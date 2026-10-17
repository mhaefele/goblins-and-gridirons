package com.mhaefele.gridiron.api

import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Product computer-opponent catalog for match create / MatchupSplash.
 *
 * Built-in kinds always listed. ONNX rows appear only when ``ai-opponent/{profile}/`` artifacts exist —
 * titles/descriptions come from [ComputerOpponentKind] / [OnnxOpponentProfile], not a Python mirror.
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
            OnnxOpponentProfile.entries
                .filter { onnxPolicy.artifactsPresent(it) }
                .map { profile ->
                    ComputerOpponentOptionDto(
                        kind = ComputerOpponentKind.ONNX,
                        onnxProfile = profile.id,
                        title = profile.title,
                        description = profile.description,
                    )
                }
        return ComputerOpponentCatalogDto(
            opponents = builtIn + onnx,
            defaultKind = ComputerOpponentKind.LOOKAHEAD,
            defaultOnnxProfile = null,
        )
    }
}

data class ComputerOpponentCatalogDto(
    val opponents: List<ComputerOpponentOptionDto>,
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
