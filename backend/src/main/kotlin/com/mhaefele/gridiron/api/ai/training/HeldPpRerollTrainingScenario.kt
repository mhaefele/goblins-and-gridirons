package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.ThrowRulerOffsets
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.stereotype.Component
import kotlin.math.abs

/** "Pinpoint Passer fails a Short throw — re-roll it." */
@Component
class HeldPpRerollTrainingScenario : AbstractHeldCheckTrainingScenario() {
    override val scenario = TrainingScenarioEnum.HELD_PP_REROLL
    override val trainerTeamFilter: (TeamCard) -> Boolean =
        { TrainingScenarioTeamPicker.rosterHasAbility(it, PlayerAbility.`Pinpoint Passer`) }

    override fun placementDiceScript(): (ScriptedDiceRoller.() -> Unit)? =
        TrainingScenarioPlacementDice.FAILED_SHORT_THROW

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val thrower = setup.placeRandomPlayerWithBall(
            trainerSide,
            playerFilter = { it.hasAbility(PlayerAbility.`Pinpoint Passer`) },
            squareFilter = { _ -> midfield(setup, trainerSide) },
        )
        val receiverSquares = setup.board.allSquares().filter { square ->
            val dx = square.coordinate.x - thrower.coordinate.x
            val dy = square.coordinate.y - thrower.coordinate.y
            midfield(setup, trainerSide)(square) &&
                ThrowRulerOffsets.short.any { it.dx == dx && it.dy == dy } &&
                abs(dx) + abs(dy) >= 2
        }
        check(receiverSquares.isNotEmpty()) { "HELD_PP_REROLL: no short receiver square from ${thrower.coordinate}" }
        val receiver = setup.placeRandomReserve(trainerSide, receiverSquares, playerFilter = setup::canHoldBall)
        // Keep the thrower Open — Marked carriers cannot Throw.
        val clearAround = thrower.adjacentSquares() + receiver.adjacentSquares()
        scatterDifficultyExtras(setup, trainerSide, difficulty, trainerAvoid = clearAround, opponentAvoid = clearAround)
    }

    override fun actionToPrime(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>): LegalAction {
        val thrower = state.board.players.values.singleOrNull { it.team == trainerSide && it.hasAbility(PlayerAbility.`Pinpoint Passer`) } ?: error("HELD_PP_REROLL: Pinpoint Passer not on board")
        return legals.filterIsInstance<LegalAction.Throw>().firstOrNull { it.playerId == thrower.id && it.target.range == ThrowRange.SHORT } ?: error("HELD_PP_REROLL: no short Throw from Pinpoint Passer ${thrower.id}")
    }

    override fun isHeldCheckLegal(action: LegalAction): Boolean = action is LegalAction.ChooseThrowReroll

    override fun isBestHeldCheckMove(action: LegalAction, state: MatchState): Boolean =
        action is LegalAction.ChooseThrowReroll && action.reroll

    private fun midfield(setup: TrainingBoardBuilder, team: TeamSide): (com.mhaefele.gridiron.model.board.BoardSquare) -> Boolean = { square ->
        setup.emptyPlayable(square) && !square.isEndZone(team) && !square.isEndZone(team.opposite()) && !square.isTrapdoor()
    }
}
