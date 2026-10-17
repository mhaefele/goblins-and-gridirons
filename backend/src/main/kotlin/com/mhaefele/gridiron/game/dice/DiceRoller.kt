package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace

/** RNG port for action resolution — production uses secure random; tests use a scripted queue. */
interface DiceRoller {
    fun d6(): Int

    fun d8(): Int

    fun blockDice(count: Int): List<BlockDieFace>

    /** Fisher–Yates shuffle for match setup (challenge deck). */
    fun <T> shuffle(items: List<T>): List<T>
}
