package com.mhaefele.gridiron.game.action.pending.run

import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.pending.MarkContinuation
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

data class OngoingRun(
    val playerId: PlayerId,
    val stage: RunStage,
    val squaresRemaining: Int,
    val respondingCoach: TeamSide? = null,
    val nestedMark: MarkContinuation? = null,
    val nestedThrow: ThrowContinuation? = null,
    val interruptRefundCardId: ChallengeCardDefinition? = null,
    val interruptMarkPlayerId: PlayerId? = null,
    val offeredAbility: PlayerAbility? = null,
    val offeredAbilityPlayerId: PlayerId? = null,
    /** Granted or declined follow-ups — (ability, target player) so Rally can offer each ally once. */
    val resolvedFollowUpOffers: Set<Pair<PlayerAbility, PlayerId>> = emptySet(),
) {
    /** Coach must answer before the auto-crank continues. DELEGATE tracks nested Mark/Throw parks. */
    val awaitsInput: Boolean
        get() = when (stage) {
            RunStage.DELEGATE_INTERRUPT_MARK      -> nestedMark!!.awaitsCoachInput()
            RunStage.DELEGATE_ABILITY_FREE_ACTION -> nestedThrow!!.awaitsCoachInput()
            else                                  -> stage.awaitsInput
        }

    val isDone: Boolean get() = stage == RunStage.DONE

    enum class RunStage {
        AWAITING_PATH,
        HANDLE_SPRINT,
        OFFER_CUT_OFF,
        HANDLE_CUT_OFF,
        CHOOSE_INTERRUPT_MARK,
        DELEGATE_INTERRUPT_MARK,
        OFFER_ABILITY_FOLLOW_UPS,
        HANDLE_ABILITY_FOLLOW_UP,
        DELEGATE_ABILITY_FREE_ACTION,
        DONE,
        ;

        /** Coach must answer before the auto-crank continues. DELEGATE uses [OngoingRun.awaitsInput]. */
        val awaitsInput: Boolean
            get() = when (this) {
                AWAITING_PATH, HANDLE_SPRINT, HANDLE_CUT_OFF,
                CHOOSE_INTERRUPT_MARK, HANDLE_ABILITY_FOLLOW_UP,
                     -> true
                else -> false
            }
    }
}

class OngoingRunMutator(
    val playerId: PlayerId,
    var stage: OngoingRun.RunStage,
    var squaresRemaining: Int,
    var respondingCoach: TeamSide? = null,
    var nestedMark: MarkContinuation? = null,
    var nestedThrow: ThrowContinuation? = null,
    var interruptRefundCardId: ChallengeCardDefinition? = null,
    var interruptMarkPlayerId: PlayerId? = null,
    var offeredAbility: PlayerAbility? = null,
    var offeredAbilityPlayerId: PlayerId? = null,
    var resolvedFollowUpOffers: Set<Pair<PlayerAbility, PlayerId>> = emptySet(),
) {
    constructor(ongoing: OngoingRun) : this(
        ongoing.playerId, ongoing.stage, ongoing.squaresRemaining, ongoing.respondingCoach,
        ongoing.nestedMark, ongoing.nestedThrow, ongoing.interruptRefundCardId, ongoing.interruptMarkPlayerId,
        ongoing.offeredAbility, ongoing.offeredAbilityPlayerId, ongoing.resolvedFollowUpOffers,
    )

    val ongoingRun: OngoingRun
        get() = OngoingRun(
            playerId, stage, squaresRemaining, respondingCoach, nestedMark, nestedThrow,
            interruptRefundCardId, interruptMarkPlayerId, offeredAbility, offeredAbilityPlayerId,
            resolvedFollowUpOffers,
        )

    val awaitsInput: Boolean
        get() = ongoingRun.awaitsInput

    val isDone: Boolean get() = ongoingRun.isDone
}
