package com.mhaefele.gridiron.game.dice

import org.springframework.stereotype.Component

/**
 * Thread-local [DiceRoller] overlay for in-process dry-runs (lookahead) that must not consume
 * the live match roller. Bind for the block, then put back whatever this thread was using —
 * including a previous overlay or none.
 *
 * [ContextualDiceRoller] is the only consumer. The bound instance does not re-enter
 * ([delegate] is null when [self] is the overlay).
 */
@Component
class DiceRollerContext {
    private val current = ThreadLocal<DiceRoller>()

    fun <T> withRoller(roller: DiceRoller, block: () -> T): T {
        val previous = current.get()
        current.set(roller)
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    /**
     * Overlay for [self] to forward to. Null when unbound, or when [self] *is* the overlay
     * (the leaf must serve its own queues / RNG).
     */
    fun delegate(self: DiceRoller): DiceRoller? {
        val overlay = current.get()
        return if (overlay != null && overlay !== self) overlay else null
    }
}
