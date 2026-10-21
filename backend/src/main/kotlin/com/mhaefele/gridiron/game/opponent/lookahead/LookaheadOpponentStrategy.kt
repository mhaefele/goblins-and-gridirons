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
import org.jetbrains.annotations.TestOnly
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
) : OpponentStrategy {

    /** Pick plus the search scores from that same call. */
    @TestOnly
    data class ChosenAction(
        val action: LegalAction,
        val search: SearchScores = SearchScores.NONE,
    )

    /** [search.evaluate] baseline and best score. [NONE] when this call did not search. */
    @TestOnly
    data class SearchScores(
        val baseline: Double,
        val bestScore: Double,
        val usedSearch: Boolean,
    ) {
        /** Leaf EV cleared [LookaheadScorer.DECISIVE_MARGIN] above the pre-action baseline. */
        val decisive: Boolean
            get() = usedSearch && bestScore >= baseline + LookaheadScorer.DECISIVE_MARGIN

        companion object {
            val NONE = SearchScores(Double.NaN, Double.NaN, usedSearch = false)
        }
    }

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

    /** Seats on the match set the limit. No arena search counter. */
    override fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction =
        choose(state, legal, thinkBudgetNanos = thinkBudgetNanosFor(state), telemetry = null).action

    /**
     * Same bean, with an arena search limit and search counter.
     *
     * @param thinkBudgetOverride replaces [thinkBudgetNanosFor] for this call. Live [chooseAction] does not
     *   use this; it reads who controls the seats. An arena passes its own limit when two seats need
     *   different budgets than those seats imply.
     * @param telemetry null on the live path. The single-game arena passes a counter so its summary can
     *   print search count and average think time. Live search time is recorded on [AiSubmitTiming].
     */
    internal fun chooseAction(
        state: MatchState,
        legal: List<LegalAction>,
        thinkBudgetOverride: Long,
        telemetry: SearchTelemetry?,
    ): LegalAction = choose(state, legal, thinkBudgetNanos = thinkBudgetOverride, telemetry = telemetry).action

    /** Same limit and counter as live [chooseAction]. Also returns the search scores. */
    @TestOnly
    fun chooseActionForTest(state: MatchState, legal: List<LegalAction>): ChosenAction =
        choose(state, legal, thinkBudgetNanos = thinkBudgetNanosFor(state), telemetry = null)

    /**
     * @param thinkBudgetNanos limit this search uses. Live [chooseAction] passes [thinkBudgetNanosFor].
     *   An arena passes its thinkBudgetOverride here instead.
     * @param telemetry null on the live path. The single-game arena's counter for the printed summary.
     */
    private fun choose(
        state: MatchState,
        legal: List<LegalAction>,
        thinkBudgetNanos: Long,
        telemetry: SearchTelemetry?,
    ): ChosenAction {
        require(legal.isNotEmpty()) { "No legal actions to choose from" }
        promptAnswer(state, legal)?.let { return ChosenAction(it) }

        val searchable = legal.filter { it.isSearchableCandidate() }
        if (searchable.isEmpty() || searchable.all { it is LegalAction.Reserves }) {
            return ChosenAction(heuristic.choose(state, legal))
        }
        obviousPlay(state, searchable)?.let { return ChosenAction(it) }
        val heurPick = heuristic.choose(state, searchable)
        if (heurPick.potentialChallengeClaims.isNotEmpty()) return ChosenAction(heurPick)
        val ranked = searchPick(state, searchable, thinkBudgetNanos, telemetry)
        if (ranked.isEmpty()) return ChosenAction(correctSearchMiss(state, heurPick, heurPick, searchable))
        val baseline = LookaheadScorer.score(state, state.currentTurnState.activeCoach)
        val chosen = rankedChoice(searchable, heurPick, ranked, baseline)
        val action = correctSearchMiss(state, chosen, heurPick, searchable)
        return ChosenAction(action, SearchScores(baseline, ranked.maxOf { it.score }, usedSearch = true))
    }

    /**
     * Prompts search must not answer.
     * Bonus, claim, and the held-check reroll are the same picks [ScriptedOpponentStrategy] makes;
     * they are repeated here so this method never searches them.
     * ReplaceChallenge is not here: the heuristic dumps the lowest slot, and search has to see the slots.
     */
    private fun promptAnswer(state: MatchState, legal: List<LegalAction>): LegalAction? {
        if (legal.any { it is LegalAction.PlayBonusCard || it is LegalAction.AdjustThrowByDoOver || it is LegalAction.AdjustBlockByDoOver || it is LegalAction.AdjustArmourByDoOver }
        ) {
            ChallengePursuit.pickDoOverAdjust(legal)?.let { return it }
            ChallengePursuit.pickPlayBonus(legal)?.let { return it }
            legal.filterIsInstance<LegalAction.DeclineBonus>().firstOrNull()?.let { return it }
        }
        ChallengePursuit.pickClaimChallenge(legal)?.let { return it }
        endRunToBankGrabIt(state, legal)?.let { return it }
        return ChallengePursuit.pickHeldCheckReroll(state, legal)
    }

    /** One legal action is already the play, so the think budget is not spent. */
    private fun obviousPlay(state: MatchState, legal: List<LegalAction>): LegalAction? =
        obviousBallCarrierPassingThrow(state, legal)
            ?: obviousTouchdown(state, legal)
            ?: obviousMarkedCarrierSidestep(state, legal)
            ?: obviousRiskyLongThrow(state, legal)
            ?: obviousLooseBallPickup(state, legal)
            ?: obviousLooseBallGuardBlock(state, legal)
            ?: obviousBlockOnOpponentCarrier(state, legal)
            ?: obviousAssistedBlock(state, legal)

    /**
     * @param thinkBudgetNanos limit this search uses. Already resolved: [thinkBudgetNanosFor], or the arena's thinkBudgetOverride.
     * @param telemetry null on the live path. The single-game arena's counter for the printed summary.
     */
    private fun searchPick(
        state: MatchState,
        searchable: List<LegalAction>,
        thinkBudgetNanos: Long,
        telemetry: SearchTelemetry?,
    ): List<LookaheadSearch.Evaluation> {
        val started = System.nanoTime()
        val pathish = searchable.filter { it.isMostlyDeterministic() }
        // Dicey Block/Foul stay out of pathish — but claim-annotated ones must still be searched
        // (otherwise Foul boards never evaluate the focus card and fall through to Reserves).
        val claimAnnotated = searchable.filter { it.potentialChallengeClaims.isNotEmpty() }
        val pool = (pathish + claimAnnotated).distinctBy { it.id }.ifEmpty { searchable }
        val ranked = search.evaluate(
            state = state,
            candidates = pool,
            deadlineNanos = started + thinkBudgetNanos,
        )
        val searchNs = System.nanoTime() - started
        telemetry?.record(searchNs)
        submitTiming.recordLookaheadSearch(state.id, searchNs)
        return ranked
    }

    private fun rankedChoice(
        searchable: List<LegalAction>,
        heurPick: LegalAction,
        ranked: List<LookaheadSearch.Evaluation>,
        baseline: Double,
    ): LegalAction {
        val best = ranked.maxBy { it.score }
        val heurScore = ranked.firstOrNull { it.action.id == heurPick.id }?.score
        val claimOnBest = best.action.potentialChallengeClaims.maxOfOrNull { it.points } ?: 0
        return when {
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

    /** Search dry-runs favor bombs, obstructed throws, and solo blocks. Put the obvious play back. */
    private fun correctSearchMiss(
        state: MatchState,
        chosen: LegalAction,
        heurPick: LegalAction,
        searchable: List<LegalAction>,
    ): LegalAction {
        val team = state.currentTurnState.activeCoach
        val cleared = preferClearThrow(chosen, heurPick, searchable)
        val opened = preferOpenThrow(cleared, heurPick, searchable, state)
        val scored = preferScoringThrow(opened, searchable, state)
        val assisted = preferAssistedBlock(scored, heurPick, searchable, state.board)
        val onCarrier = preferBlockOnOpponentCarrier(assisted, heurPick, searchable, state, team)
        return preferClaimAnnotated(onCarrier, heurPick, searchable)
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
    private fun preferScoringThrow(chosen: LegalAction, legal: List<LegalAction>, state: MatchState): LegalAction {
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

    /**
     * Live search limit from who controls the seats on [state].
     * 300ms against a human, 100ms for computer vs computer.
     * An arena's thinkBudgetOverride replaces this when two seats need different limits than these seats imply.
     */
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
