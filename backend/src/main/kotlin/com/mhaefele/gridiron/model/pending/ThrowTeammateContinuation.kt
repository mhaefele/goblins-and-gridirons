package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.throwteammate.OngoingThrowTeammate
import com.mhaefele.gridiron.shared.PlayerId

data class ThrowTeammateContinuation(
    val ongoingThrowTeammate: OngoingThrowTeammate,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.THROW_TEAMMATE_CONTINUE
    override val countsAsPlayerAction: Boolean get() = true

    override val delegate: ActionContinuation?
        get() = ongoingThrowTeammate.nestedKnockDown ?: ongoingThrowTeammate.nestedArmourCheck

    override val localAwaitsCoachInput: Boolean
        get() = ongoingThrowTeammate.stage == OngoingThrowTeammate.ThrowTeammateStage.AWAITING_LANDING
    override fun isFinished(): Boolean =
        ongoingThrowTeammate.stage == OngoingThrowTeammate.ThrowTeammateStage.DONE
    override fun awaitsOpenActionComplete(): Boolean =
        isFinished() && !ongoingThrowTeammate.cancelled

    override val localPromptPlayerId: PlayerId? get() = ongoingThrowTeammate.throwerId
}
