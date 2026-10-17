package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.knockdown.OngoingKnockDown

/**
 * Standing knock-down pipeline (Ball Guard → optional Armour Check).
 * Nest under Block / Throw Team-mate — NestedOnly; never a root pending.
 */
data class KnockDownContinuation(
    val ongoingKnockDown: OngoingKnockDown,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.KNOCK_DOWN_CONTINUE

    override val delegate: ActionContinuation?
        get() = ongoingKnockDown.nestedBallGuard ?: ongoingKnockDown.nestedArmourCheck

    override val localAwaitsCoachInput: Boolean get() = false
    override fun isFinished(): Boolean =
        ongoingKnockDown.stage == OngoingKnockDown.KnockDownStage.DONE
}
