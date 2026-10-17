package com.mhaefele.gridiron.api.dto.request

import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind

/** Body for `POST /api/matches` — selects catalog teams and pitch layout. */
data class CreateMatchRequest(
    /** Home coach roster — must be a [TeamCatalogId] from `GET /api/catalog/teams`. */
    val homeTeamId: TeamCatalogId,
    /** Away coach roster — must be a [TeamCatalogId] from `GET /api/catalog/teams`. */
    val awayTeamId: TeamCatalogId,
    /** Pitch layout — must be a [PitchDefinitionId] from `GET /api/catalog/pitches`. */
    val pitchDefinitionId: PitchDefinitionId = PitchDefinitionId("default"),
    /** Defaults to human — set [CoachControl.COMPUTER] to spawn an in-process opponent for HOME. */
    val homeControlledBy: CoachControl = CoachControl.HUMAN,
    /** Defaults to human — set [CoachControl.COMPUTER] to spawn an in-process opponent for AWAY. */
    val awayControlledBy: CoachControl = CoachControl.HUMAN,
    /**
     * Strategy for COMPUTER seats. Ignored when both seats are HUMAN.
     * Default [ComputerOpponentKind.LOOKAHEAD] (product computer).
     */
    val computerOpponent: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD,
    /**
     * Classpath folder under ``ai-opponent/{onnxProfile}/`` when [computerOpponent] is [ComputerOpponentKind.ONNX].
     * Ignored for LOOKAHEAD/HEURISTIC. Default [com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession.DEFAULT_PROFILE].
     */
    val onnxProfile: String? = null,
)