package com.mhaefele.gridiron.game.action.pending.armourcheck

import com.mhaefele.gridiron.shared.PlayerId

/**
 * Snapshot of an Armour Check (optional Bladed / Do-Over) in progress —
 * persisted only inside [com.mhaefele.gridiron.model.pending.ArmourCheckContinuation].
 *
 * OFFER_BLADED → HANDLE_BLADED? → ROLL_ARMOUR → HANDLE_ARMOUR_DO_OVER? → DONE.
 */
data class OngoingArmourCheck(
    val playerId: PlayerId,
    val stage: ArmourCheckStage,
    /** −1 on the Armour Check (Flatten / successful Throw Team-mate). */
    val armourMinusOne: Boolean = false,
    val offerBladed: Boolean = false,
    val spikedGauntlets: Boolean = false,
    /** Armour D6 while held for Do-Over; null before the armour roll. */
    val naturalRoll: Int? = null,
) {
    val awaitsInput: Boolean get() = stage.awaitsInput

    enum class ArmourCheckStage {
        OFFER_BLADED,
        HANDLE_BLADED,
        ROLL_ARMOUR,
        HANDLE_ARMOUR_DO_OVER,
        DONE,
        ;

        val awaitsInput: Boolean
            get() = when (this) {
                HANDLE_BLADED, HANDLE_ARMOUR_DO_OVER -> true
                else                                    -> false
            }
    }
}

class OngoingArmourCheckMutator(
    val playerId: PlayerId,
    var stage: OngoingArmourCheck.ArmourCheckStage,
    val armourMinusOne: Boolean = false,
    val offerBladed: Boolean = false,
    var spikedGauntlets: Boolean = false,
    var naturalRoll: Int? = null,
) {
    constructor(ongoing: OngoingArmourCheck) : this(
        ongoing.playerId, ongoing.stage, ongoing.armourMinusOne,
        ongoing.offerBladed, ongoing.spikedGauntlets, ongoing.naturalRoll,
    )

    val awaitsInput: Boolean get() = ongoingArmourCheck.awaitsInput

    val ongoingArmourCheck: OngoingArmourCheck
        get() = OngoingArmourCheck(playerId, stage, armourMinusOne, offerBladed, spikedGauntlets, naturalRoll)
}
