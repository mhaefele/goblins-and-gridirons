package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.shared.ThrowRange

/** Cap total modifier per digest § Checks — multiple sources still combine to at most ±1. */
fun clampModifier(modifier: Int): Int = modifier.coerceIn(-1, 1)

/**
 * D6 check against threshold (Throw Value / Armour Value).
 * Natural 1 always fails; natural 6 always succeeds — before modifiers apply.
 */
fun passesCheck(naturalRoll: Int, threshold: Int, modifier: Int = 0): Boolean {
    require(naturalRoll in 1..6) { "D6 roll must be 1–6, got $naturalRoll" }
    require(threshold in 2..6) { "Threshold must be 2–6, got $threshold" }
    if (naturalRoll == 1) return false
    if (naturalRoll == 6) return true
    return naturalRoll + clampModifier(modifier) >= threshold
}

sealed interface ThrowCheckOutcome {
    data object Unthrowable : ThrowCheckOutcome

    /** Result is 1 before or after modifiers — ball fumbles from thrower (digest § Throw). */
    data object Fumble : ThrowCheckOutcome

    data object Success : ThrowCheckOutcome

    data class Fail(val roll: Int, val threshold: Int) : ThrowCheckOutcome
}

fun throwCheckModifier(range: ThrowRange, obstructed: Boolean): Int {
    require(range != ThrowRange.HAND_OFF) { "Hand-off does not use throw check modifiers" }
    var modifier = 0
    if (range == ThrowRange.LONG) modifier -= 1
    if (obstructed) modifier -= 1
    return clampModifier(modifier)
}

fun throwCheck(
    naturalRoll: Int,
    throwStat: ThrowStat,
    range: ThrowRange,
    obstructed: Boolean,
): ThrowCheckOutcome {
    when (throwStat) {
        is ThrowStat.Unthrowable -> return ThrowCheckOutcome.Unthrowable
        is ThrowStat.Threshold   -> {
            require(range != ThrowRange.HAND_OFF) { "Hand-off does not use throw check" }
            require(naturalRoll in 1..6) { "D6 roll must be 1–6, got $naturalRoll" }
            if (naturalRoll == 1) return ThrowCheckOutcome.Fumble
            val modifier = throwCheckModifier(range, obstructed)
            if (naturalRoll + modifier == 1) return ThrowCheckOutcome.Fumble
            val threshold = throwStat.minimumRoll
            return if (passesCheck(naturalRoll, threshold, modifier)) {
                ThrowCheckOutcome.Success
            } else {
                ThrowCheckOutcome.Fail(naturalRoll, threshold)
            }
        }
    }
}

sealed interface ArmourCheckOutcome {
    /** `-` armour — auto-fail without rolling. */
    data object NoArmour : ArmourCheckOutcome

    data object Success : ArmourCheckOutcome

    data class Fail(val roll: Int, val threshold: Int) : ArmourCheckOutcome
}

fun armourCheck(
    naturalRoll: Int,
    armour: ArmourStat,
    flatten: Boolean = false,
): ArmourCheckOutcome {
    when (armour) {
        is ArmourStat.NoArmour  -> return ArmourCheckOutcome.NoArmour
        is ArmourStat.Threshold -> {
            val threshold = armour.minimumRoll
            val modifier = if (flatten) -1 else 0
            return if (passesCheck(naturalRoll, threshold, modifier)) {
                ArmourCheckOutcome.Success
            } else {
                ArmourCheckOutcome.Fail(naturalRoll, threshold)
            }
        }
    }
}
