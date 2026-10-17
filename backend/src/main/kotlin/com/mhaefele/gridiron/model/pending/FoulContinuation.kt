package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.foul.OngoingFoul
import com.mhaefele.gridiron.shared.PlayerId

data class FoulContinuation(
    val ongoingFoul: OngoingFoul,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.FOUL_CONTINUE
    override val localAwaitsCoachInput: Boolean get() = ongoingFoul.awaitsInput
    override fun isFinished(): Boolean =
        ongoingFoul.stage == OngoingFoul.FoulStage.DONE

    override val localPromptPlayerId: PlayerId? get() = ongoingFoul.playerId
}
