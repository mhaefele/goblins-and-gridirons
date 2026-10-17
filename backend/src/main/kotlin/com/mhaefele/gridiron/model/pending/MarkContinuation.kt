package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.mark.OngoingMark
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

data class MarkContinuation(
    val ongoingMark: OngoingMark,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.MARK_CONTINUE

    override val delegate: ActionContinuation?
        get() = ongoingMark.nestedSidestep
            ?: ongoingMark.nestedBlock

    override val localRespondingCoach: TeamSide? get() = ongoingMark.respondingCoach

    override val localAwaitsCoachInput: Boolean get() = ongoingMark.stage.awaitsInput
    override fun isFinished(): Boolean =
        ongoingMark.stage == OngoingMark.MarkStage.DONE

    override val localPromptAbility: PlayerAbility? get() = ongoingMark.offeredAbility

    override val localPromptPlayerId: PlayerId?
        get() =
            // Only stages that park without a nest — DELEGATE_* use [delegate].
            when (ongoingMark.stage) {
                OngoingMark.MarkStage.HANDLE_SLIP_AWAY,
                OngoingMark.MarkStage.HANDLE_MARK_BONUS_CARDS,
                     -> null
                OngoingMark.MarkStage.CHOOSE_INTERRUPT_SIDESTEP    -> checkNotNull(ongoingMark.interruptSidestepPlayerId) { "CHOOSE_INTERRUPT_SIDESTEP missing interruptSidestepPlayerId" }
                OngoingMark.MarkStage.HANDLE_ABILITY_FOLLOW_UP     -> checkNotNull(ongoingMark.offeredAbilityPlayerId) { "HANDLE_ABILITY_FOLLOW_UP missing offeredAbilityPlayerId" }
                OngoingMark.MarkStage.CHOOSE_MARK_BONUS_FREE_BLOCK -> ongoingMark.playerId
                else                                               -> error("promptPlayerId unexpected Mark stage ${ongoingMark.stage}")
            }
}
