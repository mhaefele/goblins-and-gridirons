package com.mhaefele.gridiron.game.action.pending.throwteammate

import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.model.pending.KnockDownContinuation
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId

/**
 * Snapshot across the select → landing pause (and nested KnockDown / ArmourCheck) —
 * persisted only inside [ThrowTeammateContinuation].
 *
 * Throw facts ([destination], [throwSucceeded]) are set when the Throw Check is rolled;
 * later stages read those facts — they do not re-roll or re-branch the check.
 */
data class OngoingThrowTeammate(
    val throwerId: PlayerId,
    val teammateId: PlayerId,
    val stage: ThrowTeammateStage,
    /** True when coach cancelled before landing — parent RPA clears delegate; not COMPLETE. */
    val cancelled: Boolean = false,
    /** Nested knock-down; non-null at DELEGATE_KNOCK_DOWN_THROWN / DELEGATE_KNOCK_DOWN_OCCUPANT. */
    val nestedKnockDown: KnockDownContinuation? = null,
    /** Nested armour check; non-null at DELEGATE_ARMOUR_CHECK (already-prone occupant). */
    val nestedArmourCheck: ArmourCheckContinuation? = null,
    /** Landing square — set when the Throw Check is rolled. */
    val destination: Coordinate? = null,
    /** Throw Check passed — set with [destination]; null until rolled (Fumble never parks). */
    val throwSucceeded: Boolean? = null,
) {
    /**
     * Empty success: APPLY_EMPTY_LANDING → DONE.
     * Empty fail: APPLY_EMPTY_LANDING → KNOCK_DOWN_THROWN → (DELEGATE_KNOCK_DOWN_THROWN?) → DONE.
     * Occupied standing: KNOCK_DOWN_OCCUPANT → (DELEGATE_KNOCK_DOWN_OCCUPANT?) → FINISH_OCCUPIED → DONE.
     * Occupied already prone: KNOCK_DOWN_OCCUPANT → (DELEGATE_ARMOUR_CHECK?) → FINISH_OCCUPIED → DONE.
     * Fumble: resolved in handle → DONE (no mid stages).
     */
    enum class ThrowTeammateStage {
        AWAITING_LANDING,
        APPLY_EMPTY_LANDING,
        KNOCK_DOWN_THROWN,
        DELEGATE_KNOCK_DOWN_THROWN,
        KNOCK_DOWN_OCCUPANT,
        DELEGATE_KNOCK_DOWN_OCCUPANT,
        DELEGATE_ARMOUR_CHECK,
        /** After KnockDown / ArmourCheck on occupant: bounce thrown's ball if any and injure thrown. */
        FINISH_OCCUPIED,
        DONE,
    }

    val awaitsInput: Boolean
        get() = when (stage) {
            ThrowTeammateStage.AWAITING_LANDING              -> true
            ThrowTeammateStage.DELEGATE_KNOCK_DOWN_THROWN,
            ThrowTeammateStage.DELEGATE_KNOCK_DOWN_OCCUPANT -> nestedKnockDown!!.awaitsCoachInput()
            ThrowTeammateStage.DELEGATE_ARMOUR_CHECK        -> nestedArmourCheck!!.awaitsCoachInput()
            else                                            -> false
        }
}

class OngoingThrowTeammateMutator(
    var throwerId: PlayerId,
    var teammateId: PlayerId,
    var stage: OngoingThrowTeammate.ThrowTeammateStage,
    var cancelled: Boolean = false,
    var nestedKnockDown: KnockDownContinuation? = null,
    var nestedArmourCheck: ArmourCheckContinuation? = null,
    var destination: Coordinate? = null,
    var throwSucceeded: Boolean? = null,
) {
    constructor(ongoing: OngoingThrowTeammate) : this(
        ongoing.throwerId,
        ongoing.teammateId,
        ongoing.stage,
        ongoing.cancelled,
        ongoing.nestedKnockDown,
        ongoing.nestedArmourCheck,
        ongoing.destination,
        ongoing.throwSucceeded,
    )

    val awaitsInput: Boolean get() = ongoingThrowTeammate.awaitsInput

    val ongoingThrowTeammate: OngoingThrowTeammate
        get() = OngoingThrowTeammate(
            throwerId = throwerId,
            teammateId = teammateId,
            stage = stage,
            cancelled = cancelled,
            nestedKnockDown = nestedKnockDown,
            nestedArmourCheck = nestedArmourCheck,
            destination = destination,
            throwSucceeded = throwSucceeded,
        )
}
