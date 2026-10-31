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
 * Classpath ONNX actors under ``ai-opponent/{profile}/`` → chosen legal-row index.
 *
 * Loads per profile on first use. LOOKAHEAD/HEURISTIC never pay.
 * Output is masked-argmax over live rows (same as Python deterministic MaskablePPO predict).
 * Missing / stale artifacts fail hard (re-export after schema 16).
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

    /** Deterministic legal-row index into [legalMoves] (0-based, live menu only). */
    fun chooseRow(profile: OnnxOpponentProfile, packedObs: FloatArray, legalMoves: List<IntArray>): Int {
        require(legalMoves.isNotEmpty()) { "No legal moves for ONNX chooseRow" }
        ensureLoaded(profile)
        val live = checkNotNull(sessions[profile]) { "ONNX session missing for profile=${profile.id} after ensureLoaded" }
        require(packedObs.size == AiObsLayout.OBSERVATION_VECTOR_LENGTH) { "packed obs length ${packedObs.size} != ${AiObsLayout.OBSERVATION_VECTOR_LENGTH}" }
        val padded = LongArray(AiObsLayout.MAX_LEGAL_MOVES * AiObsLayout.ACTION_FACTOR_COUNT) { -1L }
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
                    val index =
                        when (out) {
                            is LongArray -> out[0].toInt()
                            is IntArray  -> out[0]
                            is Array<*>  -> {
                                val first = out[0]
                                when (first) {
                                    is LongArray -> first[0].toInt()
                                    is IntArray  -> first[0]
                                    is Long      -> first.toInt()
                                    is Int       -> first
                                    else         -> error("Unexpected ONNX action nested type ${first?.javaClass?.name}")
                                }
                            }
                            is Long      -> out.toInt()
                            is Int       -> out
                            else         -> error("Unexpected ONNX action type ${out?.javaClass?.name}")
                        }
                    require(index in legalMoves.indices) { "ONNX action index=$index out of live legalMoves ${legalMoves.size}" }
                    return index
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
        val actionN = manifest.path("actionSpaceN").asInt(manifest.path("logitWidth").asInt())
        require(actionN == AiObsLayout.LEGAL_ROW_SCORE_WIDTH) { "ONNX actionSpaceN=$actionN != ${AiObsLayout.LEGAL_ROW_SCORE_WIDTH}" }
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
