package com.mhaefele.gridiron.game.opponent.lookahead

import com.mhaefele.gridiron.api.ai.AiSubmitTiming
import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.isAssistedBlock
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.scripted.ScriptedOpponentStrategy
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * AI1.6 — lookahead on mostly-deterministic actions; dicey blocks/bombs stay with the heuristic.
 *
 * Prompt handling still defers to [ScriptedOpponentStrategy]. Search uses the injected apply stack
 * with a private [com.mhaefele.gridiron.game.dice.ScriptedDiceRoller] bound on
 * [com.mhaefele.gridiron.game.dice.DiceRollerContext] — never MatchGame's production roller.
 *
 * Think budget: ~300ms when facing a human seat, ~100ms for computer-vs-computer (arena / AI v AI).
 */
@Component
@Primary
class LookaheadOpponentStrategy(
    private val heuristic: ScriptedOpponentStrategy,
    private val search: LookaheadSearch,
    private val submitTiming: AiSubmitTiming,
    //  TODO:  This feels like state, but on a singleton class.  should maybe rework it, especially if we have multiple games going at once..
    val telemetry: SearchTelemetry? = null,
    /** Override derived budget (tests / arena). Null = derive from [MatchState] coach seats. */
    private val thinkBudgetNanos: Long? = null,
) : OpponentStrategy {

    /** Last [search.evaluate] scores for the most recent searchable choose (tests / diagnostics). */
    class LastSearch {
        var baseline: Double = Double.NaN
            private set
        var bestScore: Double = Double.NaN
            private set
        var usedSearch: Boolean = false
            private set

        fun clear() {
            baseline = Double.NaN
            bestScore = Double.NaN
            usedSearch = false
        }

        fun record(baseline: Double, bestScore: Double) {
            this.baseline = baseline
            this.bestScore = bestScore
            usedSearch = true
        }

        /** Leaf EV cleared [LookaheadScorer.DECISIVE_MARGIN] above the pre-action baseline. */
        val decisive: Boolean
            get() = usedSearch && bestScore >= baseline + LookaheadScorer.DECISIVE_MARGIN
    }

    val lastSearch = LastSearch()

    class SearchTelemetry {
        var searchMoves: Int = 0
            private set
        var searchNanos: Long = 0L
            private set

        fun reset() {
            searchMoves = 0
            searchNanos = 0L
        }

        fun record(nanos: Long) {
            searchMoves++
            searchNanos += nanos
        }

        val averageThinkMs: Double
            get() = if (searchMoves == 0) 0.0 else searchNanos / 1_000_000.0 / searchMoves
    }

    override fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction {
        require(legal.isNotEmpty()) { "No legal actions to choose from" }
        lastSearch.clear()

        if (legal.any { it is LegalAction.PlayBonusCard || it is LegalAction.AdjustThrowByDoOver || it is LegalAction.AdjustBlockByDoOver || it is LegalAction.AdjustArmourByDoOver }
        ) {
            ChallengePursuit.pickDoOverAdjust(legal)?.let { return it }
            ChallengePursuit.pickPlayBonus(legal)?.let { return it }
            legal.filterIsInstance<LegalAction.DeclineBonus>().firstOrNull()?.let { return it }
        }
        ChallengePursuit.pickClaimChallenge(legal)?.let { return it }
        // Mid-run after a Get-the-Ball pickup: end the action so the claim can bank (Skip is not searchable).
        endRunToBankGrabIt(state, legal)?.let { return it }
        // ReplaceChallenge: search the (≤3) slot picks — do not short-circuit to dump-lowest
        // (same-point ties discard by slot and wipe claimable lesson cards).
        ChallengePursuit.pickHeldCheckReroll(state, legal)?.let { return it }

        val searchable = legal.filter { it.isSearchableCandidate() }
        if (searchable.isEmpty()) return heuristic.choose(state, legal)
        if (searchable.all { it is LegalAction.Reserves }) return heuristic.choose(state, legal)

        obviousBallCarrierPassingThrow(state, searchable)?.let { return it }
        obviousTouchdown(state, searchable)?.let { return it }
        obviousMarkedCarrierSidestep(state, searchable)?.let { return it }
        obviousRiskyLongThrow(state, searchable)?.let { return it }
        obviousLooseBallPickup(state, searchable)?.let { return it }
        obviousLooseBallGuardBlock(state, searchable)?.let { return it }
        obviousBlockOnOpponentCarrier(state, searchable)?.let { return it }
        obviousAssistedBlock(state, searchable)?.let { return it }

        val team = state.currentTurnState.activeCoach
        val baseline = LookaheadScorer.score(state, team)
        val pathish = searchable.filter { it.isMostlyDeterministic() }
        // Dicey Block/Foul stay out of pathish — but claim-annotated ones must still be searched
        // (otherwise Foul boards never evaluate the focus card and fall through to Reserves).
        val claimAnnotated = searchable.filter { it.potentialChallengeClaims.isNotEmpty() }
        val heurPick = heuristic.choose(state, searchable)
        if (heurPick.potentialChallengeClaims.isNotEmpty()) return heurPick

        val started = System.nanoTime()
        val budget = thinkBudgetNanos ?: thinkBudgetNanosFor(state)
        return try {
            val pool = (pathish + claimAnnotated).distinctBy { it.id }.ifEmpty { searchable }
            val ranked = search.evaluate(
                state = state,
                candidates = pool,
                deadlineNanos = started + budget,
            )
            if (ranked.isNotEmpty()) {
                lastSearch.record(baseline, ranked.maxOf { it.score })
            }
            val chosen = if (ranked.isEmpty()) {
                heurPick
            } else {
                val best = ranked.maxBy { it.score }
                val heurScore = ranked.firstOrNull { it.action.id == heurPick.id }?.score
                val claimOnBest = best.action.potentialChallengeClaims.maxOfOrNull { it.points } ?: 0
                when {
                    // Forced replace: trust search among ≤3 slots (heuristic dump-lowest is often wrong).
                    searchable.all { it is LegalAction.ReplaceChallenge }    -> best.action
                    best.score >= baseline + LookaheadScorer.DECISIVE_MARGIN -> best.action
                    best.score >= 40_000.0                                   -> best.action
                    // Annotated claim beats a no-claim heuristic pick (includes 1-pt cards).
                    claimOnBest >= 1 && (heurScore == null || best.score >= heurScore) -> best.action
                    // Only override the heuristic when search beat it by a clear margin on a shared candidate.
                    heurScore != null && best.action.isMostlyDeterministic() && best.score >= heurScore + 40.0 -> best.action
                    else                                                                                       -> heurPick
                }
            }
            val searchNs = System.nanoTime() - started
            telemetry?.record(searchNs)
            submitTiming.recordLookaheadSearch(state.id, searchNs)
            preferClaimAnnotated(
                preferBlockOnOpponentCarrier(
                    preferAssistedBlock(
                        preferScoringThrow(
                            preferOpenThrow(
                                preferClearThrow(chosen, heurPick, searchable),
                                heurPick,
                                searchable,
                                state,
                            ),
                            heurPick,
                            searchable,
                            state,
                        ),
                        heurPick,
                        searchable,
                        state.board,
                    ),
                    heurPick,
                    searchable,
                    state,
                    team,
                ),
                heurPick,
                searchable,
            )
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Skip is not in the searchable pool; after a Get-the-Ball pickup the run can still walk away.
     * End immediately so maybeClaimAfterAction can bank.
     */
    private fun endRunToBankGrabIt(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val skip = legal.filterIsInstance<LegalAction.Skip>().firstOrNull() ?: return null
        val team = state.currentTurnState.activeCoach
        val carrier = state.board.players.values.firstOrNull { it.team == team && it.heldBallId != null } ?: return null
        if (carrier.heldBallId in state.cards.ballsPossessedAtTurnStart) return null
        val grabItActive = ChallengeClaimCondition.GRAB_IT in state.cards.activeChallengeClaims()
        return skip.takeIf { grabItActive }
    }

    /** Marked ball carrier must sidestep before they can run — take that before blocking or deploying. */
    private fun obviousMarkedCarrierSidestep(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val team = state.currentTurnState.activeCoach
        val board = state.board
        val carrier = board.players.values.firstOrNull { it.team == team && it.heldBallId != null } ?: return null
        if (standingStatus(carrier, board) != StandingStatus.MARKED) return null
        return legal.filterIsInstance<LegalAction.Sidestep>().firstOrNull { it.playerId == carrier.id }
    }

    /** Ball carrier: open short into the EZ, else closest-range unobstructed scoring throw. */
    private fun obviousBallCarrierPassingThrow(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val team = state.currentTurnState.activeCoach
        val board = state.board
        val carrier = board.players.values.firstOrNull { it.team == team && it.heldBallId != null } ?: return null
        val ez = team.opposite()
        val throws = legal.filterIsInstance<LegalAction.Throw>()
            .filter { it.playerId == carrier.id && board.squareAt(it.target.destination).isEndZone(ez) }
        if (throws.isEmpty()) return null
        throws.filter { it.target.range == ThrowRange.SHORT && standingStatus(it.target.destination, board) == StandingStatus.OPEN }.minWithOrNull(compareBy<LegalAction.Throw>({ if (it.target.obstructed) 1 else 0 }))?.let { return it }
        return throws.minWithOrNull(
            compareBy<LegalAction.Throw>(
                { throwRangeRank(it.target.range) },
                { if (it.target.obstructed) 1 else 0 },
            ),
        )
    }

    /** Long bomb annotated for an active Thread the Needle card. */
    private fun obviousRiskyLongThrow(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val riskyActive = ChallengeClaimCondition.THREAD_THE_NEEDLE in state.cards.activeChallengeClaims()
        if (!riskyActive) return null
        return legal.filterIsInstance<LegalAction.Throw>().firstOrNull { throwAction ->
            throwAction.target.range == ThrowRange.LONG &&
                throwAction.potentialChallengeClaims.any { it.claimCondition == ChallengeClaimCondition.THREAD_THE_NEEDLE }
        }
    }

    /**
     * Prefer an obvious loose-ball pickup unless a different claim line is already annotated
     * (and Grab It is not active) — otherwise claim drills chase a non-lesson loose ball.
     */
    private fun obviousLooseBallPickup(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val pickup = legal.filterIsInstance<LegalAction.Run>().firstOrNull { it.ballEncounter == BallEncounter.PICK_UP }
        if (pickup == null) return null
        val grabItActive = ChallengeClaimCondition.GRAB_IT in state.cards.activeChallengeClaims()
        if (grabItActive) {
            return pickup
        } else {
            val otherClaimLine = legal.any { action ->
                action !== pickup && action.potentialChallengeClaims.isNotEmpty()
            }
            if (otherClaimLine) {
                return null
            } else {
                return pickup
            }
        }
    }

    private fun obviousBlockOnOpponentCarrier(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val team = state.currentTurnState.activeCoach
        val board = state.board
        val carrier = board.players.values.firstOrNull { it.team == team.opposite() && it.holdsBall() } ?: return null
        val carrierAt = board.findSquare(carrier.id).coordinate
        return legal.filterIsInstance<LegalAction.Block>().firstOrNull { it.destination == carrierAt }
    }

    private fun obviousAssistedBlock(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val board = state.board
        val assisted = legal.filterIsInstance<LegalAction.Block>().filter { block ->
            val blocker = board.player(block.playerId)
            val targetId = board.squareAt(block.destination).playerId ?: return@filter false
            val target = board.player(targetId)
            isAssistedBlock(blocker, target, board)
        }
        if (assisted.isEmpty()) return null
        val solo = legal.filterIsInstance<LegalAction.Block>().filter { it !in assisted }
        return if (solo.isNotEmpty()) assisted.first() else null
    }

    /** Block the opponent guarding a contested loose ball before chasing bounce paths. */
    private fun obviousLooseBallGuardBlock(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val board = state.board
        val team = state.currentTurnState.activeCoach
        val ballSquare = board.allSquares().firstOrNull { it.looseBallId != null && it.playerId == null } ?: return null
        val guardCoords = neighbors(ballSquare, board).mapNotNull { square ->
            val occupant = square.playerId?.let { board.players[it] } ?: return@mapNotNull null
            if (occupant.team == team) return@mapNotNull null
            square.coordinate
        }.toSet()
        if (guardCoords.isEmpty()) return null
        return legal.filterIsInstance<LegalAction.Block>().firstOrNull { it.destination in guardCoords }
    }

    private fun preferClaimAnnotated(chosen: LegalAction, heurPick: LegalAction, legal: List<LegalAction>): LegalAction {
        if (chosen.potentialChallengeClaims.isNotEmpty()) return chosen
        val annotated = legal.filter { it.potentialChallengeClaims.isNotEmpty() }
        if (annotated.isEmpty()) return chosen
        heurPick.takeIf { it.potentialChallengeClaims.isNotEmpty() }?.let { return it }
        return annotated.maxByOrNull { it.potentialChallengeClaims.maxOf { claim -> claim.points } } ?: chosen
    }

    private fun preferBlockOnOpponentCarrier(
        chosen: LegalAction,
        heurPick: LegalAction,
        legal: List<LegalAction>,
        state: MatchState,
        team: TeamSide,
    ): LegalAction {
        val board = state.board
        val carrier = board.players.values.firstOrNull { it.team != team && it.holdsBall() } ?: return chosen
        val carrierAt = board.findSquare(carrier.id).coordinate
        if (chosen is LegalAction.Block && chosen.destination == carrierAt) return chosen
        legal.filterIsInstance<LegalAction.Block>().firstOrNull { it.destination == carrierAt }?.let { block ->
            if (chosen !is LegalAction.Block || chosen.destination != carrierAt) return block
        }
        (heurPick as? LegalAction.Block)?.takeIf { it.destination == carrierAt }?.let { return it }
        return chosen
    }

    private fun preferAssistedBlock(
        chosen: LegalAction,
        heurPick: LegalAction,
        legal: List<LegalAction>,
        board: Board,
    ): LegalAction {
        fun assisted(action: LegalAction.Block): Boolean {
            val blocker = board.player(action.playerId)
            val targetId = board.squareAt(action.destination).playerId ?: return false
            val target = board.player(targetId)
            return isAssistedBlock(blocker, target, board)
        }
        if (chosen is LegalAction.Block && assisted(chosen)) return chosen
        val assistedBlocks = legal.filterIsInstance<LegalAction.Block>().filter(::assisted)
        if (assistedBlocks.isEmpty()) return chosen
        if (chosen is LegalAction.Block && !assisted(chosen)) {
            (heurPick as? LegalAction.Block)?.takeIf(::assisted)?.let { return it }
            return assistedBlocks.first()
        }
        return chosen
    }

    private fun preferOpenThrow(
        chosen: LegalAction,
        heurPick: LegalAction,
        legal: List<LegalAction>,
        state: MatchState,
    ): LegalAction {
        if (chosen !is LegalAction.Throw) return chosen
        if (standingStatus(chosen.target.destination, state.board) == StandingStatus.OPEN) return chosen
        (heurPick as? LegalAction.Throw)?.takeIf { it.playerId == chosen.playerId && standingStatus(it.target.destination, state.board) == StandingStatus.OPEN }?.let { return it }
        return legal.filterIsInstance<LegalAction.Throw>()
            .firstOrNull { it.playerId == chosen.playerId && standingStatus(it.target.destination, state.board) == StandingStatus.OPEN }
            ?: chosen
    }

    private fun throwRangeRank(range: ThrowRange): Int = when (range) {
        ThrowRange.HAND_OFF -> 0
        ThrowRange.SHORT    -> 1
        ThrowRange.LONG     -> 2
    }

    /** Prefer hand-off/short scoring throws over long bombs when both reach the end zone. */
    private fun preferScoringThrow(chosen: LegalAction, heurPick: LegalAction, legal: List<LegalAction>, state: MatchState): LegalAction {
        if (chosen !is LegalAction.Throw) return chosen
        val ez = state.currentTurnState.activeCoach.opposite()
        val board = state.board
        val better = legal.filterIsInstance<LegalAction.Throw>()
            .filter { it.playerId == chosen.playerId && board.squareAt(it.target.destination).isEndZone(ez) }
            .minWithOrNull(
                compareBy<LegalAction.Throw>(
                    { throwRangeRank(it.target.range) },
                    { if (it.target.obstructed) 1 else 0 },
                ),
            )
        if (better != null && (chosen.target.range == ThrowRange.LONG || throwRangeRank(better.target.range) < throwRangeRank(chosen.target.range))) {
            return better
        }
        return chosen
    }

    /** Search dry-runs can favor obstructed EZ throws; heuristic already filters them out. */
    private fun preferClearThrow(chosen: LegalAction, heurPick: LegalAction, legal: List<LegalAction>): LegalAction {
        if (chosen !is LegalAction.Throw || !chosen.target.obstructed) return chosen
        (heurPick as? LegalAction.Throw)?.takeIf { !it.target.obstructed && it.playerId == chosen.playerId }?.let { return it }
        return legal.filterIsInstance<LegalAction.Throw>()
            .firstOrNull { it.playerId == chosen.playerId && !it.target.obstructed }
            ?: chosen
    }

    /** Human-vs-AI gets a longer search; AI-vs-AI stays snappy. */
    internal fun thinkBudgetNanosFor(state: MatchState): Long {
        val home = state.config.homeControlledBy
        val away = state.config.awayControlledBy
        val facingHuman = (home == CoachControl.HUMAN && away == CoachControl.COMPUTER) ||(home == CoachControl.COMPUTER && away == CoachControl.HUMAN)
        return if (facingHuman) HUMAN_THINK_NANOS else AI_VS_AI_THINK_NANOS
    }

    private fun obviousTouchdown(state: MatchState, legal: List<LegalAction>): LegalAction? {
        val board = state.board
        val team = state.currentTurnState.activeCoach
        val oppEz = team.opposite()
        val carrier = board.players.values.firstOrNull { it.team == team && it.heldBallId != null }
        fun stepsOf(action: LegalAction): Int = when (action) {
            is LegalAction.Run  -> action.path.steps
            is LegalAction.Mark -> action.path.steps
            else                -> 0
        }
        fun isTd(action: LegalAction): Boolean = when (action) {
            is LegalAction.Run   -> board.players[action.playerId]?.heldBallId != null && board.squareAt(action.path.squares.last()).isEndZone(oppEz)
            is LegalAction.Mark  -> board.players[action.playerId]?.heldBallId != null && board.squareAt(action.path.squares.last()).isEndZone(oppEz)
            is LegalAction.Throw -> board.squareAt(action.target.destination).isEndZone(oppEz)
            else                 -> false
        }
        val tdLegals = legal.filter(::isTd)
        carrier?.id?.let { carrierId ->
            tdLegals.filter { (it as? LegalAction.PlayerLegalAction)?.playerId == carrierId }.minByOrNull(::stepsOf)?.let { return it }
        }
        return tdLegals.minByOrNull(::stepsOf)
    }

    private fun LegalAction.isMostlyDeterministic(): Boolean =
        when (this) {
            is LegalAction.Run,
            is LegalAction.Mark,
            is LegalAction.Sidestep,
            is LegalAction.StandUp,
            is LegalAction.Reserves,
            is LegalAction.DeclareRun,
            is LegalAction.ReplaceChallenge,
                                 -> true
            is LegalAction.Throw -> target.range != ThrowRange.LONG
            else                 -> false
        }

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

    companion object {
        const val AI_VS_AI_THINK_NANOS = 100_000_000L
        const val HUMAN_THINK_NANOS = 300_000_000L
    }
}
