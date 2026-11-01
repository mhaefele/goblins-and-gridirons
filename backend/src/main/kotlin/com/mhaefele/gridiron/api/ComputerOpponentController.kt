package com.mhaefele.gridiron.api

import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Product computer-opponent catalog for match create / MatchupSplash / train harness.
 *
 * [ComputerOpponentCatalogDto.opponents] = seatable only (LOOKAHEAD/HEURISTIC always; ONNX when
 * [OnnxPolicySession.isSeatable] — schema 15 factor-logit or schema 16 legal-row).
 * [ComputerOpponentCatalogDto.unavailable] explains skipped ONNX profiles (unsupported schema,
 * missing files, …) so clients can log a clear skip reason.
 *
 * SCHEMA15_DELETE: drop “schema 15 factor-logit or” once the schema15 package is gone.
 */
@RestController
@RequestMapping("/api/computer-opponents")
class ComputerOpponentController(
    private val onnxPolicy: OnnxPolicySession,
) {
    @GetMapping
    fun list(): ComputerOpponentCatalogDto {
        val builtIn =
            ComputerOpponentKind.entries
                .filter { it != ComputerOpponentKind.ONNX }
                .map { kind ->
                    ComputerOpponentOptionDto(
                        kind = kind,
                        onnxProfile = null,
                        title = kind.title,
                        description = kind.description,
                    )
                }
        val onnx =
            onnxPolicy.availableProfiles()
                .map { profile ->
                    ComputerOpponentOptionDto(
                        kind = ComputerOpponentKind.ONNX,
                        onnxProfile = profile.id,
                        title = profile.title,
                        description = profile.description,
                    )
                }
        val unavailable =
            OnnxOpponentProfile.entries.mapNotNull { profile ->
                val reason = onnxPolicy.unavailabilityReason(profile) ?: return@mapNotNull null
                ComputerOpponentUnavailableDto(
                    kind = ComputerOpponentKind.ONNX,
                    onnxProfile = profile.id,
                    reason = reason,
                )
            }
        return ComputerOpponentCatalogDto(
            opponents = builtIn + onnx,
            unavailable = unavailable,
            defaultKind = ComputerOpponentKind.LOOKAHEAD,
            defaultOnnxProfile = null,
        )
    }
}

data class ComputerOpponentCatalogDto(
    val opponents: List<ComputerOpponentOptionDto>,
    /** Present-but-unusable (or missing) ONNX profiles with why — empty when all seatable. */
    val unavailable: List<ComputerOpponentUnavailableDto> = emptyList(),
    val defaultKind: ComputerOpponentKind,
    val defaultOnnxProfile: String?,
)

/** One seat-able computer opponent: [kind] + optional [onnxProfile] when [kind] is ONNX. */
data class ComputerOpponentOptionDto(
    val kind: ComputerOpponentKind,
    /** Classpath folder under ``ai-opponent/`` when [kind] is [ComputerOpponentKind.ONNX]; otherwise null. */
    val onnxProfile: String?,
    val title: String,
    val description: String,
)

/** ONNX (or future) seat the server will refuse, with a human reason. */
data class ComputerOpponentUnavailableDto(
    val kind: ComputerOpponentKind,
    val onnxProfile: String?,
    val reason: String,
)
