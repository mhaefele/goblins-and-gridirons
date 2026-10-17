package com.mhaefele.gridiron.game

import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchConfig
import com.mhaefele.gridiron.model.MatchProgress
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.board.BoardDefinition
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/**
 * Pure initial match state — roster into dugouts, [MatchConfig], turn 1 [TurnPhase.GAME_START].
 * Coin toss and challenge-deck shuffle belong at the [com.mhaefele.gridiron.api.MatchGame]
 * boundary; pass the resulting [coachA] and dealt [cards] in.
 */
object MatchSetup {
    fun setupMatch(
        id: MatchId,
        coachA: TeamSide,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        pitchDefinitionId: PitchDefinitionId,
        pitch: BoardDefinition,
        cards: CardState,
        homeControlledBy: CoachControl = CoachControl.HUMAN,
        awayControlledBy: CoachControl = CoachControl.HUMAN,
    ): MatchState {
        val config = MatchConfig(
            coachA = coachA,
            homeTeamId = homeTeam.id,
            awayTeamId = awayTeam.id,
            homeEmergencyReserves = homeTeam.emergencyReserves,
            awayEmergencyReserves = awayTeam.emergencyReserves,
            pitchDefinitionId = pitchDefinitionId,
            homeControlledBy = homeControlledBy,
            awayControlledBy = awayControlledBy,
        )
        val players = createPlayers(homeTeam, awayTeam)
        return MatchState(
            id = id,
            board = pitch.emptyBoard(players),
            config = config,
            progress = MatchProgress(
                turnNumber = 1,
            ),
            currentTurnState = TurnState(
                phase = TurnPhase.GAME_START,
                pendingDecision = null,
                activeCoach = coachA,
            ),
            cards = cards,
        )
    }
}

/** Create both team's players in [PlayerStatus.RESERVES]. */
fun createPlayers(
    homeTeam: TeamCard,
    awayTeam: TeamCard,
): List<Player> {
    require(homeTeam.roster.isNotEmpty() && awayTeam.roster.isNotEmpty()) { "Setup requires non-empty rosters" }
    return playersFor(TeamSide.HOME, homeTeam.roster) + playersFor(TeamSide.AWAY, awayTeam.roster)
}

private fun playersFor(team: TeamSide, roster: List<PositionProfile>): List<Player> =
    roster.mapIndexed { index, profile ->
        Player(setupPlayerId(team, index), team, profile, PlayerStatus.RESERVES, heldBallId = null)
    }

private fun setupPlayerId(team: TeamSide, index: Int): PlayerId {
    val prefix = when (team) {
        TeamSide.HOME -> "h"
        TeamSide.AWAY -> "a"
    }
    return PlayerId("$prefix$index")
}
