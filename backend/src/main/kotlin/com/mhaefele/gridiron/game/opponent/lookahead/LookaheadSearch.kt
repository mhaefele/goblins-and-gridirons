package com.mhaefele.gridiron.game.opponent.lookahead

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.DiceRollerContext
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.dice.dto.bestEnforcerOrder
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.game.cards.ActionAttachedClaims
import com.mhaefele.gridiron.game.legalmoves.ActionAttachedBallEncounters
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.runPaths
import com.mhaefele.gridiron.game.legalmoves.throwTeammateLandingMoves
import com.mhaefele.gridiron.game.legalmoves.toPathFlags
import com.mhaefele.gridiron.game.opponent.search.DryRunApply
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.PendingDecision
import com.mhaefele.gridiron.model.pending.PlayerSpecificAction
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.model.pending.ClaimChallenge
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.pending.MarkContinuation
import com.mhaefele.gridiron.model.pending.RunContinuation
import com.mhaefele.gridiron.model.pending.ThrowTeammateContinuation
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import com.mhaefele.gridiron.model.pending.OfferUndying
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.pending.OfferDanceStep
import com.mhaefele.gridiron.model.pending.EmergencyReserves
import com.mhaefele.gridiron.model.pending.InitialPlayerPlacement
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.abs
import com.mhaefele.gridiron.game.action.pending.block.OngoingBlock
import com.mhaefele.gridiron.game.action.pending.run.OngoingRun
import com.mhaefele.gridiron.game.action.pending.mark.OngoingMark
import com.mhaefele.gridiron.game.action.pending.throwball.OngoingThrow

/**
 * Wide / shallow beam with optional focused deepen — shaped for this game:
 *
 * 1. Collapse redundant path ends (same player + destination), then order roots by cheap prior.
 * 2. Dry-run each with private scripted dice + greedy settle (RunContinuation hops).
 * 3. **Immediate cutoff** on decisive TD / win leaves.
 * 4. Depth≥2 expands ball-carrier / nearest hunter ([focusedActions]) plus claim-annotated
 *    kids ([deepenKids]) — never full-team fan-out on every ply (swarm teams blow the budget).
 *
 * Uses the injected apply stack (same beans as [com.mhaefele.gridiron.api.MatchGame]). Dice isolation
 * is [DiceRollerContext.withRoller] around [evaluate] — never MatchGame's production roller.
 */
@Component
class LookaheadSearch(
    private val dryRunApply: DryRunApply,
    private val diceRollerContext: DiceRollerContext,
    private val legalMovesGenerator: LegalMovesGenerator,
    private val regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator,
) {
    private val rootWidth = 56
    private val beamWidth = 12
    private val branchWidth = 12
    /** Extra deepen slots reserved for claim-annotated legals (always expanded, not just top-N). */
    private val claimExpandCap = 24
    private val depth = 2
    private val maxNodes = 144
    private val pruneMargin = 50.0

    data class Evaluation(val action: LegalAction, val score: Double)

    fun evaluate(
        state: MatchState,
        candidates: List<LegalAction>,
        team: TeamSide = state.currentTurnState.activeCoach,
        deadlineNanos: Long? = null,
    ): List<Evaluation> {
        if (candidates.isEmpty()) return emptyList()
        val dice = ScriptedDiceRoller(fallbackToDefaultPattern = true)
        return diceRollerContext.withRoller(dice) { evaluateWithDice(state, candidates, team, deadlineNanos, dice) }
    }

    private fun evaluateWithDice(
        state: MatchState,
        candidates: List<LegalAction>,
        team: TeamSide,
        deadlineNanos: Long?,
        dice: ScriptedDiceRoller,
    ): List<Evaluation> {
        val started = System.nanoTime()
        var nodes = 0
        var reachedDepth = 0
        var rootCount = 0
        var stop = "complete"
        val result = try {
            val baseline = LookaheadScorer.score(state, team)
            val collapsed = collapseRedundantPathEnds(candidates, state, team)
            val roots = LookaheadCandidateRanker.top(state, collapsed, rootWidth, team)
            rootCount = roots.size

            var bestAcrossDepth: List<Evaluation> = emptyList()

            fun budget(): Boolean {
                if (nodes >= maxNodes) {
                    stop = "max-nodes"
                    return false
                }
                if (timedOut(deadlineNanos)) {
                    stop = "timeout"
                    return false
                }
                return true
            }

            // Forced replace (≤3 slots): dry-run every discard first, then deepen each root.
            // Prefer claim-annotated kids at ply 2 (that's the bankable line); fall back to ranked
            // top-N only when nothing is annotated. Equal root pass avoids timeout starving later slots.
            if (candidates.isNotEmpty() && candidates.all { it is LegalAction.ReplaceChallenge }) {
                val roots = ArrayList<Pair<LegalAction, Leaf>>(candidates.size)
                for (action in candidates) {
                    if (!budget()) break
                    val leaf = dryRun(dice, state, action, team, baseline, deadlineNanos) ?: continue
                    nodes++
                    reachedDepth = 1
                    roots.add(action to leaf)
                }
                val evals = ArrayList<Evaluation>(roots.size)
                for ((action, leaf) in roots) {
                    var bestForRoot = leaf.score
                    if (!isTerminalForUs(leaf.state, team) && budget() && !timedOut(deadlineNanos)) {
                        val post = legalMovesGenerator.forActingCoach(leaf.state)
                            .filter { it.isSearchableCandidate() }
                        val claimKids = post
                            .filter { it.potentialChallengeClaims.isNotEmpty() }
                            .sortedWith(
                                compareByDescending<LegalAction> { it.potentialChallengeClaims.maxOfOrNull { c -> c.points } ?: 0 }
                                    .thenBy { it.id.value },
                            )
                            .take(claimExpandCap)
                        val kids = claimKids.ifEmpty { deepenKids(leaf.state, post, team) }
                        for (child in kids) {
                            if (!budget() || timedOut(deadlineNanos)) break
                            val childLeaf = dryRun(dice, leaf.state, child, team, baseline, deadlineNanos) ?: continue
                            nodes++
                            reachedDepth = 2
                            bestForRoot = maxOf(bestForRoot, childLeaf.score)
                        }
                    }
                    evals.add(Evaluation(action, bestForRoot))
                }
                stop = if (evals.isEmpty()) "empty" else "replace-menu"
                return evals
            }

            // Small menus (tests): one dry-run per candidate — no beam merge by id.
            if (collapsed.size <= 6) {
                val evals = collapsed.mapNotNull { action ->
                    if (!budget()) return@mapNotNull null
                    val leaf = dryRun(dice, state, action, team, baseline, deadlineNanos) ?: return@mapNotNull null
                    nodes++
                    reachedDepth = 1
                    Evaluation(action, leaf.score)
                }
                stop = if (evals.isEmpty()) "empty" else "shallow-menu"
                return evals
            }

            // --- Depth 1: score every root (no first-decisive short-circuit — callers compare siblings). ---
            val rootBeams = ArrayList<Beam>(roots.size)
            var bestScore = Double.NEGATIVE_INFINITY
            var rootDecisive = false
            for (action in roots) {
                if (!budget()) break
                val leaf = dryRun(dice, state, action, team, baseline, deadlineNanos) ?: continue
                nodes++
                reachedDepth = 1
                bestScore = maxOf(bestScore, leaf.score)
                rootBeams.add(Beam(action, leaf.state, leaf.score, positionKey(leaf.state)))
                if (leaf.decisive) rootDecisive = true
            }
            if (rootBeams.isEmpty()) {
                stop = "empty"
                return emptyList()
            }
            bestAcrossDepth = toEvals(rootBeams)
            if (rootDecisive) {
                stop = "decisive"
                return bestAcrossDepth
            }
            // Root pass already hit timeout / max-nodes — don't relabel as strong-root / headroom.
            if (stop != "complete") return bestAcrossDepth

            // Strong root already (near-TD / big swing) — skip deepen; spend budget elsewhere next move.
            // TODO: depth=2 is often skipped here (and below on think-budget), so 2-ply lines like
            // sidestep→TD never get expanded. Fixing that would strengthen the bot; leave weak for now
            // while the trainer still struggles to beat it.
            if (bestScore >= baseline + LookaheadScorer.DECISIVE_MARGIN * 0.7) {
                stop = "strong-root"
                return bestAcrossDepth
            }
            // Time mostly spent on roots — don't start deepen without headroom.
            if (timedOut(deadlineNanos) || remainingNanos(deadlineNanos) < 25_000_000L) {
                stop = if (timedOut(deadlineNanos)) "timeout" else "headroom"
                return bestAcrossDepth
            }

            var beam = prune(rootBeams, bestScore).sortedByDescending { it.score }.take(beamWidth)

            // --- Iterative deepen 2..depth ---
            for (ply in 2..depth) {
                if (!budget() || beam.isEmpty()) break
                val seen = HashSet<Long>(beam.size * branchWidth)
                val next = ArrayList<Beam>(beam.size * branchWidth)
                var best = beam.maxOf { it.score }

                for (b in beam) {
                    if (!budget()) break
                    if (b.score >= 40_000.0 || isTerminalForUs(b.state, team)) {
                        next.add(b)
                        continue
                    }
                    if (timedOut(deadlineNanos)) {
                        stop = "timeout"
                        next.add(b)
                        continue
                    }
                    val legal = focusedActions(b.state, team)
                    if (legal.isEmpty()) {
                        next.add(b)
                        continue
                    }
                    val searchable = legal.filter { it.isSearchableCandidate() }.ifEmpty { legal }
                    val kids = deepenKids(b.state, searchable, team)
                    var expanded = false
                    for (child in kids) {
                        if (!budget()) break
                        val leaf = dryRun(dice, b.state, child, team, baseline, deadlineNanos) ?: continue
                        nodes++
                        reachedDepth = ply
                        expanded = true
                        if (!seen.add(leaf.key)) continue
                        best = maxOf(best, leaf.score)
                        next.add(Beam(b.root, leaf.state, leaf.score, leaf.key))
                        if (leaf.decisive && b.root !is LegalAction.ReplaceChallenge) stop = "decisive"
                    }
                    if (!expanded) next.add(b)
                }

                if (next.isEmpty()) break
                beam = prune(next, best).sortedByDescending { it.score }.take(beamWidth)
                bestAcrossDepth = toEvals(beam)
                // Found a clearly winning line — no need for another ply.
                if (best >= baseline + LookaheadScorer.DECISIVE_MARGIN && stop == "complete") {
                    stop = "decisive-margin"
                    break
                }
            }

            bestAcrossDepth
        } finally {
            logSlowSearch(
                state = state,
                team = team,
                elapsedNanos = System.nanoTime() - started,
                budgetNanos = deadlineNanos?.let { it - started },
                reachedDepth = reachedDepth,
                nodes = nodes,
                rootCount = rootCount,
                candidates = candidates,
                stop = stop,
                timedOut = timedOut(deadlineNanos),
            )
        }
        return result
    }

    private fun logSlowSearch(
        state: MatchState,
        team: TeamSide,
        elapsedNanos: Long,
        budgetNanos: Long?,
        reachedDepth: Int,
        nodes: Int,
        rootCount: Int,
        candidates: List<LegalAction>,
        stop: String,
        timedOut: Boolean,
    ) {
        // Hitting the think deadline on a full-ish search is normal (esp. parallel expert export).
        // WARN only on anomalies: wall overrun past the deadline, hot us/node on a long search,
        // or shallow/few nodes after timeout. Tiny menus (e.g. ReplaceChallenge ×3) inflate
        // us/node via fixed overhead — ignore that unless wall time is also hot.
        val usPerNode = if (nodes > 0) elapsedNanos / nodes / 1_000L else 0L
        val anomalous = when {
            budgetNanos == null                                            -> elapsedNanos >= SLOW_NANOS
            elapsedNanos > budgetNanos + OVERRUN_SLACK_NANOS               -> true
            elapsedNanos >= HOT_WALL_NANOS && usPerNode >= HOT_US_PER_NODE -> true
            timedOut && reachedDepth <= 1 && nodes < rootCount / 2         -> true
            else                                                           -> false
        }
        if (!anomalous) return
        val pending = state.currentTurnState.pendingDecision?.let { deepestDelegate(it)::class.simpleName } ?: "none"
        log.warn(
            "Slow lookahead: {}ms budget={}ms depth={} nodes={}/{} us/node={} stop={} timedOut={} match={} team={} phase={} pending={} roots={} candidates={} mix={}",
            elapsedNanos / 1_000_000L,
            budgetNanos?.div(1_000_000L) ?: -1L,
            reachedDepth,
            nodes,
            maxNodes,
            usPerNode,
            stop,
            timedOut,
            state.id.value,
            team,
            state.currentTurnState.phase,
            pending,
            rootCount,
            candidates.size,
            candidateMix(candidates),
        )
    }

    /** Compact type histogram for slow-search WARNs (e.g. Run=180,Block=40). */
    private fun candidateMix(candidates: List<LegalAction>): String =
        candidates.groupingBy { it::class.simpleName ?: "?" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(6)
            .joinToString(",") { "${it.key}=${it.value}" }

    fun applyAndSettle(
        state: MatchState,
        action: LegalAction,
        team: TeamSide = state.currentTurnState.activeCoach,
        maxAutoSteps: Int = 6,
        deadlineNanos: Long? = null,
    ): MatchState {
        var next = play(state, action)
        var steps = 0
        while (
            next.progress.result == null &&
            // TODO(pending): settle loop peeks nullable pending + checkNotNull — prefer a single
            // "still settling mid-action" predicate owned by the pending (awaitsCoachInput / not RPA).
            next.currentTurnState.pendingDecision != null &&
            next.currentTurnState.activeCoach == team &&
            steps < maxAutoSteps &&
            (deadlineNanos == null || System.nanoTime() < deadlineNanos)
        ) {
            val pending = checkNotNull(next.currentTurnState.pendingDecision)
            val front = deepestDelegate(pending)
            val pick = settleFollowUp(next, front) ?: break
            next = play(next, pick)
            steps++
        }
        return next
    }

    /**
     * Single-action dry-run apply (no follow-up settle). Arena blind-spot forks bind a private
     * [ScriptedDiceRoller] on [DiceRollerContext] around a sequence of these calls.
     */
    fun apply(state: MatchState, action: LegalAction): MatchState = play(state, action)

    private fun play(state: MatchState, action: LegalAction): MatchState = dryRunApply.play(state, action)

    /**
     * Settle one mid-action hop without full-menu / claim annotate cost.
     * Stops at regular turn slots (do not start a new player action during dry-run settle).
     */
    private fun settleFollowUp(state: MatchState, front: PendingDecision): LegalAction? {
        // Selecting / placement / finished open-action parks are coach beats — don't auto-settle.
        if (front.isFinished()) return null
        when (front) {
            is RegularPlayerAction,
            is OfferExtraEffort,
            is OfferDanceStep,
            is InitialPlayerPlacement,
                -> return null
            // ER has no Skip in-engine (must place). Auto-place one so replace dry-runs reach
            // PLAYER_ACTION and claim deepen can see bankable keep-sets — otherwise search only
            // scores tableau points and dumps the lowest-point active.
            is EmergencyReserves -> {
                val reserves = legalMovesGenerator.forSettle(state).filterIsInstance<LegalAction.Reserves>()
                return LookaheadCandidateRanker.top(state, reserves, 1).firstOrNull() ?: reserves.firstOrNull()
            }
            else                 -> Unit
        }
        awaitingPathRun(front)?.let { return greedyRunSettle(state, it) }
        abilityFollowUpSkip(front)?.let { return it }
        return fastFollowUp(state, front, legalMovesGenerator.forSettle(state))
    }

    /** Nested or top-level [OngoingRun] parked on [AWAITING_PATH]. */
    private fun awaitingPathRun(front: PendingDecision): OngoingRun? {
        fun OngoingRun.takeAwaiting(): OngoingRun? =
            takeIf { stage == OngoingRun.RunStage.AWAITING_PATH }
        return when (front) {
            is RunContinuation   -> front.ongoingRun.takeAwaiting()
            is ThrowContinuation ->
                front.ongoingThrow.nestedRun?.ongoingRun?.takeAwaiting()
                    ?.takeIf { front.ongoingThrow.stage == OngoingThrow.ThrowStage.DELEGATE_ABILITY_FREE_ACTION }
            is BlockContinuation ->
                front.ongoingBlock.nestedRun?.ongoingRun?.takeAwaiting()
                    ?.takeIf { front.ongoingBlock.stage == OngoingBlock.BlockStage.DELEGATE_FREE_ACTION }
            else                 -> null
        }
    }

    /** Cap mid-run settle hops — full remaining-budget path BFS blows the think budget. */
    private fun greedyRunSettle(state: MatchState, ongoing: OngoingRun): LegalAction {
        val playerId = ongoing.playerId
        val player = state.board.player(playerId)
        if (player.status != PlayerStatus.STANDING) return LegalAction.Skip(playerId)
        val holding = player.heldBallId != null
        // Grab It banks when the pickup action ends — keep wandering and deepen never sees the claim.
        val grabItActive = ChallengeClaimCondition.GRAB_IT in state.cards.activeChallengeClaims()
        if (holding && player.heldBallId !in state.cards.ballsPossessedAtTurnStart && grabItActive) {
            return LegalAction.Skip(playerId)
        }
        val cap = if (holding) ongoing.squaresRemaining.coerceAtMost(4) else 1
        val runs = runPaths(player, state.board, cap).map { LegalAction.Run(playerId, it) }
        if (runs.isEmpty()) return LegalAction.Skip(playerId)
        val team = state.currentTurnState.activeCoach
        val pick = LookaheadCandidateRanker.top(state, runs, 1, team).firstOrNull() ?: runs.first()
        return ActionAttachedBallEncounters.withBallEncounter(pick, state.board)
    }

    /** Optional free-ability offers always include Skip — take it without enumerating free Run/Block menus. */
    private fun abilityFollowUpSkip(front: PendingDecision): LegalAction? {
        val playerId: PlayerId = when (front) {
            is OfferUndying      -> front.playerId
            is ThrowContinuation ->
                front.ongoingThrow.takeIf { it.stage == OngoingThrow.ThrowStage.HANDLE_ABILITY_FOLLOW_UP }
                    ?.let { it.offeredAbilityPlayerId ?: it.throwerId } ?: return null
            is RunContinuation   ->
                front.ongoingRun.takeIf { it.stage == OngoingRun.RunStage.HANDLE_ABILITY_FOLLOW_UP }
                    ?.let { it.offeredAbilityPlayerId ?: it.playerId } ?: return null
            is MarkContinuation  ->
                front.ongoingMark.takeIf { it.stage == OngoingMark.MarkStage.HANDLE_ABILITY_FOLLOW_UP }
                    ?.let { it.offeredAbilityPlayerId ?: it.playerId } ?: return null
            is BlockContinuation ->
                front.ongoingBlock.takeIf { it.stage == OngoingBlock.BlockStage.HANDLE_ABILITY_FOLLOW_UP }
                    ?.let { it.offeredAbilityPlayerId ?: it.blockerId } ?: return null
            else                 -> return null
        }
        return LegalAction.Skip(playerId)
    }

    /**
     * Cheap settle pick from [legals] — **must** return an element of [legals] (or null to stop).
     * Prefer [settleFollowUp] so callers do not pay full-menu / claim annotate.
     */
    internal fun fastFollowUp(
        state: MatchState,
        front: PendingDecision,
        legals: List<LegalAction> = legalMovesGenerator.forSettle(state),
    ): LegalAction? {
        if (legals.isEmpty()) return null
        val team = state.currentTurnState.activeCoach

        legals.firstOrNull { it is LegalAction.DeclineBonus }?.let { return it }
        legals.firstOrNull { it is LegalAction.ContinueBlock }?.let { return it }
        legals.filterIsInstance<LegalAction.ChooseBlockFaceOrder>().let { orders ->
            if (orders.isNotEmpty()) {
                val preferred = bestEnforcerOrder(orders.first().orderedFaces)
                return orders.firstOrNull { it.orderedFaces == preferred } ?: orders.first()
            }
        }
        legals.filterIsInstance<LegalAction.ChooseBlockReroll>().firstOrNull { !it.reroll }?.let { return it }
        legals.filterIsInstance<LegalAction.ChooseThrowReroll>().firstOrNull { !it.reroll }?.let { return it }
        legals.filterIsInstance<LegalAction.PlaceLooseBall>().firstOrNull()?.let { return it }
        legals.filterIsInstance<LegalAction.ThrowTeammate>().firstOrNull()?.let { return it }
        legals.filterIsInstance<LegalAction.PlaySprint>().firstOrNull()?.let { return it }

        val runs = legals.filterIsInstance<LegalAction.Run>()
        if (runs.isNotEmpty()) {
            return LookaheadCandidateRanker.top(state, runs, 1, team).firstOrNull() ?: runs.first()
        }

        if (front is BlockContinuation && front.ongoingBlock.stage == OngoingBlock.BlockStage.HANDLE_SHOVE_FOLLOWUP) {
            val vacated = front.ongoingBlock.shoveVacatedSquare
            if (vacated != null) {
                legals.filterIsInstance<LegalAction.Mark>()
                    .firstOrNull { it.path.squares.last() == vacated }
                    ?.let { return it }
            }
        }
        legals.filterIsInstance<LegalAction.Mark>().firstOrNull()?.let { return it }
        legals.filterIsInstance<LegalAction.StandUp>().firstOrNull()?.let { return it }
        legals.filterIsInstance<LegalAction.Sidestep>().firstOrNull()?.let { return it }

        when (front) {
            is ClaimChallenge   ->
                ChallengePursuit.pickClaimChallenge(legals.filterIsInstance<LegalAction.ClaimChallenge>())
                    ?.let { return it }
            is ReplaceChallenge ->
                ChallengePursuit.pickReplaceChallenge(legals.filterIsInstance<LegalAction.ReplaceChallenge>())
                    ?.let { return it }
            else                -> Unit
        }

        legals.firstOrNull { it is LegalAction.Skip }?.let { return it }
        return null
    }

    /**
     * Many Run/Mark/Sidestep legals share a destination via different routes. Keep the highest-prior
     * path per (kind, player, end) so rootWidth isn't burned on redundant dry-runs.
     */
    private fun collapseRedundantPathEnds(
        candidates: List<LegalAction>,
        state: MatchState,
        team: TeamSide,
    ): List<LegalAction> {
        val ctx = LookaheadCandidateRanker.Context.from(state, team)
        val bestPath = LinkedHashMap<String, LegalAction>()
        val other = ArrayList<LegalAction>(candidates.size)
        for (action in candidates) {
            val key = when (action) {
                is LegalAction.Run      -> "R:${action.playerId.value}:${action.path.squares.last()}"
                is LegalAction.Mark     -> "M:${action.playerId.value}:${action.path.squares.last()}"
                is LegalAction.Sidestep -> "S:${action.playerId.value}:${action.path.squares.last()}"
                else                    -> null
            }
            if (key == null) {
                other.add(action)
                continue
            }
            val prior = bestPath[key]
            if (prior == null || LookaheadCandidateRanker.weight(action, ctx) > LookaheadCandidateRanker.weight(prior, ctx)) {
                bestPath[key] = action
            }
        }
        if (bestPath.isEmpty()) return candidates
        return other + bestPath.values
    }

    private fun toEvals(beams: List<Beam>): List<Evaluation> =
        beams.groupBy { it.root.id.value }
            .map { (_, group) ->
                val best = group.maxBy { it.score }
                Evaluation(best.root, best.score)
            }

    private fun prune(beams: List<Beam>, best: Double): List<Beam> {
        if (beams.isEmpty()) return beams
        val floor = best - pruneMargin
        val kept = beams.filter { it.score >= floor }
        return kept.ifEmpty { listOf(beams.maxBy { it.score }) }
    }

    private fun isTerminalForUs(state: MatchState, team: TeamSide): Boolean =
        state.progress.result != null || state.currentTurnState.activeCoach != team || state.currentTurnState.pendingDecision == null

    private fun dryRun(
        dice: ScriptedDiceRoller,
        state: MatchState,
        action: LegalAction,
        team: TeamSide,
        baseline: Double,
        deadlineNanos: Long?,
    ): Leaf? {
        if (timedOut(deadlineNanos)) return null
        return try {
            dice.reset()
            primaryScript(action)?.invoke(dice)
            val next = applyAndSettle(
                state = state,
                action = action,
                team = team,
                maxAutoSteps = 10,
                deadlineNanos = deadlineNanos,
            )
            val claimHint = action.potentialChallengeClaims.maxOfOrNull { it.points } ?: 0
            var score = LookaheadScorer.score(next, team) + claimHint * 45.0
            // Dicey blocks: blend shove (positional) with smash (knockdown) so we don't overfit one face.
            if (action is LegalAction.Block || action is LegalAction.ContinueBlock) {
                if (!timedOut(deadlineNanos)) {
                    dice.reset()
                    dice.queueBlockDice(BlockDieFace.SMASH)
                    val alt = applyAndSettle(state, action, team, 10, deadlineNanos)
                    score = (score + LookaheadScorer.score(alt, team) + claimHint * 45.0) / 2.0
                }
            }
            val pointsGained = LookaheadScorer.points(next, team) - LookaheadScorer.points(state, team)
            val decisive = pointsGained >= 4 || score >= baseline + LookaheadScorer.DECISIVE_MARGIN || next.progress.result != null
            Leaf(next, score, positionKey(next), decisive)
        } catch (e: Exception) {
            throw e
        }
    }

    /** Only script when the action cannot proceed without a primary roll. Blocks blend in [dryRun]. */
    private fun primaryScript(action: LegalAction): (ScriptedDiceRoller.() -> Unit)? =
        when (action) {
            is LegalAction.DeclareRun                          -> {
                { queueD6(4) }
            }
            is LegalAction.Throw                               ->
                if (action.target.range == ThrowRange.HAND_OFF) null
                else {
                    { queueD6(4) }
                }
            is LegalAction.ThrowTeammate                       -> {
                { queueD6(4) }
            }
            is LegalAction.Block, is LegalAction.ContinueBlock -> {
                { queueBlockDice(BlockDieFace.SHOVE) }
            }
            is LegalAction.Foul                                -> {
                { queueBlockDice(BlockDieFace.SMASH) }
            }
            else                                               -> null
        }

    /** Compact key for TT within one evaluate() — not a cryptographic hash. */
    private fun positionKey(state: MatchState): Long {
        var h = state.lastEventId.toLong()
        h = h * 31 + state.progress.homeScore.points
        h = h * 31 + state.progress.awayScore.points
        h = h * 31 + state.progress.turnNumber
        h = h * 31 + state.currentTurnState.activeCoach.ordinal
        h = h * 31 + state.currentTurnState.actionsRemaining
        h = h * 31 + (if (state.currentTurnState.pendingDecision != null) 1 else 0)
        for (p in state.board.players.values) {
            h = h * 31 + p.id.value.hashCode()
            h = h * 31 + p.status.ordinal
            h = h * 31 + (p.heldBallId?.value?.hashCode() ?: 0)
            if (p.status != PlayerStatus.RESERVES) {
                val c = state.board.findSquare(p.id).coordinate
                h = h * 31 + c.x
                h = h * 31 + c.y
            }
        }
        for (sq in state.board.allSquares()) {
            val loose = sq.looseBallId ?: continue
            h = h * 31 + loose.value.hashCode()
            h = h * 31 + sq.coordinate.x
            h = h * 31 + sq.coordinate.y
        }
        return h
    }

    /**
     * Cheap deepen fan-out: only the ball carrier / nearest ball hunter — never full-team
     * LegalMovesGenerator.forActingCoach (swarm teams blow the think budget).
     */
    private fun focusedActions(state: MatchState, team: TeamSide): List<LegalAction> {
        val board = state.board
        val turn = state.currentTurnState
        val root = turn.pendingDecision
        val pending = root?.let(::deepestDelegate)
        if (pending is PlayerSpecificAction) {
            val player = board.player(pending.playerId)
            if (player.team != team) return emptyList()
            return regularPlayerActionLegalMovesGenerator
                .geometryMoves(player, board, turn.bonusModifiers.toPathFlags())
                .filter { it.isSearchableCandidate() }
        }
        if (pending is ThrowTeammateContinuation) {
            val ongoing = pending.ongoingThrowTeammate
            val player = board.player(ongoing.throwerId)
            if (player.team != team) return emptyList()
            return throwTeammateLandingMoves(player, ongoing.teammateId, board)
                .filter { it.isSearchableCandidate() }
        }
        if (pending is ReplaceChallenge) {
            return legalMovesGenerator.forSettle(state).filterIsInstance<LegalAction.ReplaceChallenge>()
        }
        // Selecting RPA (no delegate) is the normal fan-out slot — do not treat it as "unknown pending".
        if (pending != null && pending !is RegularPlayerAction) return emptyList()
        if (pending is RegularPlayerAction && pending.delegate != null) return emptyList()

        val standing = board.players.values.filter { it.team == team && it.status == PlayerStatus.STANDING }
        if (standing.isEmpty()) return emptyList()

        val carrier = standing.firstOrNull { it.heldBallId != null }
        val theirCarrier = board.players.values.firstOrNull { it.team != team && it.status == PlayerStatus.STANDING && it.heldBallId != null }
        val ballAt = board.allSquares().firstOrNull { it.looseBallId != null }?.coordinate
            ?: carrier?.let { board.findSquare(it.id).coordinate }
            ?: theirCarrier?.let { board.findSquare(it.id).coordinate }

        val focus = buildList {
            carrier?.let { add(it) }
            if (carrier == null && ballAt != null) {
                standing.minByOrNull {
                    val c = board.findSquare(it.id).coordinate
                    maxOf(abs(c.x - ballAt.x), abs(c.y - ballAt.y))
                }?.let { add(it) }
            }
            if (size == 1 && ballAt != null) {
                standing
                    .filter { it.id != first().id }
                    .minByOrNull {
                        val c = board.findSquare(it.id).coordinate
                        maxOf(abs(c.x - ballAt.x), abs(c.y - ballAt.y))
                    }
                    ?.let { add(it) }
            }
        }.distinct().take(2)

        val ballMoves = focus.flatMap { regularPlayerActionLegalMovesGenerator.geometryMoves(it, board, turn.bonusModifiers.toPathFlags()) }.filter { it.isSearchableCandidate() }

        // Claim hints on the same geometry fan-out — never a second full forActingCoach here (budget + invariant).
        val activeClaims = state.cards.activeChallengeClaims()
        if (activeClaims.isEmpty()) return ballMoves
        val annotated = ActionAttachedClaims.annotatePotentialClaims(ballMoves, state)
        val claimish = annotated.filter { action ->
            action.potentialChallengeClaims.any { it.claimCondition in activeClaims }
        }
        if (claimish.isEmpty()) return ballMoves
        return (claimish + ballMoves).distinctBy { it.id }
    }

    /**
     * Deepen fan-out: always include claim-annotated legals (capped), then fill with the usual
     * ranked top-[branchWidth] so ball/TD priors still get budget without burying bankable claims.
     */
    private fun deepenKids(state: MatchState, searchable: List<LegalAction>, team: TeamSide): List<LegalAction> {
        if (searchable.isEmpty()) return emptyList()
        val annotated = searchable
            .filter { it.potentialChallengeClaims.isNotEmpty() }
            .sortedWith(
                compareByDescending<LegalAction> { it.potentialChallengeClaims.maxOfOrNull { c -> c.points } ?: 0 }
                    .thenBy { it.id.value },
            )
            .take(claimExpandCap)
        val ranked = LookaheadCandidateRanker.top(state, searchable, branchWidth, team)
        return (annotated + ranked).distinctBy { it.id.value }
    }

    private fun timedOut(deadlineNanos: Long?): Boolean =
        deadlineNanos?.let { System.nanoTime() >= it } == true

    private fun remainingNanos(deadlineNanos: Long?): Long =
        deadlineNanos?.let { it - System.nanoTime() } ?: Long.MAX_VALUE

    private fun LegalAction.isSearchableCandidate(): Boolean =
        when (this) {
            is LegalAction.Run,
            is LegalAction.Mark,
            is LegalAction.Sidestep,
            is LegalAction.Throw,
            is LegalAction.ThrowTeammate,
            is LegalAction.ThrowTeammateSelect,
            is LegalAction.Block,
            is LegalAction.Foul,
            is LegalAction.StandUp,
            is LegalAction.Reserves,
            is LegalAction.DeclareRun,
            is LegalAction.ReplaceChallenge,
                 -> true
            else -> false
        }

    private data class Beam(
        val root: LegalAction,
        val state: MatchState,
        val score: Double,
        val key: Long,
    )

    private data class Leaf(
        val state: MatchState,
        val score: Double,
        val key: Long,
        val decisive: Boolean,
    )

    /** Walk [PendingDecision.delegate] for settle / focused fan-out — lookahead-only, not domain API. */
    private fun deepestDelegate(pending: PendingDecision): PendingDecision {
        var cur = pending
        while (true) {
            cur = cur.delegate ?: return cur
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LookaheadSearch::class.java)
        /** Absolute floor when evaluate() has no think deadline (live seats always pass one). */
        private const val SLOW_NANOS = 200_000_000L
        /** WARN if wall time exceeds think budget by more than this (GC / scheduler stall). */
        private const val OVERRUN_SLACK_NANOS = 50_000_000L
        /** Floor before us/node can WARN — small menus inflate µs/node without being slow. */
        private const val HOT_WALL_NANOS = 50_000_000L
        /** WARN when dry-run cost per node is well above typical ~0.7–1ms under load. */
        private const val HOT_US_PER_NODE = 2_000L
    }
}
