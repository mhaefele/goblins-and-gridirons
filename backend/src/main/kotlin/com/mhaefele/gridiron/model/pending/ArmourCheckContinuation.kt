package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.armourcheck.OngoingArmourCheck
import com.mhaefele.gridiron.shared.PlayerId

/**
 * Armour Check pipeline (optional Bladed → roll → optional Do-Over).
 * Nest under KnockDown / Block / Throw Team-mate — NestedOnly; never a root pending.
 */
data class ArmourCheckContinuation(
    val ongoingArmourCheck: OngoingArmourCheck,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.ARMOUR_CHECK_CONTINUE

    override val localAwaitsCoachInput: Boolean get() = ongoingArmourCheck.awaitsInput
    override fun isFinished(): Boolean =
        ongoingArmourCheck.stage == OngoingArmourCheck.ArmourCheckStage.DONE

    override val localPromptPlayerId: PlayerId?
        get() = when (ongoingArmourCheck.stage) {
            OngoingArmourCheck.ArmourCheckStage.HANDLE_ARMOUR_DO_OVER -> ongoingArmourCheck.playerId
            else                                                      -> null
        }
}
