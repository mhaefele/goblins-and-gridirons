package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** "OS Blitzer blocks; keep a Smash pool, otherwise re-roll." */
@Component
class HeldSsRerollTrainingScenario : AbstractHeldCheckTrainingScenario() {
    override val scenario = TrainingScenarioEnum.HELD_SS_REROLL
    override val trainerTeamFilter: (TeamCard) -> Boolean =
        { TrainingScenarioTeamPicker.rosterHasAbility(it, PlayerAbility.`Second Swing`) }

    override fun placementDiceScript(): (ScriptedDiceRoller.() -> Unit)? =
        TrainingScenarioPlacementDice.SECOND_SWING_BLOCK

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val target = setup.placeRandomReserve(trainerSide.opposite(), setup::emptyPlayable)
        setup.placeRandomReserve(trainerSide, setup.emptyAdjacentSquares(target.square)) { it.hasAbility(PlayerAbility.`Second Swing`) }
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = target.adjacentSquares(), trainerAvoid = target.adjacentSquares())
    }

    override fun actionToPrime(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>): LegalAction {
        val os = state.board.players.values.singleOrNull { it.team == trainerSide && it.hasAbility(PlayerAbility.`Second Swing`) } ?: error("HELD_SS_REROLL: Second Swing not on board")
        return legals.filterIsInstance<LegalAction.Block>().firstOrNull { it.playerId == os.id } ?: error("HELD_SS_REROLL: no Block from Second Swing ${os.id}")
    }

    override fun isHeldCheckLegal(action: LegalAction): Boolean = action is LegalAction.ChooseBlockReroll

    override fun isBestHeldCheckMove(action: LegalAction, state: MatchState): Boolean {
        if (action !is LegalAction.ChooseBlockReroll) return false
        val shouldReroll = DoOverRoll.mayImproveBlock(state.board, action.playerId, action.faces)
        return action.reroll == shouldReroll
    }
}
