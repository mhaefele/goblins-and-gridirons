package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import jakarta.annotation.PreDestroy
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Classpath ONNX factor-logit trunks for schemaVersion=15.
 *
 * Delete with the rest of `schema15` once opponents are re-exported at schema 16+.
 */
@Component
class Schema15OnnxSession(
    private val jsonMapper: JsonMapper,
    private val resourceLoader: ResourceLoader,
) : AutoCloseable {
    private data class Loaded(
        val session: OrtSession,
        val inputName: String,
        val profile: OnnxOpponentProfile,
        val schemaVersion: Int,
    )

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = ConcurrentHashMap<OnnxOpponentProfile, Loaded>()

    fun unavailabilityReason(profile: OnnxOpponentProfile, manifest: JsonNode): String? =
        try {
            validateManifest(manifest, profile)
            null
        } catch (ex: IllegalArgumentException) {
            ex.message
        } catch (ex: IllegalStateException) {
            ex.message
        }

    fun ensureLoaded(profile: OnnxOpponentProfile) {
        if (sessions.containsKey(profile)) return
        sessions.computeIfAbsent(profile) { openSession(it) }
    }

    fun factorLogits(profile: OnnxOpponentProfile, packedObs: FloatArray): FloatArray {
        ensureLoaded(profile)
        val live = checkNotNull(sessions[profile]) { "schema-15 ONNX session missing for profile=${profile.id}" }
        require(packedObs.size == Schema15Layout.OBSERVATION_VECTOR_LENGTH) {
            "packed obs length ${packedObs.size} != ${Schema15Layout.OBSERVATION_VECTOR_LENGTH}"
        }
        val shape = longArrayOf(1, packedObs.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(packedObs), shape).use { input ->
            live.session.run(mapOf(live.inputName to input)).use { results ->
                val out = results[0].value
                val logits =
                    when (out) {
                        is Array<*>   -> {
                            @Suppress("UNCHECKED_CAST")
                            out[0] as FloatArray
                        }
                        is FloatArray -> out
                        else          -> error("Unexpected ONNX output type ${out?.javaClass?.name}")
                    }
                require(logits.size == Schema15Layout.ACTION_LOGIT_WIDTH) {
                    "ONNX logit width ${logits.size} != ${Schema15Layout.ACTION_LOGIT_WIDTH}"
                }
                return logits.copyOf()
            }
        }
    }

    @PreDestroy
    override fun close() {
        sessions.values.forEach { it.session.close() }
        sessions.clear()
    }

    private fun openSession(profile: OnnxOpponentProfile): Loaded {
        val manifestResource = resourceLoader.getResource(OnnxPolicySession.manifestLocation(profile))
        check(manifestResource.exists()) {
            "Missing classpath resource /${OnnxPolicySession.CLASSPATH_PREFIX}/${profile.id}/manifest.json"
        }
        val manifest = manifestResource.inputStream.use { jsonMapper.readTree(it) }
        validateManifest(manifest, profile)
        val onnxName = manifest.path("onnxFile").asString("policy.onnx")
        val modelResource = resourceLoader.getResource(OnnxPolicySession.modelLocation(profile, onnxName))
        check(modelResource.exists()) {
            "Missing classpath resource /${OnnxPolicySession.CLASSPATH_PREFIX}/${profile.id}/$onnxName"
        }
        val session = env.createSession(modelResource.inputStream.use { it.readBytes() }, OrtSession.SessionOptions())
        return Loaded(
            session = session,
            inputName = manifest.path("inputName").asString("packed_obs"),
            profile = profile,
            schemaVersion = manifest.path("schemaVersion").asInt(),
        )
    }

    private fun validateManifest(manifest: JsonNode, expected: OnnxOpponentProfile) {
        val schemaVersion = manifest.path("schemaVersion").asInt()
        require(schemaVersion == Schema15Layout.SCHEMA_VERSION) {
            "ONNX opponent '${expected.id}' is not schema 15 (schemaVersion=$schemaVersion)"
        }
        val obsLen = manifest.path("observationVectorLength").asInt()
        require(obsLen == Schema15Layout.OBSERVATION_VECTOR_LENGTH) {
            "ONNX opponent '${expected.id}' observationVectorLength=$obsLen != ${Schema15Layout.OBSERVATION_VECTOR_LENGTH}"
        }
        val headsNode = manifest.path("actionHeadSizes")
        require(headsNode.isArray) { "ONNX opponent '${expected.id}' actionHeadSizes must be an array" }
        val heads = (0 until headsNode.size()).map { headsNode[it].asInt() }
        require(heads == Schema15Layout.ACTION_HEAD_SIZES.toList()) {
            "ONNX opponent '${expected.id}' actionHeadSizes=$heads != ${Schema15Layout.ACTION_HEAD_SIZES.toList()}"
        }
        val manifestProfile = manifest.path("profile").asString(expected.id)
        require(manifestProfile == expected.id) {
            "ONNX opponent folder '${expected.id}' has manifest profile=$manifestProfile"
        }
    }
}
