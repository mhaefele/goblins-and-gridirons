package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.ballguard.OngoingBallGuard
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

data class BallGuardContinuation(
    val ongoingBallGuard: OngoingBallGuard,
    override val localRespondingCoach: TeamSide? = null,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.BALL_GUARD_CONTINUE
    override val localAwaitsCoachInput: Boolean get() = ongoingBallGuard.stage.awaitsInput
    override fun isFinished(): Boolean =
        ongoingBallGuard.stage == OngoingBallGuard.BallGuardStage.DONE

    override val localPromptAbility: PlayerAbility? get() = PlayerAbility.`Ball Guard`
    override val localPromptPlayerId: PlayerId? get() = ongoingBallGuard.playerId
}
