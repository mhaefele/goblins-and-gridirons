package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.training.claim.AbstractChallengeClaimTrainingScenario
import com.mhaefele.gridiron.api.ai.training.claim.ClaimFillerSelector
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.PlayerActionExecutor
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.dice.DiceRollerContext
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.game.turn.TurnEngine
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.PlayerActionStep
import com.mhaefele.gridiron.shared.PreTurnStep
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import com.mhaefele.gridiron.shared.TurnPhase
import org.springframework.stereotype.Component
import kotlin.math.abs

/**
 * Curriculum scenario scripts + placement. Injects every [AbstractTrainingScenario] bean and
 * looks them up by [TrainingScenarioEnum].
 *
 * [openScenario] builds a match ready for the trainer's first decision (see steps in [tryPlacement]).
 * Callers persist via [com.mhaefele.gridiron.api.MatchGame.createScenarioMatch] — scenarios
 * never go through [com.mhaefele.gridiron.api.MatchGame.startMatch].
 */
@Component
class TrainingScenarios(
    implementations: List<AbstractTrainingScenario>,
    private val turnEngine: TurnEngine,
    private val playerActionExecutor: PlayerActionExecutor,
    private val diceRollerContext: DiceRollerContext,
    private val dice: DiceRoller,
    private val legalMovesGenerator: LegalMovesGenerator,
) {
    private val byKind: Map<TrainingScenarioEnum, AbstractTrainingScenario> =
        implementations.associateBy { it.scenario }.also { map ->
            require(map.size == TrainingScenarioEnum.entries.size) { "Expected one AbstractTrainingScenario bean per TrainingScenarioEnum " + "(got ${map.size} for keys ${map.keys}; beans=${implementations.map { it::class.simpleName }})" }
            implementations.forEach { impl ->
                if (impl.difficultyProxy != null) {
                    require(!impl.gatesProgress) { "${impl.scenario}: difficultyProxy drills must set gatesProgress=false" }
                }
            }
        }

    fun implementation(kind: TrainingScenarioEnum): AbstractTrainingScenario = byKind.getValue(kind)

    /** Catalog for the Python harness — enum order, family, optional difficulty proxy. */
    fun catalogEntries(): List<TrainingScenarioCatalogEntry> =
        TrainingScenarioEnum.entries.map { kind ->
            val impl = byKind.getValue(kind)
            TrainingScenarioCatalogEntry(
                name = kind,
                family = impl.family,
                difficultyProxy = impl.difficultyProxy,
                gatesProgress = impl.gatesProgress,
            )
        }

    /**
     * Episode opening coach turn — training soft-mask / PASS / search gates live here, not inside
     * [com.mhaefele.gridiron.game.opponent.search.DeterministicActionSearch].
     */
    fun isTrainerOpeningTurn(state: MatchState): Boolean {
        val episode = state.trainingEpisode ?: return false
        return state.progress.turnNumber == episode.openingTurnNumber && state.currentTurnState.activeCoach == episode.trainerSide
    }

    /** Non-scenario matches have no episode — scenario flags stay false. */
    fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState): Boolean {
        val episode = state.trainingEpisode ?: return false
        if (episode.drillStatus != TrainingDrillStatus.STILL_IN_PROGRESS) return false
        if (!isTrainerOpeningTurn(state)) return false
        return implementation(episode.scenario).isSoftMaskScenarioMove(action, state, episode.trainerSide)
    }

    /**
     * Menu-level soft-mask ids when the scenario overrides [AbstractTrainingScenario.softMaskLegalIds],
     * else null (caller uses per-row [isSoftMaskScenarioMove]).
     */
    fun softMaskLegalIds(state: MatchState, actions: List<LegalAction>): Set<LegalActionId>? {
        val episode = state.trainingEpisode ?: return null
        if (episode.drillStatus != TrainingDrillStatus.STILL_IN_PROGRESS) return null
        if (!isTrainerOpeningTurn(state)) return null
        return implementation(episode.scenario).softMaskLegalIds(actions, state, episode.trainerSide)
    }

    fun isPassingScenarioMove(action: LegalAction, state: MatchState): Boolean {
        val episode = state.trainingEpisode ?: return false
        if (episode.drillStatus != TrainingDrillStatus.STILL_IN_PROGRESS) return false
        if (!isTrainerOpeningTurn(state)) return false
        return implementation(episode.scenario).isPassingScenarioMove(action, state, episode.trainerSide)
    }

    /**
     * Freeze [TrainingEpisodeConfig.openingPassingActionIds] on the first opening player-action
     * menu (skips replace / mid-player-action / already frozen). Idempotent.
     */
    fun freezeOpeningPassingIdsIfNeeded(state: MatchState, legals: List<LegalAction>): MatchState {
        val episode = state.trainingEpisode ?: return state
        if (episode.openingPassingActionIds != null) return state
        if (episode.drillStatus != TrainingDrillStatus.STILL_IN_PROGRESS) return state
        if (!isTrainerOpeningTurn(state)) return state
        if (state.currentTurnState.currentPlayerAction != null) return state
        if (state.currentTurnState.pendingDecision is ReplaceChallenge) return state
        if (state.currentTurnState.actionsThisTurn.isNotEmpty()) return state
        val script = implementation(episode.scenario)
        val ids = legals
            .filter { script.isPassingOpeningMove(it, state, episode.trainerSide) }
            .map { it.id.value }
            .toSet()
        return state.copy(trainingEpisode = episode.copy(openingPassingActionIds = ids))
    }

    /**
     * Apply scenario [AbstractTrainingScenario.drillStatus] after one submit.
     * Caller must only invoke when [MatchState.trainingEpisode] is set.
     */
    fun updatedTrainingEpisode(before: MatchState, after: MatchState, action: LegalAction): TrainingEpisodeConfig {
        val episode = requireNotNull(after.trainingEpisode) { "updatedTrainingEpisode requires trainingEpisode" }
        val script = implementation(episode.scenario)
        val graded =
            if (episode.drillStatus == TrainingDrillStatus.STILL_IN_PROGRESS && isTrainerOpeningTurn(before)) {
                episode.copy(drillStatus = script.drillStatus(before, after, action))
            } else {
                episode
            }
        return TrainingScenarioGrading.afterDrillStatusUpdate(script, after, graded)
    }

    /**
     * Build a scenario match and retry until [AbstractTrainingScenario.validateOpeningMenu] passes.
     * [difficulty] (0–5) caps extra scattered reserves after scripted pieces.
     */
    fun openScenario(
        blank: MatchState,
        trainerSide: TeamSide,
        scenario: TrainingScenarioEnum,
        difficulty: Int = 0,
        episodeMode: TrainingEpisodeMode = TrainingEpisodeMode.TRAIN,
        maxPlacementAttempts: Int = MAX_PLACEMENT_ATTEMPTS,
    ): MatchState {
        require(difficulty in 0..5) { "difficulty must be 0–5, was $difficulty" }
        require(maxPlacementAttempts in 1..MAX_PLACEMENT_ATTEMPTS) { "maxPlacementAttempts must be 1–$MAX_PLACEMENT_ATTEMPTS, was $maxPlacementAttempts" }
        require(blank.config.coachA == trainerSide) { "coachA must be $trainerSide before openScenario(pass coachA to createMatch), was ${blank.config.coachA}" }
        val script = implementation(scenario)
        var lastFailure: Exception? = null
        repeat(maxPlacementAttempts) {
            try {
                return tryPlacement(blank, script, trainerSide, scenario, difficulty, episodeMode)
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw IllegalStateException("Failed to build valid ${scenario.name} for $trainerSide after $maxPlacementAttempts placement attempts${lastFailure?.let { " (${it.message})" } ?: ""}", lastFailure)
    }

    private fun tryPlacement(
        blank: MatchState,
        script: AbstractTrainingScenario,
        trainerSide: TeamSide,
        scenario: TrainingScenarioEnum,
        difficulty: Int,
        episodeMode: TrainingEpisodeMode,
    ): MatchState {
        val setup = TrainingBoardBuilder(blank.board)
        script.buildBoard(setup, trainerSide, difficulty)
        val placed = blank.copy(
            board = setup.build(),
            trainingEpisode = TrainingEpisodeConfig(
                scenario = scenario,
                difficulty = difficulty,
                trainerSide = trainerSide,
                episodeMode = episodeMode,
                openingTurnNumber = blank.progress.turnNumber,
                starTurnStarId = setup.starTurnStarId,
            ),
            currentTurnState = TurnState(
                phase = TurnPhase.PLAYER_ACTION,
                step = PlayerActionStep.SEED,
                activeCoach = trainerSide,
            ),
        )
        var state = script.configureEpisode(placed, trainerSide)

        state = advanceToFirstTrainerDecision(state, script, trainerSide)
        // Claim: ball-in-play before the opening menu so keep-set / fillers see pickup geometry
        // (one forActingCoach — do not probe, place ball, then probe again).
        if (script is AbstractChallengeClaimTrainingScenario && state.board.balls.isEmpty()) {
            state = ensureBallInPlayForClaimEpisode(state)
        }
        // Player-action menu for this board. Claim drills use it as a *probe* (see branch below);
        // non-claim drills validate this menu as the real opening decision.
        val openingMoves = legalMovesGenerator.forActingCoach(state)
        require(state.currentTurnState.activeCoach == trainerSide) { "${script.scenario.name}: trainer $trainerSide must act first, was ${state.currentTurnState.activeCoach}" }
        require(openingMoves.isNotEmpty()) { "${script.scenario.name}: trainer has no legal moves on the first decision" }

        if (script is AbstractChallengeClaimTrainingScenario) {
            // Claim drills open on Pre-Turn ReplaceChallenge, but keep-set / bait selection and
            // "focus claims / fillers don't leak" checks need the *player-action* menu for this
            // board. Tableau titles do not change paths/marks/blocks — only claim annotations —
            // so we probe once here, then rewind pending to replace for the real episode start.
            //
            // 1) Probe while still on PLAYER_ACTION (openingMoves above).
            // 2) Assemble keep-set + baits from that probe (non-leaking = not claimable on these rows).
            // 3) Re-stamp claim hints onto the *same* rows with the new tableau; validateClaimGeometry
            //    asserts the lesson card is annotated and baits are not. If we waited until after
            //    parkForcedReplace, forActingCoach would return ReplaceChallenge discards — wrong
            //    menu for claim-geometry checks.
            // 4) parkForcedReplace rolls the decision surface back to Pre-Turn replace (the menu
            //    the agent actually sees first).
            // 5) Regenerate legals for that pending and validateBoard (bait discard vs keep-set).
            state = script.assembleReplaceKeepSet(state, dice, openingMoves)
            val claimLegals = ClaimFillerSelector.attachClaimHints(state, openingMoves)
            script.validateClaimGeometry(state, trainerSide, claimLegals)
            state = parkForcedReplace(state, trainerSide)
            val replaceLegals = legalMovesGenerator.forActingCoach(state)
            script.validateBoard(state, trainerSide, replaceLegals)
            return state
        } else {
            val legals = ClaimFillerSelector.attachClaimHints(state, openingMoves)
            script.validateBoard(state, trainerSide, legals)
            return freezeOpeningPassingIdsIfNeeded(state, legals)
        }
    }

    /**
     * Rewind claim setup from the player-action probe onto Pre-Turn [ReplaceChallenge].
     *
     * The episode must open on replace (forced keep-set lesson), but setup already probed the
     * PLAYER_ACTION menu to assemble baits and validate claim annotations. This copies that board /
     * tableau into a Pre-Turn pending so the first real decision is discard-a-bait.
     *
     * Sparse claim boards leave dugout ≥ real team ER, so the next Pre-Turn step would queue a free
     * Reserves that is not the lesson. Raise both ER thresholds above any roster dugout size so the
     * engine's `dugout < threshold` check never arms ER — scenario setup only, not a TurnEngine fork.
     *
     * TODO: retire [CLAIM_EPISODE_ER_NEVER] once claim boards keep dugout below real team ER
     * (see TRAINING_SCENARIOS.md). Ball-in-play for no-ball lessons is ordinary scenario geometry
     * ([ensureBallInPlayForClaimEpisode]), not part of this TODO.
     */
    private fun parkForcedReplace(state: MatchState, trainerSide: TeamSide): MatchState =
        state.copy(
            config = state.config.copy(
                homeEmergencyReserves = CLAIM_EPISODE_ER_NEVER,
                awayEmergencyReserves = CLAIM_EPISODE_ER_NEVER,
            ),
            currentTurnState = TurnState(
                phase = TurnPhase.PRE_TURN,
                step = PreTurnStep.REPLACE_CHALLENGE,
                activeCoach = trainerSide,
                pendingDecision = ReplaceChallenge(),
            ),
        )

    /**
     * No-ball claim lessons still open mid-game: put a loose ball on the pitch before Pre-Turn so
     * NEW_BALL does not emerge a trapdoor ball into the teaching lane. Prefer empty non-end-zone
     * squares **out of Run reach** of every on-pitch trainer who can hold the ball — otherwise
     * difficulty scatter + a far ball invents a pickup→TD line that outscores the lesson claim.
     */
    private fun ensureBallInPlayForClaimEpisode(state: MatchState): MatchState {
        val trainer = requireNotNull(state.trainingEpisode) { "ensureBallInPlayForClaimEpisode requires trainingEpisode" }.trainerSide
        val setup = TrainingBoardBuilder(state.board)
        val trainerCoords = state.board.players.values
            .filter { it.team == trainer && it.status != PlayerStatus.RESERVES }
            .map { state.board.findSquare(it.id).coordinate }
        val hunters = state.board.players.values.filter { it.team == trainer && it.status != PlayerStatus.RESERVES && setup.canHoldBall(it) }
        fun chebyshev(a: Coordinate, b: Coordinate): Int = maxOf(abs(a.x - b.x), abs(a.y - b.y))
        fun reachSquares(player: Player): Int =
            when (val move = player.profile.stats.move) {
                is MoveValue.Fixed -> move.squares
                MoveValue.RandomD6 -> 6
            }
        fun outOfTrainerPickupReach(sq: BoardSquare): Boolean =
            hunters.all { hunter ->
                chebyshev(state.board.findSquare(hunter.id).coordinate, sq.coordinate) > reachSquares(hunter)
            }
        val playable = state.board.allSquares()
            .filter { setup.emptyPlayable(it) && !it.isEndZone(TeamSide.HOME) && !it.isEndZone(TeamSide.AWAY) }
        val unreachable = playable.filter(::outOfTrainerPickupReach)
        val pool = unreachable.ifEmpty { playable }
        val square = pool.maxByOrNull { sq ->
            if (trainerCoords.isEmpty()) 0
            else trainerCoords.minOf { t -> abs(sq.coordinate.x - t.x) + abs(sq.coordinate.y - t.y) }
        } ?: return state
        setup.placeLooseBallAt(square)
        return state.copy(board = setup.build())
    }

    /** Run the turn engine until the trainer has a player-action decision (optional held-check prime). */
    private fun advanceToFirstTrainerDecision(
        state: MatchState,
        script: AbstractTrainingScenario,
        trainerSide: TeamSide,
    ): MatchState {
        fun run(): MatchState {
            val session = MatchMutator(state)
            turnEngine.advance(session)
            check(!session.matchOver && session.needsPlayerInput()) { "Scenario setup ended before first decision" }
            check(session.activeCoach == trainerSide) { "Scenario setup: trainer $trainerSide must act first, was ${session.activeCoach}" }
            val beforePrime = session.toMatchState()
            val primeAction = script.actionToPrime(beforePrime, trainerSide, legalMovesGenerator.forActingCoach(beforePrime))
            if (primeAction != null) {
                playerActionExecutor.takeAction(session, primeAction)
                turnEngine.advance(session)
                check(!session.matchOver && session.needsPlayerInput()) { "${script.scenario.name}: prime action did not park on a held-check decision" }
                check(session.activeCoach == trainerSide) { "${script.scenario.name}: trainer lost the turn while priming" }
            }
            return session.toMatchState()
        }
        val placementDice = script.placementDiceScript() ?: return run()
        val roller = ScriptedDiceRoller(fallbackToDefaultPattern = false)
        placementDice(roller)
        return diceRollerContext.withRoller(roller) { run() }
    }

    companion object {
        const val MAX_PLACEMENT_ATTEMPTS = 64

        /**
         * Above any team roster dugout size. Engine queues ER only when dugout ≥ threshold —
         * this value never arms. See [parkForcedReplace] TODO.
         */
        private const val CLAIM_EPISODE_ER_NEVER = 99
    }
}
