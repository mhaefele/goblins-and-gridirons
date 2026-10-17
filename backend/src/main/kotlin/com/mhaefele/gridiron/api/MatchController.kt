package com.mhaefele.gridiron.api

import com.mhaefele.gridiron.api.dto.request.CreateMatchRequest
import com.mhaefele.gridiron.api.dto.response.*
import com.mhaefele.gridiron.api.mapping.toBonusHandDto
import com.mhaefele.gridiron.api.mapping.toMatchRosterDto
import com.mhaefele.gridiron.api.mapping.toMatchSnapshotDto
import com.mhaefele.gridiron.api.mapping.toPitchLayoutDto
import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/matches")
class MatchController(
    private val matchGame: MatchGame,
    private val repository: InMemoryMatchRepository,
) {
    /** Create only ([com.mhaefele.gridiron.shared.TurnPhase.GAME_START]). Client subscribes to SSE, then [start]. */
    @PostMapping
    fun create(@Valid @RequestBody request: CreateMatchRequest): CreateMatchResponse {
        val onnxProfile = resolveOnnxProfile(request)
        val state = matchGame.createPreparedMatch(
            homeTeamId = request.homeTeamId,
            awayTeamId = request.awayTeamId,
            pitchDefinitionId = request.pitchDefinitionId,
            homeControlledBy = request.homeControlledBy,
            awayControlledBy = request.awayControlledBy,
            computerOpponent = request.computerOpponent,
            onnxProfile = onnxProfile,
        )

        return CreateMatchResponse(state.id, toMatchSnapshotDto(state))
    }

    private fun resolveOnnxProfile(request: CreateMatchRequest): String {
        if (request.computerOpponent != ComputerOpponentKind.ONNX) return OnnxPolicySession.DEFAULT_PROFILE.id
        val profile = request.onnxProfile ?: OnnxPolicySession.DEFAULT_PROFILE.id
        require(OnnxOpponentProfile.fromId(profile) != null) { "Unknown onnxProfile='$profile'" }
        return profile
    }

    @PostMapping("/{matchId}/start")
    fun start(@PathVariable matchId: MatchId): MatchActionResponse {
        val outcome = matchGame.startMatch(matchId)

        return MatchActionResponse(
            snapshot = toMatchSnapshotDto(outcome.newState),
            events = outcome.events,
        )
    }

    /** Free in-memory match state (trainer / scenario churn). Idempotent. */
    @DeleteMapping("/{matchId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable matchId: MatchId) {
        matchGame.deleteMatch(matchId)
    }

    @GetMapping("/{matchId}/legal-actions")
    fun legalActions(@PathVariable matchId: MatchId): List<LegalAction> =
        matchGame.legalActions(matchId)

    @PostMapping("/{matchId}/legal-actions/submit")
    fun submitAction(@PathVariable matchId: MatchId, @RequestBody legalActionId: LegalActionId): MatchActionResponse {
        val outcome = matchGame.submitActionOutcome(matchId, legalActionId)
        return MatchActionResponse(
            snapshot = toMatchSnapshotDto(outcome.newState),
            events = outcome.events,
        )
    }

    @GetMapping("/{matchId}/snapshot")
    fun snapshot(@PathVariable matchId: MatchId): MatchSnapshotDto =
        toMatchSnapshotDto(repository.findById(matchId))

    @GetMapping("/{matchId}/pitch")
    fun pitch(@PathVariable matchId: MatchId): PitchLayoutDto =
        toPitchLayoutDto(repository.findById(matchId))

    @GetMapping("/{matchId}/roster")
    fun roster(@PathVariable matchId: MatchId): MatchRosterDto =
        toMatchRosterDto(repository.findById(matchId))

    /**
     * Seat-private bonus hand — never fold into the shared snapshot.
     * [seat] must be the viewing coach (HOME or AWAY).
     */
    @GetMapping("/{matchId}/bonus-hand")
    fun bonusHand(
        @PathVariable matchId: MatchId,
        @RequestParam seat: TeamSide,
    ): BonusHandDto =
        toBonusHandDto(repository.findById(matchId).cards, seat)
}
