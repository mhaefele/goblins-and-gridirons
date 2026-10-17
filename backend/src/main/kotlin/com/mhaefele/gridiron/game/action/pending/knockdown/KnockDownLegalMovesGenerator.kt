package com.mhaefele.gridiron.game.action.pending.knockdown

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.armourcheck.ArmourCheckLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.ballguard.BallGuardLegalMovesGenerator
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.KnockDownContinuation
import org.springframework.stereotype.Component

/** Legal moves for [KnockDownContinuation] — Ball Guard / nested Armour Check. */
@Component
class KnockDownLegalMovesGenerator(
    private val ballGuardLegalMovesGenerator: BallGuardLegalMovesGenerator,
    private val armourCheckLegalMovesGenerator: ArmourCheckLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<KnockDownContinuation>() {
    override fun generate(
        state: MatchState,
        pending: KnockDownContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingKnockDown
        return when (ongoing.stage) {
            OngoingKnockDown.KnockDownStage.DELEGATE_BALL_GUARD   -> {
                val nested = checkNotNull(ongoing.nestedBallGuard) { "DELEGATE_BALL_GUARD missing nestedBallGuard" }
                ballGuardLegalMovesGenerator.generate(state, nested)
            }
            OngoingKnockDown.KnockDownStage.DELEGATE_ARMOUR_CHECK -> {
                val nested = checkNotNull(ongoing.nestedArmourCheck) { "DELEGATE_ARMOUR_CHECK missing nestedArmourCheck" }
                armourCheckLegalMovesGenerator.generate(state, nested)
            }
            else                                                  -> error("KnockDownContinuation legal moves unexpected stage: ${ongoing.stage}")
        }
    }
}
