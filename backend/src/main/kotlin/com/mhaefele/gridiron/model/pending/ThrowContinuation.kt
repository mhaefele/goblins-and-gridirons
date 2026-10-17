package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.throwball.OngoingThrow
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.PlayerId

data class ThrowContinuation(
    val ongoingThrow: OngoingThrow,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.THROW_CONTINUE

    override val delegate: ActionContinuation?
        get() = ongoingThrow.nestedRun

    override val localAwaitsCoachInput: Boolean get() = ongoingThrow.stage.awaitsInput
    override fun isFinished(): Boolean =
        ongoingThrow.stage == OngoingThrow.ThrowStage.DONE

    override val localPromptAbility: PlayerAbility? get() = ongoingThrow.offeredAbility

    override val localPromptPlayerId: PlayerId?
        get() = when (ongoingThrow.stage) {
            // Only stages that park without a nest — DELEGATE_* use [delegate].
            OngoingThrow.ThrowStage.HANDLE_BONUS_CARD_OFFER,
            OngoingThrow.ThrowStage.APPLY_POST_ROLL_ABILITIES,
                 -> ongoingThrow.throwerId
            OngoingThrow.ThrowStage.HANDLE_ABILITY_FOLLOW_UP -> checkNotNull(ongoingThrow.offeredAbilityPlayerId) { "HANDLE_ABILITY_FOLLOW_UP missing offeredAbilityPlayerId" }
            else                                             -> error("promptPlayerId unexpected Throw stage ${ongoingThrow.stage}")
        }
}
