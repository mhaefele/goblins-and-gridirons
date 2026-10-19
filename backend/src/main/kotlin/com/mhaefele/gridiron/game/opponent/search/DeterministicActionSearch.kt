package com.mhaefele.gridiron.game.opponent.search

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.DiceRollerContext
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.stereotype.Component

/**
 * Deterministic dry-run action search — reusable game-tree walk, not a training feature.
 *
 * ## Basic algorithm (v1)
 *
 * 1. Apply one legal via [DryRunApply] under a fixed optimistic dice overlay.
 * 2. Ask [SearchEvaluator] for [SearchOutcome].
 * 3. On [SearchOutcome.CONTINUE], if still the same coach turn, expand **every** legal from
 *    [LegalMovesGenerator.forActingCoach] (stable id order) and recurse.
 * 4. Horizon is **end of that coach's turn**: [MatchProgress.turnNumber] or [TurnState.activeCoach]
 *    changed from the search root (hand-off bumps both). No SUCCESS by then → that line failed.
 *
 * **Opponent interrupts (quick-release, Brace, …):** turn has not handed off, but
 * [MatchState.actingSeat] follows [PendingDecision.respondingCoach], so [LegalMovesGenerator.forActingCoach]
 * returns the **other** seat's menu. v1 still OR-expands those choices — i.e. we optimistically
 * assume the opponent picks whatever helps us reach SUCCESS. Not an adversary model; revisit when
 * a caller needs worst-case interrupts.
 *
 * Shared with lookahead today: only [DryRunApply]. Lookahead keeps ranking / prune / deepen in
 * its own class — do not merge those here.
 *
 * ## Customization
 *
 * **v1 plug point:** [SearchEvaluator] only (PASS, TD, …).
 *
 * **Not customizable yet** (wait for a second real caller):
 * - dice policy (today: optimistic 6 / SMASH)
 * - expansion strategy (today: DFS; later best-first open set)
 * - alternate horizons (today: end of turn only)
 *
 * No mid-action “settle” shortcut, no budgeted-action depth counter, no candidate allowlists.
 */
@Component
class DeterministicActionSearch(
    private val dryRun: DryRunApply,
    private val legalMoves: LegalMovesGenerator,
    private val diceRollerContext: DiceRollerContext,
) {
    /**
     * After [DryRunApply.play] from [before] → [after] via [action], say whether this line
     * succeeded, died, or should keep expanding.
     *
     * Implementations may grade onto episode state (training) then read [SearchOutcome] from that.
     * The search never imports training types.
     */
    fun interface SearchEvaluator {
        fun evaluate(before: MatchState, after: MatchState, action: LegalAction): SearchOutcome
    }

    /**
     * Which of [roots] can reach [SearchOutcome.SUCCESS] before the search-root coach turn ends
     * (`turnNumber` / `activeCoach` change), under optimistic dice. Empty set means none could
     * (caller decides fallback).
     *
     * [roots] is the menu to try as first moves — caller supplies the acting menu
     * ([LegalMovesGenerator.forActingCoach] already throws if empty).
     */
    fun reachableRootIds(
        state: MatchState,
        roots: List<LegalAction>,
        actingCoach: TeamSide = state.currentTurnState.activeCoach,
        evaluate: SearchEvaluator,
    ): Set<LegalActionId> {
        val rootTurnNumber = state.progress.turnNumber
        val solvable = linkedSetOf<LegalActionId>()
        val nodes = IntArray(1)
        val memo = HashMap<Long, Boolean>()
        // Stable order so a hit on the safety fuse is deterministic across runs.
        for (action in roots.sortedBy { it.id.value }) {
            if (nodes[0] >= SAFETY_NODE_FUSE) break
            if (reachesSuccess(state, action, rootTurnNumber, actingCoach, evaluate, nodes, memo)) {
                solvable += action.id
            }
        }
        return solvable
    }

    private fun reachesSuccess(
        before: MatchState,
        action: LegalAction,
        rootTurnNumber: Int,
        rootCoach: TeamSide,
        evaluate: SearchEvaluator,
        nodes: IntArray,
        memo: MutableMap<Long, Boolean>,
    ): Boolean {
        if (nodes[0] >= SAFETY_NODE_FUSE) return false
        nodes[0]++

        val key = memoKey(before, action.id)
        memo[key]?.let { return it }

        val afterPlay = playOptimistic(before, action) ?: run {
            memo[key] = false
            return false
        }

        when (evaluate.evaluate(before, afterPlay, action)) {
            SearchOutcome.SUCCESS  -> {
                memo[key] = true
                return true
            }
            SearchOutcome.FAILURE  -> {
                memo[key] = false
                return false
            }
            SearchOutcome.CONTINUE -> Unit
        }

        // Hand-off increments turnNumber and swaps activeCoach together (TurnEngine HANDOFF).
        // Interrupts do neither — see class KDoc (optimistic OR over opponent menus).
        if (
            afterPlay.progress.turnNumber != rootTurnNumber ||
            afterPlay.currentTurnState.activeCoach != rootCoach
        ) {
            memo[key] = false
            return false
        }

        // Includes interrupt seats when respondingCoach != activeCoach (optimistic for-us picks).
        val kids = legalMoves.forActingCoach(afterPlay).sortedBy { it.id.value }
        val ok = kids.any { reachesSuccess(afterPlay, it, rootTurnNumber, rootCoach, evaluate, nodes, memo) }
        memo[key] = ok
        return ok
    }

    private fun playOptimistic(state: MatchState, action: LegalAction): MatchState? {
        val overlay = ScriptedDiceRoller()
        queueOptimisticPrimary(action, overlay)
        return try {
            diceRollerContext.withRoller(overlay) { dryRun.play(state, action) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fixed “best primary face” queues for reachability — future dice-policy plug.
     * Exhaustive on sealed [LegalAction]: anything that does not roll leaves the overlay empty.
     */
    private fun queueOptimisticPrimary(action: LegalAction, overlay: ScriptedDiceRoller) {
        when (action) {
            is LegalAction.DeclareRun                          -> overlay.queueD6(6)
            is LegalAction.Throw                               -> if(action.target.range != ThrowRange.HAND_OFF) overlay.queueD6(6)
            is LegalAction.ThrowTeammate                       -> overlay.queueD6(6)
            is LegalAction.Block, is LegalAction.ContinueBlock -> overlay.queueBlockDice(BlockDieFace.SMASH)
            is LegalAction.Foul                                -> overlay.queueBlockDice(BlockDieFace.SMASH)
            is LegalAction.QuickReleaseThrow                   -> overlay.queueD6(6)
            is LegalAction.Run,
            is LegalAction.Mark,
            is LegalAction.Sidestep,
            is LegalAction.ThrowTeammateSelect,
            is LegalAction.CancelThrowTeammate,
            is LegalAction.StandUp,
            is LegalAction.Reserves,
            is LegalAction.Skip,
            is LegalAction.DeclineBonus,
            is LegalAction.PlaceLooseBall,
            is LegalAction.ChooseBlockReroll,
            is LegalAction.ChooseBlockFaceOrder,
            is LegalAction.ChooseThrowReroll,
            is LegalAction.AdjustThrowByDoOver,
            is LegalAction.AdjustBlockByDoOver,
            is LegalAction.AdjustArmourByDoOver,
            is LegalAction.PlayBonusCard,
            is LegalAction.ClaimChallenge,
            is LegalAction.ReplaceChallenge,
                -> Unit
        }
    }

    /**
     * Dry-run never advances [MatchState.lastEventId] (persist owns that). Memo must fingerprint
     * board + turn log or distinct positions collide.
     */
    private fun memoKey(state: MatchState, actionId: LegalActionId): Long {
        var h = positionFingerprint(state)
        h = h * 31 + actionId.value.hashCode()
        return h
    }

    private fun positionFingerprint(state: MatchState): Long {
        var h = state.progress.turnNumber.toLong()
        h = h * 31 + state.currentTurnState.activeCoach.ordinal
        h = h * 31 + state.currentTurnState.actionsRemaining
        h = h * 31 + (state.currentTurnState.pendingDecision?.javaClass?.name?.hashCode() ?: 0)
        for (logged in state.currentTurnState.actionsThisTurn) {
            h = h * 31 + logged.playerId.value.hashCode()
            h = h * 31 + logged.actionType.ordinal
            h = h * 31 + if (logged.isFree) 1 else 0
        }
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
        for (claim in state.cards.activeChallengeClaims()) {
            h = h * 31 + claim.ordinal
        }
        return h
    }

    private companion object {
        /**
         * Safety fuse only — not a search strategy. Full menus (including run paths) can explode;
         * raise this or add best-first later rather than inventing settle/prune here.
         */
        const val SAFETY_NODE_FUSE = 10_000
    }
}
