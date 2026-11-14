package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.shared.ThrowRange
import kotlin.math.roundToInt

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

/** Effective throw check after optional Dead Eye / Hail Mary rewrites. Null = hand-off (no roll). */
private data class EffectiveThrowCheck(val threshold: ThrowStat.Threshold, val range: ThrowRange, val obstructed: Boolean)

private fun effectiveThrowCheck(
    throwStat: ThrowStat,
    range: ThrowRange,
    obstructed: Boolean,
    hailMary: Boolean,
    deadEye: Boolean,
): EffectiveThrowCheck? {
    if (!hailMary && !deadEye && range == ThrowRange.HAND_OFF) return null
    val effectiveStat = when {
        hailMary -> ThrowStat.Threshold(4)
        deadEye  -> ThrowStat.Threshold(2)
        else     -> throwStat
    }
    val effectiveRange = if (hailMary || deadEye) ThrowRange.SHORT else range
    val effectiveObs = if (hailMary || deadEye) false else obstructed
    require(effectiveRange != ThrowRange.HAND_OFF) { "Hand-off does not use throw check" }
    return when (effectiveStat) {
        is ThrowStat.Unthrowable -> error("Unthrowable players have no throw check")
        is ThrowStat.Threshold   -> EffectiveThrowCheck(effectiveStat, effectiveRange, effectiveObs)
    }
}

/**
 * Effective minimum natural D6 for a successful throw check — same `2..6` codes as cell `throwStat`
 * thresholds; `0` = hand-off (no check). Long / obstructed / Dead Eye / Hail Mary already baked in.
 */
fun throwCheckNeeded(
    throwStat: ThrowStat,
    range: ThrowRange,
    obstructed: Boolean,
    hailMary: Boolean = false,
    deadEye: Boolean = false,
): Int {
    val check = effectiveThrowCheck(throwStat, range, obstructed, hailMary, deadEye) ?: return 0
    return (check.threshold.minimumRoll - throwCheckModifier(check.range, check.obstructed)).coerceIn(2, 6)
}

/**
 * P(success) from [throwCheckNeeded] — `0` (hand-off) → `1.0`; `2..6` → `(7 − n) / 6`.
 * Use at pack / rank time; do not store a parallel float on [ThrowTarget].
 */
fun throwSuccessChanceFromNeeded(throwCheckNeeded: Int): Double {
    require(throwCheckNeeded == 0 || throwCheckNeeded in 2..6) { "throwCheckNeeded must be 0 or 2..6, was $throwCheckNeeded" }
    return if (throwCheckNeeded == 0) 1.0 else (7 - throwCheckNeeded) / 6.0
}

/** Percent `0..100` for the legal-row pack column — Python only `/100`. */
fun throwSuccessChancePercentFromNeeded(throwCheckNeeded: Int): Int =
    (throwSuccessChanceFromNeeded(throwCheckNeeded) * 100.0).roundToInt().coerceIn(0, 100)

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
