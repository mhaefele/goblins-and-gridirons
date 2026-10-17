package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.sidestep.OngoingSidestep
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

data class SidestepContinuation(
    val ongoingSidestep: OngoingSidestep,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.SIDESTEP_CONTINUE
    override val localRespondingCoach: TeamSide? get() = ongoingSidestep.respondingCoach
    override val localAwaitsCoachInput: Boolean get() = ongoingSidestep.stage.awaitsInput
    override fun isFinished(): Boolean =
        ongoingSidestep.stage == OngoingSidestep.SidestepStage.DONE

    override val localPromptPlayerId: PlayerId?
        get() = when (ongoingSidestep.stage) {
            OngoingSidestep.SidestepStage.HANDLE_STICK_CLOSE -> null
            else                                           -> error("promptPlayerId unexpected Sidestep stage ${ongoingSidestep.stage}")
        }
}
