package com.mhaefele.gridiron.game.action.pending.throwball

import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.game.dice.ThrowCheckOutcome
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.pending.RunContinuation
import com.mhaefele.gridiron.shared.PlayerId

data class OngoingThrow(
    val throwerId: PlayerId,
    val target: ThrowTarget,
    val stage: ThrowStage,
    val hailMary: Boolean = false,
    val deadEye: Boolean = false,
    // Null if the throw hasn't been rolled yet.  certain abilities may change this result after the roll or reroll
    val outcome: ThrowCheckOutcome? = null,
    val naturalRoll: Int? = null,
    val pinpointPasserUsed: Boolean = false,
    /** Declined Do-Over for this throw's check — survives HTTP via the continuation. */
    val doOverThrowDeclined: Boolean = false,
    val offeredAbility: PlayerAbility? = null,
    val offeredAbilityPlayerId: PlayerId? = null,
    val nestedRun: RunContinuation? = null,
    val resolvedFollowUpOffers: Set<Pair<PlayerAbility, PlayerId>> = emptySet(),
) {
    /** Coach must answer before the auto-crank continues. DELEGATE tracks nested Run's park. */
    val awaitsInput: Boolean
        get() = when (stage) {
            ThrowStage.DELEGATE_ABILITY_FREE_ACTION -> nestedRun!!.awaitsCoachInput()
            else                                    -> stage.awaitsInput
        }

    val isDone: Boolean get() = stage == ThrowStage.DONE

    enum class ThrowStage {
        INITIALIZE,
        OFFER_BONUS_CARDS,
        HANDLE_BONUS_CARD_OFFER,
        INITIAL_DICE_ROLL,
        QUEUE_POST_ROLL_ABILITIES,
        APPLY_POST_ROLL_ABILITIES,
        APPLY_FINAL_RESULT,
        OFFER_ABILITY_FOLLOW_UPS,
        HANDLE_ABILITY_FOLLOW_UP,
        DELEGATE_ABILITY_FREE_ACTION,
        DONE,
        ;

        val awaitsInput: Boolean
            get() = when (this) {
                HANDLE_BONUS_CARD_OFFER, APPLY_POST_ROLL_ABILITIES, HANDLE_ABILITY_FOLLOW_UP -> true
                else                                                                         -> false
            }
    }
}

class OngoingThrowMutator(
    var ongoingThrow: OngoingThrow
) {
    val throwerId: PlayerId get() = ongoingThrow.throwerId
    var target: ThrowTarget
        set(value) { ongoingThrow = ongoingThrow.copy(target = value) }
        get() = ongoingThrow.target

    var stage: OngoingThrow.ThrowStage
        set(value) { ongoingThrow = ongoingThrow.copy(stage = value) }
        get() = ongoingThrow.stage

    var hailMary: Boolean
        set(value) { ongoingThrow = ongoingThrow.copy(hailMary = value) }
        get() = ongoingThrow.hailMary

    var deadEye: Boolean
        set(value) { ongoingThrow = ongoingThrow.copy(deadEye = value) }
        get() = ongoingThrow.deadEye

    var naturalRoll: Int
        set(value) { ongoingThrow = ongoingThrow.copy(naturalRoll = value) }
        get() = ongoingThrow.naturalRoll!!

    var outcome: ThrowCheckOutcome
        set(value) { ongoingThrow = ongoingThrow.copy(outcome = value) }
        get() = ongoingThrow.outcome!!

    var pinpointPasserUsed: Boolean
        set(value) { ongoingThrow = ongoingThrow.copy(pinpointPasserUsed = value) }
        get() = ongoingThrow.pinpointPasserUsed

    var doOverThrowDeclined: Boolean
        set(value) { ongoingThrow = ongoingThrow.copy(doOverThrowDeclined = value) }
        get() = ongoingThrow.doOverThrowDeclined

    var offeredAbility: PlayerAbility?
        set(value) { ongoingThrow = ongoingThrow.copy(offeredAbility = value) }
        get() = ongoingThrow.offeredAbility

    var offeredAbilityPlayerId: PlayerId?
        set(value) { ongoingThrow = ongoingThrow.copy(offeredAbilityPlayerId = value) }
        get() = ongoingThrow.offeredAbilityPlayerId

    var nestedRun: RunContinuation?
        set(value) { ongoingThrow = ongoingThrow.copy(nestedRun = value) }
        get() = ongoingThrow.nestedRun

    var resolvedFollowUpOffers: Set<Pair<PlayerAbility, PlayerId>>
        set(value) { ongoingThrow = ongoingThrow.copy(resolvedFollowUpOffers = value) }
        get() = ongoingThrow.resolvedFollowUpOffers

    val awaitsInput: Boolean get() = ongoingThrow.awaitsInput

    val isDone: Boolean get() = ongoingThrow.isDone
}
