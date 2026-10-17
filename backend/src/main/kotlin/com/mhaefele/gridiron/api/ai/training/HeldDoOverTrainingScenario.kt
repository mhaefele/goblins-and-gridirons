package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.ThrowRulerOffsets
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.stereotype.Component
import kotlin.math.abs

/** "Failed Short throw with Do-Over in hand — adjust, don't decline." */
@Component
class HeldDoOverTrainingScenario : AbstractHeldCheckTrainingScenario() {
    override val scenario = TrainingScenarioEnum.HELD_DO_OVER

    override fun placementDiceScript(): (ScriptedDiceRoller.() -> Unit)? =
        TrainingScenarioPlacementDice.FAILED_SHORT_THROW

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val thrower = setup.placeRandomPlayerWithBall(trainerSide, midfield(setup, trainerSide))
        val receiverSquares = setup.board.allSquares().filter { square ->
            val dx = square.coordinate.x - thrower.coordinate.x
            val dy = square.coordinate.y - thrower.coordinate.y
            midfield(setup, trainerSide)(square) &&
                ThrowRulerOffsets.short.any { it.dx == dx && it.dy == dy } &&
                abs(dx) + abs(dy) >= 2
        }
        check(receiverSquares.isNotEmpty()) { "HELD_DO_OVER: no short receiver square from ${thrower.coordinate}" }
        val receiver = setup.placeRandomReserve(trainerSide, receiverSquares, playerFilter = setup::canHoldBall)
        val clearAround = thrower.adjacentSquares() + receiver.adjacentSquares()
        scatterDifficultyExtras(setup, trainerSide, difficulty, trainerAvoid = clearAround, opponentAvoid = clearAround)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState =
        state.copy(cards = state.cards.withBonusInHand(trainerSide, BonusPlay.`Do-Over`))

    override fun actionToPrime(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>): LegalAction {
        val thrower = trainerBallCarrier(state, trainerSide)
        return legals.filterIsInstance<LegalAction.Throw>().firstOrNull { it.playerId == thrower.id && it.target.range == ThrowRange.SHORT } ?: error("HELD_DO_OVER: no short Throw from carrier ${thrower.id}")
    }

    override fun isHeldCheckLegal(action: LegalAction): Boolean =
        action is LegalAction.AdjustThrowByDoOver || action is LegalAction.DeclineBonus

    override fun isBestHeldCheckMove(action: LegalAction, state: MatchState): Boolean =
        action is LegalAction.AdjustThrowByDoOver

    private fun midfield(setup: TrainingBoardBuilder, team: TeamSide): (com.mhaefele.gridiron.model.board.BoardSquare) -> Boolean = { square ->
        setup.emptyPlayable(square) && !square.isEndZone(team) && !square.isEndZone(team.opposite()) && !square.isTrapdoor()
    }
}
