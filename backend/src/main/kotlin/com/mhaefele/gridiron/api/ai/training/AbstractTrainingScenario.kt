package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide

/** Board setup and post-crank validation for one [TrainingScenarioEnum] curriculum episode. */
abstract class AbstractTrainingScenario {
    abstract val scenario: TrainingScenarioEnum

    /** Catalog taxonomy for the Python harness (board vs claim unlock gates). */
    open val family: TrainingScenarioFamily = TrainingScenarioFamily.BOARD

    /**
     * When non-null, curriculum difficulty is stored/read under this scenario's name
     * (e.g. bounce tracks loose-ball run). The harness skips scenario-specific after-eval on
     * this drill; difficulty follows the proxy target instead.
     *
     * Must pair with [gatesProgress] = false — proxy drills are never curriculum gates.
     */
    open val difficultyProxy: TrainingScenarioEnum? = null

    /**
     * When false, the harness still trains, evals, and bumps difficulty on this drill but
     * excludes it from curriculum unlock gates (lead pack, claim unlock, scenario_a/b, full-game).
     *
     * Use when we want exposure in the training mix but do not yet trust the drill for gating:
     * either the expert/lookahead oracle is incomplete (we do not know the right moves), or
     * placement scatter still produces boards we cannot guarantee are solvable under eval.
     *
     * Required (always false) when [difficultyProxy] is set.
     */
    open val gatesProgress: Boolean = true

    /**
     * When false, [com.mhaefele.gridiron.integration.TrainingScenarioLookaheadTest] and expert
     * export still run lookahead choose→submit but do **not** require [TrainingDrillStatus.PASS]
     * (no trusted oracle yet). Default on unless [difficultyProxy] is set (proxy drills are
     * omitted from the lookahead solve test entirely).
     */
    open val lookaheadSolveEval: Boolean get() = difficultyProxy == null

    /**
     * Trainer-roster gate for scenario match creation. Default accepts every team; ability drills
     * override to require Pinpoint Passer, Enforcer, Second Swing, etc.
     */
    open val trainerTeamFilter: (TeamCard) -> Boolean = { true }

    /**
     * Optional budgeted action to play after the first crank so the episode opens mid-check
     * (OS / EP / Do-Over / Enforcer). [AbstractHeldCheckTrainingScenario] overrides with a
     * non-null action; default none — episode opens on the first player pick.
     */
    open fun actionToPrime(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>): LegalAction? = null

    /**
     * When non-null, [TrainingScenarios] binds a fresh [ScriptedDiceRoller] on [DiceRollerContext]
     * while cranking and priming so held-check windows open reliably (production + export).
     */
    open fun placementDiceScript(): (ScriptedDiceRoller.() -> Unit)? = null

    abstract fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int)

    /**
     * Budgeted player actions the lookahead solve test allows before [TrainingDrillStatus.PASS].
     * Override on multi-action drills.
     */
    open val expectedBudgetedActions: Int = 1

    /**
     * Challenge-card point value (or other opening-turn target) for [TrainingDrillStatus.DISTRACTED].
     * When null, a miss on the opening turn stays a loss unless [TrainingDrillStatus.PASS].
     */
    open fun expectedOpeningTurnPoints(state: MatchState): Int? = null

    /**
     * Optional match-level tweaks after the board is placed (challenge tableau, turn number, …).
     * Default leaves [state] unchanged.
     */
    open fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState = state

    /**
     * When true, [TrainingScenarios] shuffles claim fillers into empty challenge tableau slots
     * after placement (see [com.mhaefele.gridiron.api.ai.training.claim.ClaimFillerSelector]).
     */
    open val assignsRandomClaimFillers: Boolean = false

    abstract fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>)

    /**
     * Soft-mask rows for the trainer (logit bias via ``softMaskLegal``). Does **not** remove
     * other legal rows — see [isPassingScenarioMove] for moves that grade PASS.
     *
     * Default: first budgeted action on the episode opening coach turn via [isSoftMaskOpeningMove].
     * Multi-step drills override to keep flagging follow-ups on that same opening turn.
     * [TrainingScenarios] only consults this on the trainer opening turn.
     */
    open fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (state.currentTurnState.currentPlayerAction != null) return false
        return if (state.currentTurnState.actionsThisTurn.isEmpty()) {
            isSoftMaskOpeningMove(action, state, trainerSide)
        } else {
            isSoftMaskFollowUpMove(action, state, trainerSide)
        }
    }

    /**
     * Move that would grade PASS on the opening coach turn (wired to ``passingScenarioMove`` /
     * ``passingLegal``). Usually matches [isSoftMaskScenarioMove]; choice drills (throw target,
     * marked-run follow-up, bounce block/score, …) override when PASS is a strict subset of
     * soft-mask rows.
     * [TrainingScenarios] only consults this on the trainer opening turn.
     */
    open fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (state.currentTurnState.currentPlayerAction != null) return false
        return if (state.currentTurnState.actionsThisTurn.isEmpty()) {
            isPassingOpeningMove(action, state, trainerSide)
        } else {
            isPassingFollowUpMove(action, state, trainerSide)
        }
    }

    /**
     * First player action on the episode opening coach turn (**soft-mask**).
     * Default none — drills override (e.g. throw drills flag all carrier throws).
     */
    open fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean = false

    /** Opening-turn move that would grade [TrainingDrillStatus.PASS] (default: same as soft-mask opening). */
    open fun isPassingOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isSoftMaskOpeningMove(action, state, trainerSide)

    /**
     * Follow-up on the opening turn after the first budgeted action (soft-mask).
     * One-shot: frozen opening PASS id still on the menu **and** [isSoftMaskOpeningMove] still
     * true (e.g. throw-then-ball-less EZ run keeps the id but must not soft-mask). Multi-step override.
     */
    open fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        expectedBudgetedActions == 1 && isSavedOpeningPassingMove(action, state) && isSoftMaskOpeningMove(action, state, trainerSide)

    /**
     * Pass-grade follow-up on the opening turn.
     * One-shot: frozen id + [isPassingOpeningMove] still true (snapshot alone is not enough —
     * geometry ids survive after the ball leaves). Multi-step: soft-mask follow-up.
     */
    open fun isPassingFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        if (expectedBudgetedActions == 1) {
            isSavedOpeningPassingMove(action, state) && isPassingOpeningMove(action, state, trainerSide)
        } else {
            isSoftMaskFollowUpMove(action, state, trainerSide)
        }

    /** True when [action] matches a frozen opening PASS id on this episode. */
    protected fun isSavedOpeningPassingMove(action: LegalAction, state: MatchState): Boolean {
        val ids = state.trainingEpisode?.openingPassingActionIds ?: return false
        return action.id.value in ids
    }

    /**
     * Drill grade for one submit (curriculum / winrate; harness scenario terminal on window end).
     * Only called while episode [TrainingDrillStatus] is still [TrainingDrillStatus.STILL_IN_PROGRESS].
     *
     * Default: PASS on touchdown or [isPassingScenarioMove].
     * [TrainingScenarios.updatedTrainingEpisode] only invokes this on the trainer opening turn.
     */
    open fun drillStatus(before: MatchState, after: MatchState, action: LegalAction): TrainingDrillStatus {
        val trainerSide = trainerSide(before)
        if (scoredTouchdown(before, after, trainerSide)) return TrainingDrillStatus.PASS
        if (isPassingScenarioMove(action, before, trainerSide)) return TrainingDrillStatus.PASS
        return TrainingDrillStatus.STILL_IN_PROGRESS
    }

    /** [MatchState.trainingEpisode] must be set on every scenario match. */
    protected fun requireTrainingEpisode(state: MatchState): TrainingEpisodeConfig =
        state.trainingEpisode ?: error("${scenario.name}: trainingEpisode required")

    protected fun trainerSide(state: MatchState): TeamSide = requireTrainingEpisode(state).trainerSide

    /**
     * Board-family difficulty scatter (opponent first, then trainer).
     * Claim drills use a different order in [com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario.buildBoard]
     * (trainer first + throw-range / isolate guards) — keep those policies separate on purpose.
     */
    protected fun scatterDifficultyExtras(
        setup: TrainingBoardBuilder,
        trainerSide: TeamSide,
        difficulty: Int,
        opponentAvoid: Collection<BoardSquare> = emptyList(),
        trainerAvoid: Collection<BoardSquare> = emptyList(),
    ) {
        setup.scatterRemainingReserves(trainerSide.opposite(), maxPlayers = difficulty, avoid = opponentAvoid)
        setup.scatterRemainingReserves(trainerSide, maxPlayers = difficulty, avoid = trainerAvoid)
    }

    /**
     * Opponent carrier within 0..2 rows of [carrierNearEndZone] + trainer adjacent; clear around
     * for difficulty scatter. Used by [StripAndScoreTrainingScenario] and deny-carrier drills.
     */
    protected fun buildAdjacentCarrierBlockBoard(
        setup: TrainingBoardBuilder,
        trainerSide: TeamSide,
        carrierNearEndZone: TeamSide,
        difficulty: Int,
    ) {
        val ballHolder = setup.placeRandomPlayerWithBall(
            trainerSide.opposite(),
            setup.withinRowsOf(carrierNearEndZone, 0..2, excludeEndZone = true),
        )
        setup.placeRandomReserve(trainerSide, setup.emptyAdjacentSquares(ballHolder.square))
        val clearAround = ballHolder.adjacentSquares()
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = clearAround, trainerAvoid = clearAround)
    }

    protected fun validateAdjacentCarrierBlock(
        state: MatchState,
        trainerSide: TeamSide,
        legals: List<LegalAction>,
    ) {
        val carrier = opponentBallCarrier(state, trainerSide)
        val carrierCoord = state.board.findSquare(carrier.id).coordinate
        require(legals.filterIsInstance<LegalAction.Block>().any { it.destination == carrierCoord }) { "${scenario.name}: no legal block targets opponent carrier ${carrier.id} at $carrierCoord" }
    }

    protected fun isSoftMaskAdjacentCarrierBlock(
        action: LegalAction,
        state: MatchState,
        trainerSide: TeamSide,
    ): Boolean {
        val carrier = opponentBallCarrierOrNull(state, trainerSide) ?: return false
        val carrierCoord = state.board.findSquare(carrier.id).coordinate
        return action is LegalAction.Block && action.destination == carrierCoord
    }

    protected fun runsIntoEndZone(legals: List<LegalAction>, board: Board, endZone: TeamSide): Boolean =
        legals.filterIsInstance<LegalAction.Run>().any { run ->
            board.squareAt(run.path.squares.last()).isEndZone(endZone)
        }

    /** Trainer's on-pitch ball carrier, or null when the ball has already left (follow-up menus). */
    protected fun trainerBallCarrierOrNull(state: MatchState, trainerSide: TeamSide): Player? =
        state.board.players.values.singleOrNull { it.team == trainerSide && it.heldBallId != null }

    /**
     * Trainer's on-pitch ball carrier. Throws when absent — missing carrier is a broken drill
     * setup. Soft-mask / PASS predicates use [trainerBallCarrierOrNull] instead.
     */
    protected fun trainerBallCarrier(state: MatchState, trainerSide: TeamSide): Player =
        trainerBallCarrierOrNull(state, trainerSide) ?: error("${scenario.name}: no trainer ball carrier on board")

    protected fun opponentBallCarrierOrNull(state: MatchState, trainerSide: TeamSide): Player? =
        state.board.players.values.singleOrNull { it.team == trainerSide.opposite() && it.heldBallId != null }

    protected fun opponentBallCarrier(state: MatchState, trainerSide: TeamSide): Player =
        opponentBallCarrierOrNull(state, trainerSide) ?: error("${scenario.name}: no opponent ball carrier on board")

    protected fun touchdowns(state: MatchState, side: TeamSide): Int =
        when (side) {
            TeamSide.HOME -> state.progress.homeTouchdowns
            TeamSide.AWAY -> state.progress.awayTouchdowns
        }

    protected fun scoredTouchdown(before: MatchState, after: MatchState, side: TeamSide): Boolean =
        touchdowns(after, side) > touchdowns(before, side)

    protected fun isBlockOnThemBallCarrier(action: LegalAction, board: Board, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Block) return false
        val targetId = board.squareAt(action.destination).playerId ?: return false
        val target = board.players[targetId] ?: return false
        return target.team == trainerSide.opposite() && target.holdsBall()
    }

    protected fun isRunOntoLooseBall(action: LegalAction, board: Board, trainerSide: TeamSide): Boolean {
        if (board.players.values.any { it.team == trainerSide && it.holdsBall() }) return false
        val dest = movementDestination(action) ?: return false
        val square = board.squareAt(dest)
        return square.looseBallId != null && square.playerId == null
    }

    /** Any [LegalAction.Throw] by the trainer's on-pitch ball carrier. */
    protected fun isTrainerBallCarrierThrow(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Throw) return false
        val carrier = trainerBallCarrierOrNull(state, trainerSide) ?: return false
        return action.playerId == carrier.id
    }

    protected fun isThrowToScoringEndZone(action: LegalAction, board: Board, trainerSide: TeamSide): Boolean {
        val dest = when (action) {
            is LegalAction.Throw             -> action.targetCoordinate
            is LegalAction.QuickReleaseThrow -> action.targetCoordinate
            else                             -> return false
        }
        return isOpponentEndZone(board, dest, trainerSide)
    }

    /** Scoring end zone for [trainerSide] (the opposition's end zone). */
    protected fun isOpponentEndZone(board: Board, coordinate: Coordinate, trainerSide: TeamSide): Boolean =
        board.squareAt(coordinate).isEndZone(trainerSide.opposite())

    protected fun movementDestination(action: LegalAction): Coordinate? =
        when (action) {
            is LegalAction.Run        -> action.path.squares.last()
            is LegalAction.DeclareRun -> action.targetCoordinate
            is LegalAction.Sidestep   -> action.path.squares.last()
            else                      -> null
        }

    protected fun looseBallGuardSquares(board: Board, trainerSide: TeamSide): Set<Coordinate> {
        val ballSquare = board.allSquares().firstOrNull { it.looseBallId != null && it.playerId == null }
            ?: return emptySet()
        val opp = trainerSide.opposite()
        return neighbors(ballSquare, board)
            .filter { square ->
                val id = square.playerId ?: return@filter false
                board.players[id]?.team == opp
            }
            .map { it.coordinate }
            .toSet()
    }
}
