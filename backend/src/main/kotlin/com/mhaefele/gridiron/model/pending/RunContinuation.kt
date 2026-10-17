package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.run.OngoingRun
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

data class RunContinuation(
    val ongoingRun: OngoingRun,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.RUN_CONTINUE

    override val delegate: ActionContinuation?
        get() = ongoingRun.nestedMark ?: ongoingRun.nestedThrow

    override val localRespondingCoach: TeamSide? get() = ongoingRun.respondingCoach

    override val localAwaitsCoachInput: Boolean get() = ongoingRun.stage.awaitsInput
    override fun isFinished(): Boolean =
        ongoingRun.stage == OngoingRun.RunStage.DONE

    override val localPromptAbility: PlayerAbility? get() = ongoingRun.offeredAbility

    override val localPromptPlayerId: PlayerId?
        get() =
            // Only stages that park without a nest — DELEGATE_* use [delegate].
            when (ongoingRun.stage) {
                OngoingRun.RunStage.AWAITING_PATH,
                OngoingRun.RunStage.HANDLE_SPRINT,
                     -> ongoingRun.playerId
                OngoingRun.RunStage.CHOOSE_INTERRUPT_MARK    -> checkNotNull(ongoingRun.interruptMarkPlayerId) { "CHOOSE_INTERRUPT_MARK missing interruptMarkPlayerId" }
                OngoingRun.RunStage.HANDLE_ABILITY_FOLLOW_UP -> checkNotNull(ongoingRun.offeredAbilityPlayerId) { "HANDLE_ABILITY_FOLLOW_UP missing offeredAbilityPlayerId" }
                OngoingRun.RunStage.HANDLE_CUT_OFF      -> null
                else                                         -> error("promptPlayerId unexpected Run stage ${ongoingRun.stage}")
            }
}
