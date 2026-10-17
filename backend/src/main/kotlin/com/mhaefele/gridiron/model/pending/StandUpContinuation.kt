package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.standup.OngoingStandUp
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.PlayerId

data class StandUpContinuation(
    val ongoingStandUp: OngoingStandUp,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.STAND_UP_CONTINUE
    override val localAwaitsCoachInput: Boolean get() = ongoingStandUp.awaitsInput
    override fun isFinished(): Boolean =
        ongoingStandUp.stage == OngoingStandUp.StandUpStage.DONE

    override val localPromptPlayerId: PlayerId? get() = ongoingStandUp.playerId
    override val localPromptBonusPlay: BonusPlay?
        get() = if (ongoingStandUp.stage == OngoingStandUp.StandUpStage.HANDLE_BACK_ON_YOUR_FEET) BonusPlay.`Back on Your Feet` else null
}
