package com.mhaefele.gridiron.api.ai

import com.mhaefele.gridiron.game.legalmoves.LegalMovesPhaseTimer
import com.mhaefele.gridiron.shared.MatchId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Rolling batch stats for harness policy submits — next-legals / engine / turn-wait /
 * pack-dto / pack-flat split plus lookahead search count and nanos attributed to the
 * opponent wait window.
 *
 * A **handoff** is one policy submit that blocked in [AiTurnWait]
 * (control left the harness seat until the opponent finished — includes interrupts, not game turns).
 *
 * Logs one INFO line every [LOG_INTERVAL] submits; nested timings are subsets of the
 * parent bucket in parentheses (`next_legals_ms` → `claims_ms`, `turnWait_ms` → `lookahead_ms`, etc.).
 */
@Component
class AiSubmitTiming {
    private val batchSubmits = LongAdder()
    private val batchNextLegalsNs = LongAdder()
    private val batchNextLegalsEnumNs = LongAdder()
    private val batchNextLegalsBallNs = LongAdder()
    private val batchNextLegalsClaimsNs = LongAdder()
    private val batchEngineNs = LongAdder()
    private val batchTurnWaitNs = LongAdder()
    private val batchPackDtoNs = LongAdder()
    private val batchPackFlatNs = LongAdder()
    private val batchLookaheadNs = LongAdder()
    private val batchLookaheadMoves = LongAdder()
    private val batchSoftmaskSearchNs = LongAdder()
    private val batchSoftmaskSearchCalls = LongAdder()
    private val batchHandoffs = LongAdder()
    private val batchHandoffTurnWaitNs = LongAdder()

    private data class MatchLookahead(val moves: LongAdder = LongAdder(), val nanos: LongAdder = LongAdder())

    private data class MatchSoftmask(val calls: LongAdder = LongAdder(), val nanos: LongAdder = LongAdder())

    private val matchLookahead = ConcurrentHashMap<MatchId, MatchLookahead>()
    private val matchSoftmask = ConcurrentHashMap<MatchId, MatchSoftmask>()

    /** Per-submit accumulator — call [submit] once at the end of a harness policy submit. */
    inner class Run internal constructor(private val matchId: MatchId) {
        private var nextLegalsNs = 0L
        private var nextLegalsEnumNs = 0L
        private var nextLegalsBallNs = 0L
        private var nextLegalsClaimsNs = 0L
        private var engineNs = 0L
        private var turnWaitNs = 0L
        private var packDtoNs = 0L
        private var packFlatNs = 0L

        /**
         * [com.mhaefele.gridiron.api.MatchGame.legalActions] for the next harness step.
         * Pass [LegalMovesPhaseTimer] into the pipeline for nested enum / ball / claims buckets.
         */
        fun <T> nextLegals(block: (LegalMovesPhaseTimer) -> T): T {
            val timer = object : LegalMovesPhaseTimer {
                override fun <R> enumMoves(block: ()      -> R): R = timed(block) { nextLegalsEnumNs = it }
                override fun <R> ballAnnotate(block: ()   -> R): R = timed(block) { nextLegalsBallNs = it }
                override fun <R> claimsAnnotate(block: () -> R): R = timed(block) { nextLegalsClaimsNs = it }
            }
            return timed({ block(timer) }) { nextLegalsNs = it }
        }

        fun <T> engine(block: () -> T): T = timed(block) { engineNs = it }

        fun <T> turnWait(block: () -> T): T = timed(block) { turnWaitNs = it }

        fun <T> packDto(block: () -> T): T = timed(block) { packDtoNs = it }

        fun <T> packFlat(block: () -> T): T = timed(block) { packFlatNs = it }

        fun submit(handedOff: Boolean) {
            val (lookaheadMoves, lookaheadNs) = drainMatchLookahead(matchId)
            val (softmaskCalls, softmaskNs) = drainMatchSoftmask(matchId)
            recordSubmit(
                nextLegalsNs, nextLegalsEnumNs, nextLegalsBallNs, nextLegalsClaimsNs,
                engineNs, turnWaitNs, packDtoNs, packFlatNs, lookaheadNs, lookaheadMoves,
                softmaskNs, softmaskCalls, handedOff,
            )
        }
    }

    /** Reset per-match lookahead and return a per-submit timing accumulator. */
    fun startTiming(matchId: MatchId): Run {
        resetMatchLookahead(matchId)
        return Run(matchId)
    }

    /** Clear per-match lookahead accumulators at the start of each policy submit. */
    private fun resetMatchLookahead(matchId: MatchId) {
        matchLookahead[matchId] = MatchLookahead()
    }

    /** One [com.mhaefele.gridiron.game.opponent.lookahead.LookaheadSearch.evaluate] on the computer thread. */
    fun recordLookaheadSearch(matchId: MatchId, nanos: Long) {
        val acc = matchLookahead[matchId] ?: return
        acc.moves.increment()
        acc.nanos.add(nanos)
    }

    /** One multi-step training soft-mask solvability search (pack / step legal rows). */
    fun recordSoftmaskSearch(matchId: MatchId, nanos: Long) {
        val acc = matchSoftmask.getOrPut(matchId) { MatchSoftmask() }
        acc.calls.increment()
        acc.nanos.add(nanos)
    }

    private fun drainMatchLookahead(matchId: MatchId): Pair<Long, Long> {
        val acc = matchLookahead.remove(matchId) ?: return 0L to 0L
        return acc.moves.sum() to acc.nanos.sum()
    }

    private fun drainMatchSoftmask(matchId: MatchId): Pair<Long, Long> {
        val acc = matchSoftmask.remove(matchId) ?: return 0L to 0L
        return acc.calls.sum() to acc.nanos.sum()
    }

    private fun recordSubmit(
        nextLegalsNs: Long,
        nextLegalsEnumNs: Long,
        nextLegalsBallNs: Long,
        nextLegalsClaimsNs: Long,
        engineNs: Long,
        turnWaitNs: Long,
        packDtoNs: Long,
        packFlatNs: Long,
        lookaheadNs: Long,
        lookaheadMoves: Long,
        softmaskNs: Long,
        softmaskCalls: Long,
        handedOff: Boolean,
    ) {
        batchNextLegalsNs.add(nextLegalsNs)
        batchNextLegalsEnumNs.add(nextLegalsEnumNs)
        batchNextLegalsBallNs.add(nextLegalsBallNs)
        batchNextLegalsClaimsNs.add(nextLegalsClaimsNs)
        batchEngineNs.add(engineNs)
        batchTurnWaitNs.add(turnWaitNs)
        batchPackDtoNs.add(packDtoNs)
        batchPackFlatNs.add(packFlatNs)
        batchLookaheadNs.add(lookaheadNs)
        batchLookaheadMoves.add(lookaheadMoves)
        batchSoftmaskSearchNs.add(softmaskNs)
        batchSoftmaskSearchCalls.add(softmaskCalls)
        if (handedOff) {
            batchHandoffs.increment()
            batchHandoffTurnWaitNs.add(turnWaitNs)
        }
        batchSubmits.increment()
        val n = batchSubmits.sum()
        if (n % LOG_INTERVAL == 0L) {
            val nextLegalsMs = avgMs(batchNextLegalsNs, n)
            val nextLegalsEnumMs = avgMs(batchNextLegalsEnumNs, n)
            val nextLegalsBallMs = avgMs(batchNextLegalsBallNs, n)
            val nextLegalsClaimsMs = avgMs(batchNextLegalsClaimsNs, n)
            val engineMs = avgMs(batchEngineNs, n)
            val turnWaitMs = avgMs(batchTurnWaitNs, n)
            val packDtoMs = avgMs(batchPackDtoNs, n)
            val packFlatMs = avgMs(batchPackFlatNs, n)
            val lookaheadMs = avgMs(batchLookaheadNs, n)
            val softmaskMs = avgMs(batchSoftmaskSearchNs, n)
            val totalMs = nextLegalsMs + engineMs + turnWaitMs + packDtoMs + packFlatMs
            val handoffs = batchHandoffs.sum()
            val handoffOnlyMs = if (handoffs == 0L) 0.0 else batchHandoffTurnWaitNs.sum().toDouble() / handoffs / 1_000_000.0
            val nextLegalsChildren = "enum_ms=${fmtMs(nextLegalsEnumMs)} ball_ms=${fmtMs(nextLegalsBallMs)} claims_ms=${fmtMs(nextLegalsClaimsMs)}"
            val packDtoChildren = "softmask_search_ms=${fmtMs(softmaskMs)}"
            val breakdown = listOf(fmtBucket("next_legals_ms", nextLegalsMs, nextLegalsChildren), fmtBucket("engine_ms", engineMs), fmtBucket("turnWait_ms", turnWaitMs, "handoff_only_ms=${fmtMs(handoffOnlyMs)} lookahead_ms=${fmtMs(lookaheadMs)}"), fmtBucket("pack_dto_ms", packDtoMs, packDtoChildren), fmtBucket("pack_flat_ms", packFlatMs)).joinToString(" ")
            log.info(
                "ai-submit-timing: n={} handoffs={} total_ms={} ({}) lookahead_moves_per_submit={} softmask_searches_per_submit={}",
                n,
                handoffs,
                fmtMs(totalMs),
                breakdown,
                batchLookaheadMoves.sum().toDouble() / n,
                batchSoftmaskSearchCalls.sum().toDouble() / n,
            )
            resetBatch()
        }
    }

    private fun resetBatch() {
        batchSubmits.reset()
        batchNextLegalsNs.reset()
        batchNextLegalsEnumNs.reset()
        batchNextLegalsBallNs.reset()
        batchNextLegalsClaimsNs.reset()
        batchEngineNs.reset()
        batchTurnWaitNs.reset()
        batchPackDtoNs.reset()
        batchPackFlatNs.reset()
        batchLookaheadNs.reset()
        batchLookaheadMoves.reset()
        batchSoftmaskSearchNs.reset()
        batchSoftmaskSearchCalls.reset()
        batchHandoffs.reset()
        batchHandoffTurnWaitNs.reset()
    }

    private inline fun <T> timed(block: () -> T, record: (Long) -> Unit): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            record(System.nanoTime() - started)
        }
    }

    private companion object {
        const val LOG_INTERVAL = 400L
        private val log = LoggerFactory.getLogger(AiSubmitTiming::class.java)

        private fun avgMs(ns: LongAdder, n: Long): Double = ns.sum().toDouble() / n / 1_000_000.0

        private fun fmtMs(ms: Double): String = "%.3f".format(ms)

        private fun fmtBucket(name: String, ms: Double, children: String = ""): String =
            if (children.isEmpty()) "$name=${fmtMs(ms)}" else "$name=${fmtMs(ms)} ($children)"
    }
}
