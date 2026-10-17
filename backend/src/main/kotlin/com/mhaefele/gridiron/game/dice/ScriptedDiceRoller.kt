package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.shared.Direction
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Scripted [DiceRoller] for Spring profiles `test` and `scripted-dice`.
 *
 * Queued values are consumed per die type. When a type's queue is empty:
 * - [fallbackToDefaultPattern] `false` (unit/API tests) — throw
 * - [fallbackToDefaultPattern] `true` (default / e2e) — cycle the default pattern
 *
 * [usePassThrough] delegates to [RandomDiceRoller] until [reset] (living playthroughs).
 * Lookahead overlay lives on [ContextualDiceRoller].
 */
@Component
@Profile("test", "scripted-dice")
class ScriptedDiceRoller @Autowired constructor(
    private val random: RandomDiceRoller,
    @Value("\${gridiron.dice.fallback-to-default-pattern:true}")
    private val fallbackToDefaultPattern: Boolean,
) : DiceRoller {

    constructor(fallbackToDefaultPattern: Boolean = true) : this(RandomDiceRoller(), fallbackToDefaultPattern)

    private val d6Queue = ArrayDeque<Int>()
    private val d8Queue = ArrayDeque<Int>()
    private val blockQueue = ArrayDeque<BlockDieFace>()

    private var d6Index = 0
    private var d8Index = 0
    private var blockIndex = 0
    private var passThrough = false

    fun reset() {
        synchronized(this) {
            d6Queue.clear()
            d8Queue.clear()
            blockQueue.clear()
            d6Index = 0
            d8Index = 0
            blockIndex = 0
            passThrough = false
        }
    }

    /** Ignore queues and delegate all rolls to [random] until [reset]. */
    fun usePassThrough() {
        synchronized(this) {
            d6Queue.clear()
            d8Queue.clear()
            blockQueue.clear()
            passThrough = true
        }
    }

    fun queueD6(vararg values: Int) = queueD6(values.toList())

    fun queueD6(values: List<Int>): ScriptedDiceRoller {
        require(values.isNotEmpty()) { "D6 queue must include at least one value" }
        values.forEach { value ->
            require(value in 1..6) { "D6 queue value must be 1–6, got $value" }
        }
        synchronized(this) { d6Queue.addAll(values) }
        return this
    }

    fun queueD8(vararg values: Int) = queueD8(values.toList())

    fun queueD8(values: List<Int>): ScriptedDiceRoller {
        require(values.isNotEmpty()) { "D8 queue must include at least one value" }
        values.forEach { value ->
            require(value in 1..8) { "D8 queue value must be 1–8, got $value" }
        }
        synchronized(this) { d8Queue.addAll(values) }
        return this
    }

    fun queueBounce(vararg directions: Direction) = queueD8(directions.map { it.d8Value })

    fun queueBlockDice(vararg faces: BlockDieFace) = queueBlockDice(faces.toList())

    fun queueBlockDice(faces: List<BlockDieFace>): ScriptedDiceRoller {
        require(faces.isNotEmpty()) { "Block queue must include at least one face" }
        synchronized(this) { blockQueue.addAll(faces) }
        return this
    }

    override fun d6(): Int {
        return synchronized(this) {
            if (passThrough) return@synchronized random.d6()
            if (d6Queue.isNotEmpty()) return@synchronized d6Queue.removeFirst()
            if (fallbackToDefaultPattern) return@synchronized DEFAULT_D6[d6Index++ % DEFAULT_D6.size]
            error("ScriptedDiceRoller D6 queue exhausted")
        }
    }

    override fun d8(): Int {
        return synchronized(this) {
            if (passThrough) return@synchronized random.d8()
            if (d8Queue.isNotEmpty()) return@synchronized d8Queue.removeFirst()
            if (fallbackToDefaultPattern) return@synchronized DEFAULT_D8[d8Index++ % DEFAULT_D8.size]
            error("ScriptedDiceRoller D8 queue exhausted")
        }
    }

    override fun blockDice(count: Int): List<BlockDieFace> {
        require(count > 0) { "Block dice count must be positive" }
        return synchronized(this) {
            if (passThrough) return@synchronized random.blockDice(count)
            List(count) {
                if (blockQueue.isNotEmpty()) blockQueue.removeFirst()
                else if (fallbackToDefaultPattern) DEFAULT_BLOCK[blockIndex++ % DEFAULT_BLOCK.size]
                else error("ScriptedDiceRoller block queue exhausted")
            }
        }
    }

    /**
     * Identity order unless [usePassThrough] — keeps createMatch deterministic without queuing
     * shuffle entropy (catalog deal order).
     */
    override fun <T> shuffle(items: List<T>): List<T> {
        return synchronized(this) { if(passThrough) random.shuffle(items) else items }
    }

    companion object {
        val DEFAULT_D6: List<Int> = listOf(1, 2, 3, 4, 5, 6)
        val DEFAULT_D8: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        val DEFAULT_BLOCK: List<BlockDieFace> = BlockDieFace.entries
    }
}
