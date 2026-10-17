package com.mhaefele.gridiron.game.action.pending.mark

import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.pending.SidestepContinuation
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

data class OngoingMark(
    val playerId: PlayerId,
    val stage: MarkStage,
    val respondingCoach: TeamSide? = null,
    val nestedSidestep: SidestepContinuation? = null,
    val nestedBlock: BlockContinuation? = null,
    val offeredAbility: PlayerAbility? = null,
    val offeredAbilityPlayerId: PlayerId? = null,
    /** Momentum or All-In Tackle while choosing / running the free Block. */
    val markBonusCard: BonusPlay? = null,
    val interruptRefundCardId: ChallengeCardDefinition? = null,
    val interruptSidestepPlayerId: PlayerId? = null,
    /** Granted or declined follow-ups — (ability, target) so Rally can offer each ally once. */
    val resolvedFollowUpOffers: Set<Pair<PlayerAbility, PlayerId>> = emptySet(),
) {
    /** Coach must answer before the auto-crank continues. DELEGATE tracks nested child's park. */
    val awaitsInput: Boolean
        get() = when (stage) {
            MarkStage.DELEGATE_INTERRUPT_SIDESTEP    -> nestedSidestep!!.awaitsCoachInput()
            MarkStage.DELEGATE_ABILITY_FREE_ACTION   -> nestedBlock!!.awaitsCoachInput()
            MarkStage.DELEGATE_MARK_BONUS_FREE_BLOCK -> nestedBlock!!.awaitsCoachInput()
            else                                     -> stage.awaitsInput
        }

    val isDone: Boolean get() = stage == MarkStage.DONE

    enum class MarkStage {
        OFFER_SLIP_AWAY,
        HANDLE_SLIP_AWAY,
        CHOOSE_INTERRUPT_SIDESTEP,
        DELEGATE_INTERRUPT_SIDESTEP,
        /** Player abilities (Relentless After Mark, Charge, Rally, …). */
        OFFER_ABILITY_FOLLOW_UPS,
        HANDLE_ABILITY_FOLLOW_UP,
        DELEGATE_ABILITY_FREE_ACTION,
        /** Bonus cards Momentum / All-In Tackle after this Mark — not player abilities. */
        OFFER_MARK_BONUS_CARDS,
        HANDLE_MARK_BONUS_CARDS,
        CHOOSE_MARK_BONUS_FREE_BLOCK,
        DELEGATE_MARK_BONUS_FREE_BLOCK,
        DONE,
        ;

        /** Coach must answer before the auto-crank continues. DELEGATE uses [OngoingMark.awaitsInput]. */
        val awaitsInput: Boolean
            get() = when (this) {
                HANDLE_SLIP_AWAY, CHOOSE_INTERRUPT_SIDESTEP,
                HANDLE_ABILITY_FOLLOW_UP,
                HANDLE_MARK_BONUS_CARDS, CHOOSE_MARK_BONUS_FREE_BLOCK,
                     -> true
                else -> false
            }
    }
}

class OngoingMarkMutator(
    val playerId: PlayerId,
    var stage: OngoingMark.MarkStage,
    var respondingCoach: TeamSide? = null,
    var nestedSidestep: SidestepContinuation? = null,
    var nestedBlock: BlockContinuation? = null,
    var offeredAbility: PlayerAbility? = null,
    var offeredAbilityPlayerId: PlayerId? = null,
    var markBonusCard: BonusPlay? = null,
    var interruptRefundCardId: ChallengeCardDefinition? = null,
    var interruptSidestepPlayerId: PlayerId? = null,
    var resolvedFollowUpOffers: Set<Pair<PlayerAbility, PlayerId>> = emptySet(),
) {
    constructor(ongoing: OngoingMark) : this(
        ongoing.playerId, ongoing.stage, ongoing.respondingCoach,
        ongoing.nestedSidestep, ongoing.nestedBlock,
        ongoing.offeredAbility, ongoing.offeredAbilityPlayerId, ongoing.markBonusCard,
        ongoing.interruptRefundCardId, ongoing.interruptSidestepPlayerId, ongoing.resolvedFollowUpOffers,
    )

    val ongoingMark: OngoingMark
        get() = OngoingMark(
            playerId, stage, respondingCoach, nestedSidestep, nestedBlock,
            offeredAbility, offeredAbilityPlayerId, markBonusCard, interruptRefundCardId, interruptSidestepPlayerId,
            resolvedFollowUpOffers,
        )

    val awaitsInput: Boolean
        get() = ongoingMark.awaitsInput

    val isDone: Boolean get() = ongoingMark.isDone
}
