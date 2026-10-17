package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchConfig
import com.mhaefele.gridiron.model.MatchProgress
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.Score
import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.PlayerActionStep
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/** Default [MatchConfig] aligned with [GameScenario.bothTeamsDeployed] defaults. */
fun defaultMatchConfig(
    coachA: TeamSide = TeamSide.HOME,
): MatchConfig = MatchConfig(
    coachA = coachA,
    homeTeamId = TeamCatalog.DWARVES_ID,
    awayTeamId = TeamCatalog.ORCS_ID,
    homeEmergencyReserves = TeamCatalog.dwarves().emergencyReserves,
    awayEmergencyReserves = TeamCatalog.orcs().emergencyReserves,
)

/** Build [MatchState] for tests — override only what the test cares about. */
fun matchState(
    board: Board = GameScenario.bothTeamsDeployed(),
    config: MatchConfig = defaultMatchConfig(),
    trainingEpisode: TrainingEpisodeConfig? = null,
    id: MatchId = MatchId("test-match"),
    activeCoach: TeamSide = config.coachA,
    actionsRemaining: Int = 3,
    phase: TurnPhase = TurnPhase.PLAYER_ACTION,
    cards: CardState = CardState.dealInCatalogOrder(),
): MatchState {
    require(actionsRemaining in 0..3) { "actionsRemaining must be 0–3, was $actionsRemaining" }
    // Park one RPA; slots still count that pick until beginPlayerAction decrements.
    val pending = if (actionsRemaining == 0) null else RegularPlayerAction()
    return MatchState(
        id = id,
        board = board,
        config = config,
        progress = MatchProgress(turnNumber = 1),
        currentTurnState = TurnState(
            phase = phase,
            activeCoach = activeCoach,
            pendingDecision = pending,
            regularActionSlotsRemaining = actionsRemaining,
            step = if (phase == TurnPhase.PLAYER_ACTION) PlayerActionStep.SELECT else null,
        ),
        cards = cards,
        trainingEpisode = trainingEpisode,
    )
}

fun MatchState.withActionsRemaining(actionsRemaining: Int): MatchState {
    require(actionsRemaining in 0..3) { "actionsRemaining must be 0–3, was $actionsRemaining" }
    val pending = if (actionsRemaining == 0) null else RegularPlayerAction()
    return copy(
        currentTurnState = currentTurnState.copy(
            pendingDecision = pending,
            regularActionSlotsRemaining = actionsRemaining,
        ),
    )
}

fun MatchState.withActionsUsed(
    actionsUsedThisTurn: Map<PlayerId, Set<ActionType>>,
): MatchState = copy(
    currentTurnState = currentTurnState.copy(
        actionsThisTurn = actionsUsedThisTurn.flatMap { (playerId, types) ->
            types.map { TurnAction(playerId, it) }
        },
    ),
)

fun MatchState.withTurnNumber(turnNumber: Int): MatchState =
    copy(progress = progress.copy(turnNumber = turnNumber))

fun MatchState.withScores(homePoints: Int, awayPoints: Int): MatchState =
    copy(
        progress = progress.copy(
            homeScore = Score(homePoints),
            awayScore = Score(awayPoints),
        ),
    )

fun MatchState.exhaustedTurn(): MatchState =
    copy(
        currentTurnState = currentTurnState.copy(
            pendingDecision = null,
            regularActionSlotsRemaining = 0,
        ),
    )
