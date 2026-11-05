package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component
import kotlin.math.abs

/**
 * Soft-mask / expected: marked → carrier sidestep; open → carrier EZ run.
 * PASS only on a Run touchdown — opening sidestep and non-Run TDs must not grade PASS.
 */
@Component
class MarkedRunTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.MARKED_RUN
    override val expectedBudgetedActions = 2

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val scoringEndZone = trainerSide.opposite()
        // Exactly 2 rows out: sidestep (1) then run in (1). Marked players cannot Run.
        val carrier = setup.placeRandomPlayerWithBall(
            trainerSide,
            playerFilter = setup::isFixedMove,
            setup.withinRowsOf(scoringEndZone, 2..2, excludeEndZone = true),
        )
        placeRearwardMarker(setup, carrier, scoringEndZone, trainerSide.opposite())
        setup.scatterRemainingReserves(trainerSide.opposite(), maxPlayers = difficulty, avoid = carrier.adjacentSquares())
    }

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        val carrier = trainerBallCarrier(state, trainerSide)
        require(standingStatus(carrier, state.board) == StandingStatus.MARKED) { "MARKED_RUN: carrier ${carrier.id} is not marked" }
        val carrierLegals = legals.filterIsInstance<LegalAction.PlayerLegalAction>().filter { it.playerId == carrier.id }
        val canSidestep = carrierLegals.any { it is LegalAction.Sidestep }
        val canRunIn = runsIntoEndZone(carrierLegals, state.board, trainerSide.opposite())
        require(canSidestep || canRunIn) { "MARKED_RUN: marked carrier has neither a legal sidestep nor a run into the end zone" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        val carrier = trainerBallCarrierOrNull(state, trainerSide) ?: return false
        return action is LegalAction.Sidestep && action.playerId == carrier.id
    }

    override fun isPassingOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isSoftMaskOpeningMove(action, state, trainerSide)

    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isScoringRun(action, state, trainerSide)

    override fun isPassingFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isScoringRun(action, state, trainerSide)

    override fun drillStatus(before: MatchState, after: MatchState, action: LegalAction): TrainingDrillStatus =
        drillStatusForTouchdownByRun(before, after, action) ?: TrainingDrillStatus.STILL_IN_PROGRESS

    private fun isScoringRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        val carrier = trainerBallCarrierOrNull(state, trainerSide) ?: return false
        if (action !is LegalAction.Run || action.playerId != carrier.id) return false
        return state.board.squareAt(action.path.squares.last()).isEndZone(trainerSide.opposite())
    }

    private fun placeRearwardMarker(setup: TrainingBoardBuilder, carrier: PlacedPlayer, scoringEndZone: TeamSide, markerTeam: TeamSide) {
        val ezRow = setup.endZoneRow(scoringEndZone)
        val carrierDist = abs(carrier.square.coordinate.y - ezRow)
        // Marker one row farther from the EZ — carrier chooses how to sidestep open.
        val rearward = setup.adjacentSquares(carrier.square).filter { square ->
            setup.emptyPlayable(square) && abs(square.coordinate.y - ezRow) == carrierDist + 1
        }
        check(rearward.isNotEmpty()) { "MARKED_RUN: no rearward square for marker" }
        setup.placeRandomReserve(markerTeam, rearward)
    }
}
