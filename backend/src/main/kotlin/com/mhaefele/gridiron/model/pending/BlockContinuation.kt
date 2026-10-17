package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.block.OngoingBlock
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Single pending for the whole Block process. [ongoingBlock.stage] selects legal moves and
 * which coach responds (Quick Release / Brace). Nest via [delegate] (KnockDown / ArmourCheck / free follow-up).
 */
data class BlockContinuation(
    val ongoingBlock: OngoingBlock,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.BLOCK_CONTINUE

    override val delegate: ActionContinuation?
        get() = ongoingBlock.nestedKnockDown
            ?: ongoingBlock.nestedArmourCheck
            ?: ongoingBlock.nestedRun
            ?: ongoingBlock.nestedBlock

    override val localRespondingCoach: TeamSide?
        get() = when (ongoingBlock.stage) {
            OngoingBlock.BlockStage.HANDLE_QUICK_RELEASE,
            OngoingBlock.BlockStage.HANDLE_BRACE,
                 -> ongoingBlock.targetTeam
            else -> null
        }

    override val localAwaitsCoachInput: Boolean get() = ongoingBlock.stage.awaitsInput
    override fun isFinished(): Boolean =
        ongoingBlock.stage == OngoingBlock.BlockStage.DONE

    override val localPromptAbility: PlayerAbility? get() = ongoingBlock.offeredAbility

    override val localPromptPlayerId: PlayerId?
        get() = when (ongoingBlock.stage) {
            OngoingBlock.BlockStage.HANDLE_QUICK_RELEASE -> ongoingBlock.offeredAbilityPlayerId ?: ongoingBlock.targetId
            OngoingBlock.BlockStage.HANDLE_SHOVE_FOLLOWUP,
            OngoingBlock.BlockStage.HANDLE_POST_POOL,
            OngoingBlock.BlockStage.HANDLE_ENFORCER_ORDER,
                 -> ongoingBlock.blockerId
            OngoingBlock.BlockStage.HANDLE_ABILITY_FOLLOW_UP -> checkNotNull(ongoingBlock.offeredAbilityPlayerId) { "HANDLE_ABILITY_FOLLOW_UP missing offeredAbilityPlayerId" }
            else                                             -> null
        }
}
