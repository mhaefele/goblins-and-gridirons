package com.mhaefele.gridiron.game.opponent.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import jakarta.annotation.PreDestroy
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Classpath ONNX actor trunks under ``ai-opponent/{profile}/`` → factor logits.
 *
 * Loads per profile on first use (~11MB + OrtSession each). LOOKAHEAD/HEURISTIC never pay.
 * Missing profile artifacts fail hard (run ``ai/export_opponent.sh``).
 */
@Component
class OnnxPolicySession(
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

    /** Profiles that have manifest + model on the classpath. */
    fun availableProfiles(): List<OnnxOpponentProfile> =
        OnnxOpponentProfile.entries.filter { artifactsPresent(it) }

    fun artifactsPresent(profile: OnnxOpponentProfile): Boolean {
        val manifest = resourceLoader.getResource(manifestLocation(profile))
        if (!manifest.exists()) return false
        val onnxName =
            manifest.inputStream.use { stream ->
                jsonMapper.readTree(stream).path("onnxFile").asString("policy.onnx")
            }
        return resourceLoader.getResource(modelLocation(profile, onnxName)).exists()
    }

    /** Wire / request id — unknown ids are absent, not invalid. */
    fun artifactsPresent(profileId: String): Boolean {
        val profile = OnnxOpponentProfile.fromId(profileId) ?: return false
        return artifactsPresent(profile)
    }

    fun ensureLoaded(profile: OnnxOpponentProfile) {
        if (sessions.containsKey(profile)) return
        sessions.computeIfAbsent(profile) { openSession(it) }
    }

    fun factorLogits(profile: OnnxOpponentProfile, packedObs: FloatArray): FloatArray {
        ensureLoaded(profile)
        val live = checkNotNull(sessions[profile]) { "ONNX session missing for profile=${profile.id} after ensureLoaded" }
        require(packedObs.size == AiObsLayout.OBSERVATION_VECTOR_LENGTH) { "packed obs length ${packedObs.size} != ${AiObsLayout.OBSERVATION_VECTOR_LENGTH}" }
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
                require(logits.size == AiObsLayout.ACTION_LOGIT_WIDTH) { "ONNX logit width ${logits.size} != ${AiObsLayout.ACTION_LOGIT_WIDTH}" }
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
        val manifestResource = resourceLoader.getResource(manifestLocation(profile))
        check(manifestResource.exists()) { "Missing classpath resource /$CLASSPATH_PREFIX/${profile.id}/manifest.json — run ai/export_opponent.sh ${profile.id}" }
        val manifest = manifestResource.inputStream.use { jsonMapper.readTree(it) }
        validateManifest(manifest, profile)
        val onnxName = manifest.path("onnxFile").asString("policy.onnx")
        val modelResource = resourceLoader.getResource(modelLocation(profile, onnxName))
        check(modelResource.exists()) { "Missing classpath resource /$CLASSPATH_PREFIX/${profile.id}/$onnxName — run ai/export_opponent.sh ${profile.id}" }
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
        require(schemaVersion == AiObsLayout.SCHEMA_VERSION) { "ONNX schemaVersion=$schemaVersion != runtime ${AiObsLayout.SCHEMA_VERSION} — re-export" }
        val obsLen = manifest.path("observationVectorLength").asInt()
        require(obsLen == AiObsLayout.OBSERVATION_VECTOR_LENGTH) { "ONNX observationVectorLength=$obsLen != ${AiObsLayout.OBSERVATION_VECTOR_LENGTH}" }
        val headsNode = manifest.path("actionHeadSizes")
        require(headsNode.isArray) { "ONNX actionHeadSizes must be an array" }
        val heads = (0 until headsNode.size()).map { headsNode[it].asInt() }
        require(heads == AiObsLayout.ACTION_HEAD_SIZES.toList()) { "ONNX actionHeadSizes=$heads != ${AiObsLayout.ACTION_HEAD_SIZES.toList()}" }
        val manifestProfile = manifest.path("profile").asString(expected.id)
        require(manifestProfile == expected.id) { "ONNX manifest profile=$manifestProfile != requested ${expected.id}" }
    }

    companion object {
        const val CLASSPATH_PREFIX = "ai-opponent"
        val DEFAULT_PROFILE: OnnxOpponentProfile = OnnxOpponentProfile.GENERAL

        fun manifestLocation(profile: OnnxOpponentProfile): String =
            "classpath:$CLASSPATH_PREFIX/${profile.id}/manifest.json"

        fun modelLocation(profile: OnnxOpponentProfile, onnxFile: String): String =
            "classpath:$CLASSPATH_PREFIX/${profile.id}/$onnxFile"
    }
}
