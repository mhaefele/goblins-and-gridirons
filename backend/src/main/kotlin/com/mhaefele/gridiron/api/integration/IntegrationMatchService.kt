package com.mhaefele.gridiron.api.integration

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.ai.AiTurnWait
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.integration.dto.IntegrationCreateMatchResponse
import com.mhaefele.gridiron.api.integration.dto.IntegrationMatchStateDto
import com.mhaefele.gridiron.api.integration.mapping.toIntegrationMatchSnapshotDto
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryDomainEventRepository
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.springframework.stereotype.Component

@Component
class IntegrationMatchService(
    private val repository: InMemoryMatchRepository,
    private val eventRepository: InMemoryDomainEventRepository,
    private val matchGame: MatchGame,
    private val turnWait: AiTurnWait,
) {
    fun createMatch(): IntegrationCreateMatchResponse {
        val agentSeat = DEFAULT_AGENT_SEAT
        val (homeTeamId, awayTeamId) = randomMatchup()
        val outcome = matchGame.createAndStartMatch(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            pitchDefinitionId = DEFAULT_PITCH,
            homeControlledBy = seatControl(agentSeat, TeamSide.HOME),
            awayControlledBy = seatControl(agentSeat, TeamSide.AWAY),
            computerOpponent = DEFAULT_COMPUTER,
        )
        return IntegrationCreateMatchResponse(
            matchId = outcome.newState.id,
            agentSeat = agentSeat,
            state = matchState(outcome.newState, waitForSeat = agentSeat, viewerSeat = agentSeat),
        )
    }

    fun matchState(matchId: MatchId, waitForSeat: TeamSide?, viewerSeat: TeamSide): IntegrationMatchStateDto =
        matchState(repository.findById(matchId), waitForSeat, viewerSeat)

    fun matchState(state: MatchState, waitForSeat: TeamSide?, viewerSeat: TeamSide): IntegrationMatchStateDto {
        val resolved = if (waitForSeat == null || readyForSeat(state, waitForSeat)) state else turnWait.untilSeatOrTerminal(state.id, waitForSeat)
        return toStateDto(resolved, viewerSeat = viewerSeat)
    }

    fun play(matchId: MatchId, action: LegalAction, waitForSeat: TeamSide?, viewerSeat: TeamSide): IntegrationMatchStateDto =
        play(matchId, action.id, waitForSeat, viewerSeat)

    fun play(matchId: MatchId, legalActionId: LegalActionId, waitForSeat: TeamSide?, viewerSeat: TeamSide): IntegrationMatchStateDto {
        val beforeState = repository.findById(matchId)
        require(!beforeState.isGameEnded()) { "Cannot play on a finished match" }
        val beforeEventId = beforeState.lastEventId
        val outcome = matchGame.submitActionOutcome(matchId, legalActionId)
        val afterState = if (waitForSeat == null || readyForSeat(outcome.newState, waitForSeat)) outcome.newState else turnWait.untilSeatOrTerminal(matchId, waitForSeat)
        return toStateDto(afterState, viewerSeat = viewerSeat, chosenLegalActionId = legalActionId, events = eventRepository.findByMatchIdAfter(beforeState.id, beforeEventId))
    }

    private fun readyForSeat(state: MatchState, seat: TeamSide): Boolean =
        state.isGameEnded() || state.actingSeat() == seat

    private fun toStateDto(
        state: MatchState,
        viewerSeat: TeamSide,
        chosenLegalActionId: LegalActionId? = null,
        events: List<StoredDomainEvent> = emptyList(),
    ): IntegrationMatchStateDto {
        val acting = if (state.isGameEnded()) null else state.actingSeat()
        val legals = matchGame.legalActionsForViewer(state, viewerSeat)
        return IntegrationMatchStateDto(
            snapshot = toIntegrationMatchSnapshotDto(state, viewerSeat),
            legalActions = legals,
            actingSeat = acting,
            chosenLegalActionId = chosenLegalActionId,
            events = events,
        )
    }

    private fun seatControl(agentSeat: TeamSide, seat: TeamSide): CoachControl =
        if (seat == agentSeat) CoachControl.TRAINER else CoachControl.COMPUTER

    private fun randomMatchup(): Pair<TeamCatalogId, TeamCatalogId> {
        val teams = TeamCatalog.allTeams()
        val home = teams.random()
        val awayPool = teams.filter { it.id != home.id }
        val away = if (awayPool.isEmpty()) home else awayPool.random()
        return home.id to away.id
    }

    private companion object {
        val DEFAULT_AGENT_SEAT: TeamSide = TeamSide.HOME
        val DEFAULT_PITCH: PitchDefinitionId = PitchDefinitionId("default")
        val DEFAULT_COMPUTER: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD
    }
}
