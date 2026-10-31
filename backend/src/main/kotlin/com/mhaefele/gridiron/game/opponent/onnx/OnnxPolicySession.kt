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
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Classpath ONNX actor trunks under ``ai-opponent/{profile}/`` → legal-row scores.
 *
 * Loads per profile on first use. LOOKAHEAD/HEURISTIC never pay.
 * Missing / stale schema-15 factor-logit artifacts fail hard (re-export after schema 16).
 */
@Component
class OnnxPolicySession(
    private val jsonMapper: JsonMapper,
    private val resourceLoader: ResourceLoader,
) : AutoCloseable {
    private data class Loaded(
        val session: OrtSession,
        val inputObsName: String,
        val inputLegalName: String,
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

    fun rowScores(profile: OnnxOpponentProfile, packedObs: FloatArray, legalMoves: List<IntArray>): FloatArray {
        ensureLoaded(profile)
        val live = checkNotNull(sessions[profile]) { "ONNX session missing for profile=${profile.id} after ensureLoaded" }
        require(packedObs.size == AiObsLayout.OBSERVATION_VECTOR_LENGTH) { "packed obs length ${packedObs.size} != ${AiObsLayout.OBSERVATION_VECTOR_LENGTH}" }
        val padded = LongArray(AiObsLayout.MAX_LEGAL_MOVES * AiObsLayout.ACTION_FACTOR_COUNT)
        for (i in legalMoves.indices) {
            require(legalMoves[i].size == AiObsLayout.ACTION_FACTOR_COUNT) { "legal row width ${legalMoves[i].size}" }
            val base = i * AiObsLayout.ACTION_FACTOR_COUNT
            for (f in 0 until AiObsLayout.ACTION_FACTOR_COUNT) {
                padded[base + f] = legalMoves[i][f].toLong()
            }
        }
        val obsShape = longArrayOf(1, packedObs.size.toLong())
        val legalShape = longArrayOf(1, AiObsLayout.MAX_LEGAL_MOVES.toLong(), AiObsLayout.ACTION_FACTOR_COUNT.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(packedObs), obsShape).use { obsTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(padded), legalShape).use { legalTensor ->
                live.session.run(mapOf(live.inputObsName to obsTensor, live.inputLegalName to legalTensor)).use { results ->
                    val out = results[0].value
                    val scores =
                        when (out) {
                            is Array<*>   -> {
                                @Suppress("UNCHECKED_CAST")
                                out[0] as FloatArray
                            }
                            is FloatArray -> out
                            else          -> error("Unexpected ONNX output type ${out?.javaClass?.name}")
                        }
                    require(scores.size == AiObsLayout.LEGAL_ROW_SCORE_WIDTH) { "ONNX row score width ${scores.size} != ${AiObsLayout.LEGAL_ROW_SCORE_WIDTH}" }
                    return scores.copyOf()
                }
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
            inputObsName = manifest.path("inputObsName").asString("packed_obs"),
            inputLegalName = manifest.path("inputLegalName").asString("legal_moves"),
            profile = profile,
            schemaVersion = manifest.path("schemaVersion").asInt(),
        )
    }

    private fun validateManifest(manifest: JsonNode, expected: OnnxOpponentProfile) {
        val schemaVersion = manifest.path("schemaVersion").asInt()
        require(schemaVersion == AiObsLayout.SCHEMA_VERSION) { "ONNX schemaVersion=$schemaVersion != runtime ${AiObsLayout.SCHEMA_VERSION} — re-export" }
        val obsLen = manifest.path("observationVectorLength").asInt()
        require(obsLen == AiObsLayout.OBSERVATION_VECTOR_LENGTH) { "ONNX observationVectorLength=$obsLen != ${AiObsLayout.OBSERVATION_VECTOR_LENGTH}" }
        val maxLegal = manifest.path("maxLegalMoves").asInt()
        require(maxLegal == AiObsLayout.MAX_LEGAL_MOVES) { "ONNX maxLegalMoves=$maxLegal != ${AiObsLayout.MAX_LEGAL_MOVES}" }
        val logitWidth = manifest.path("logitWidth").asInt()
        require(logitWidth == AiObsLayout.LEGAL_ROW_SCORE_WIDTH) { "ONNX logitWidth=$logitWidth != ${AiObsLayout.LEGAL_ROW_SCORE_WIDTH}" }
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
