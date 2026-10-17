package com.mhaefele.gridiron.game.action.pending.knockdown

import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.model.pending.BallGuardContinuation
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId

/**
 * Snapshot of a standing knock-down in progress —
 * persisted only inside [com.mhaefele.gridiron.model.pending.KnockDownContinuation].
 *
 * APPLY_KNOCK_DOWN → (DELEGATE_BALL_GUARD?) → (DELEGATE_ARMOUR_CHECK?) → DONE.
 */
data class OngoingKnockDown(
    val playerId: PlayerId,
    /** Player's square when this knock-down began (Ball Guard drop). */
    val at: Coordinate,
    val stage: KnockDownStage,
    val withArmour: Boolean,
    /** −1 on a nested Armour Check (Flatten / successful Throw Team-mate). */
    val armourMinusOne: Boolean = false,
    val offerBladed: Boolean = false,
    val nestedBallGuard: BallGuardContinuation? = null,
    val nestedArmourCheck: ArmourCheckContinuation? = null,
) {
    val awaitsInput: Boolean
        get() = when (stage) {
            KnockDownStage.DELEGATE_BALL_GUARD   -> nestedBallGuard!!.awaitsCoachInput()
            KnockDownStage.DELEGATE_ARMOUR_CHECK -> nestedArmourCheck!!.awaitsCoachInput()
            else                                 -> stage.awaitsInput
        }

    enum class KnockDownStage {
        APPLY_KNOCK_DOWN,
        DELEGATE_BALL_GUARD,
        DELEGATE_ARMOUR_CHECK,
        DONE,
        ;

        val awaitsInput: Boolean get() = false
    }
}

class OngoingKnockDownMutator(
    val playerId: PlayerId,
    val at: Coordinate,
    var stage: OngoingKnockDown.KnockDownStage,
    val withArmour: Boolean,
    val armourMinusOne: Boolean = false,
    val offerBladed: Boolean = false,
    var nestedBallGuard: BallGuardContinuation? = null,
    var nestedArmourCheck: ArmourCheckContinuation? = null,
) {
    constructor(ongoing: OngoingKnockDown) : this(
        ongoing.playerId, ongoing.at, ongoing.stage, ongoing.withArmour, ongoing.armourMinusOne,
        ongoing.offerBladed, ongoing.nestedBallGuard, ongoing.nestedArmourCheck,
    )

    val awaitsInput: Boolean get() = ongoingKnockDown.awaitsInput

    val ongoingKnockDown: OngoingKnockDown
        get() = OngoingKnockDown(playerId, at, stage, withArmour, armourMinusOne, offerBladed, nestedBallGuard, nestedArmourCheck)
}
