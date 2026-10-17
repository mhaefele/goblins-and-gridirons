package com.mhaefele.gridiron.game.action.pending.armourcheck

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.game.legalmoves.hasDoOver
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import org.springframework.stereotype.Component

/** Legal moves for [ArmourCheckContinuation] — Bladed / armour Do-Over. */
@Component
class ArmourCheckLegalMovesGenerator : PendingDecisionLegalMovesGenerator<ArmourCheckContinuation>() {
    override fun generate(
        state: MatchState,
        pending: ArmourCheckContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingArmourCheck
        val turn = state.currentTurnState
        val cards = state.cards
        return when (ongoing.stage) {
            OngoingArmourCheck.ArmourCheckStage.HANDLE_BLADED            -> playIfPresent(cards, turn.activeCoach, turn, BonusPlay.`Spiked Gauntlets`) + LegalAction.DeclineBonus
            OngoingArmourCheck.ArmourCheckStage.HANDLE_ARMOUR_DO_OVER -> {
                val armour = state.board.player(ongoing.playerId).profile.stats.armour
                val effective = if (ongoing.spikedGauntlets) ArmourStat.Threshold(6) else armour
                val rt = if (!DoOverRoll.mayImproveArmour(ongoing.naturalRoll!!, effective, ongoing.armourMinusOne)) {
                    emptyList()
                } else if (hasDoOver(cards, turn.activeCoach, turn)) {
                    listOf(LegalAction.AdjustArmourByDoOver(ongoing.playerId, ongoing.naturalRoll!!))
                } else {
                    emptyList()
                }
                rt + LegalAction.DeclineBonus
            }
            else                                                         -> error("ArmourCheckContinuation legal moves unexpected stage: ${ongoing.stage}")
        }
    }
}
