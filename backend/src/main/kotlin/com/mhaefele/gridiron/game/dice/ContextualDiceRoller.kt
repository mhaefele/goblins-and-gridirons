package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * Injected [DiceRoller]: [DiceRollerContext] overlay (lookahead), else [ScriptedDiceRoller]
 * when that profile bean exists, else [RandomDiceRoller]. Leaves do not know about overlays.
 */
@Component
@Primary
class ContextualDiceRoller(
    private val diceRollerContext: DiceRollerContext,
    private val random: RandomDiceRoller,
    private val scripted: ScriptedDiceRoller? = null,
) : DiceRoller {
    private fun active(): DiceRoller =
        diceRollerContext.delegate(this) ?: scripted ?: random

    override fun d6(): Int = active().d6()

    override fun d8(): Int = active().d8()

    override fun blockDice(count: Int): List<BlockDieFace> = active().blockDice(count)

    override fun <T> shuffle(items: List<T>): List<T> = active().shuffle(items)
}
