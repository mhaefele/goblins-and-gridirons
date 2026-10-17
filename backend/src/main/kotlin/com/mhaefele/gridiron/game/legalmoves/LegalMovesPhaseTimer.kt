package com.mhaefele.gridiron.game.legalmoves

/**
 * Optional phase timers for [LegalMovesGenerator.forActingCoach] profiling (AI submit nested buckets).
 * Defaults are no-ops so game / lookahead callers stay free of timer wiring.
 */
interface LegalMovesPhaseTimer {
    fun <T> enumMoves(block: () -> T): T = block()

    fun <T> ballAnnotate(block: () -> T): T = block()

    fun <T> claimsAnnotate(block: () -> T): T = block()

    companion object {
        val NOOP: LegalMovesPhaseTimer = object : LegalMovesPhaseTimer {}
    }
}
