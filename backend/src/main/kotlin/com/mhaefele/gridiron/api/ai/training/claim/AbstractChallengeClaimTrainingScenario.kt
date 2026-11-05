package com.mhaefele.gridiron.api.ai.training.claim

import com.mhaefele.gridiron.api.ai.training.AbstractTrainingScenario
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.api.ai.training.TrainingScenarioFamily
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.shared.TeamSide
import kotlin.reflect.KClass

/**
 * Challenge-claim curriculum drill: pinned keep-set, random non-leaking baits, claims enabled on
 * turn [ChallengeClaimTableau.OPENING_TURN]. Episode opens on forced Pre-Turn replace — discard a
 * bait, never a keepable — then the usual claim soft-mask / PASS.
 *
 * Single-move drills should stay data-only: [scenario], [focusCondition], [expectedType],
 * optional [succeedsOnAttempt], and [buildClaimBoard]. Soft-mask defaults to [claimsFocus] —
 * override opening/follow-up only when the card annotates late (multi-move) or soft ≠ PASS.
 *
 * @param expectedType legal-action class the focus claim must ride on, or null when several types are OK.
 *   When an action is annotated for [focusCondition], [claimsFocus] also `check`s this type.
 */
abstract class AbstractChallengeClaimTrainingScenario(
    val expectedType: KClass<out LegalAction>?,
) : AbstractTrainingScenario() {
    override val family = TrainingScenarioFamily.CLAIM
    /** Keep-set assembly runs in [TrainingScenarios] via [assembleReplaceKeepSet] — not [fillOpenSlots]. */
    override val assignsRandomClaimFillers = false

    abstract val focusCondition: ChallengeClaimCondition

    /**
     * When true, trainer difficulty scatter keeps every on-pitch teammate non-adjacent
     * (needed for Fan Out / Lock Them Down / multi-move casts where adjacency leaks the claim).
     */
    protected open val isolateTrainerScatter: Boolean = false

    /**
     * Titles the coach must keep on the forced replace. Default: the focus card only.
     * Clean Sweep overrides with the full trio (bait is whatever is not in this set).
     */
    protected open fun keepConditionsForReplace(): Set<ChallengeClaimCondition> = setOf(focusCondition)

    /**
     * Tableau + deck-top bait layout before parking [ReplaceChallenge].
     *
     * [openingMoves] is the player-action probe from [TrainingScenarios] (board geometry only).
     * Baits are chosen so they do not claim on those rows; titles change, paths do not.
     */
    open fun assembleReplaceKeepSet(state: MatchState, dice: DiceRoller, openingMoves: List<LegalAction>): MatchState =
        ClaimFillerSelector.assembleFocusReplaceKeepSet(state, focusCondition, dice, openingMoves)

    /**
     * Assert focus / no-filler leaks on the player-action probe after keep-set assembly.
     *
     * Must run **before** [TrainingScenarios] parks [ReplaceChallenge]: after the rewind,
     * [LegalMovesGenerator] returns discard rows, not the annotated player actions this check needs.
     * Subclasses that need extra geometry checks override [validateClaimBoard].
     */
    fun validateClaimGeometry(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(state.progress.turnNumber == ChallengeClaimTableau.OPENING_TURN) { "${scenario.name}: expected turn ${ChallengeClaimTableau.OPENING_TURN}, was ${state.progress.turnNumber}" }
        requireActiveFocus(state)
        validateClaimBoard(state, trainerSide, legals)
    }

    /**
     * Opening validation for the menu the episode actually starts on.
     * After [TrainingScenarios] parks [ReplaceChallenge], that is discard rows
     * ([validateReplaceKeepSetOpening]). Non-claim / mid-episode paths may still hit
     * [validateClaimBoard] when [legals] are player actions.
     */
    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(state.progress.turnNumber == ChallengeClaimTableau.OPENING_TURN) { "${scenario.name}: expected turn ${ChallengeClaimTableau.OPENING_TURN}, was ${state.progress.turnNumber}" }
        if (legals.any { it is LegalAction.ReplaceChallenge }) {
            validateReplaceKeepSetOpening(state, legals)
            return
        }
        requireActiveFocus(state)
        validateClaimBoard(state, trainerSide, legals)
    }

    /**
     * Lesson placement first, then difficulty scatter.
     *
     * Trainer extras: keep Hold Ball players out of the lesson thrower's ruler range so scatter
     * cannot invent a Completion / Risky Throw leak. We do **not** also ban ball-less reserves from
     * reaching the scoring End Zone — that filter starved scatter candidates, and without the ball
     * an End Zone Run cannot score / claim Play to the Stands.
     *
     * Lesson carriers/throwers themselves are placed with [TrainingBoardBuilder.beyondMoveOf] in
     * [ClaimBoardPhrases] so the taught action is not competing with a Touchdown Run.
     *
     * Teaching-lane squares ([TrainingBoardBuilder.markOppositionScatterAvoid]) are merged into
     * **trainer** avoid as well — otherwise trainer extras stand on the ≥4 advance / TD path and
     * wipe the focus annotation (looked like random filler failure, was lane clobber).
     */
    final override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        buildClaimBoard(setup, trainerSide)
        val lessonAvoid = setup.scatterAvoidZones()
        // Lane-protect both teams: oppositionScatterAvoid holds exact teaching-path cells.
        val trainerAvoid = lessonAvoid + setup.oppositionScatterAvoidZones()
        setup.scatterRemainingReserves(
            trainerSide,
            maxPlayers = difficulty,
            avoid = trainerAvoid,
            playerFilter = ::trainerScatterEligible,
            squareFilter = { player ->
                { square -> setup.holdBallNotInThrowRange(player, square) }
            },
            isolateFromTeam = if (isolateTrainerScatter) trainerSide else null,
        )
        val postTrainerAvoid = lessonAvoid + setup.oppositionScatterAvoidZones() + setup.onPitchScatterAvoid(trainerSide)
        setup.scatterRemainingReserves(trainerSide.opposite(), maxPlayers = difficulty, avoid = postTrainerAvoid)
    }

    /**
     * Difficulty extras for the trainer. Override to keep ability leaks off the pitch
     * (e.g. Lobber ranged Blocks on a Foul-only Send Them Off drill).
     */
    protected open fun trainerScatterEligible(player: Player): Boolean = true

    protected abstract fun buildClaimBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide)

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState {
        val episode = requireTrainingEpisode(state)
        return state.copy(
            progress = state.progress.copy(turnNumber = ChallengeClaimTableau.OPENING_TURN),
            cards = ChallengeClaimTableau.withFocusOnly(focusCondition),
            trainingEpisode = episode.copy(openingTurnNumber = ChallengeClaimTableau.OPENING_TURN),
        )
    }

    protected fun validateReplaceKeepSetOpening(state: MatchState, legals: List<LegalAction>) {
        val replaces = legals.filterIsInstance<LegalAction.ReplaceChallenge>()
        require(replaces.size == 3) { "${scenario.name}: expected 3 ReplaceChallenge legals, was ${replaces.size}" }
        require(state.currentTurnState.pendingDecision is ReplaceChallenge) { "${scenario.name}: expected ReplaceChallenge pending" }
        val keep = keepConditionsForReplace()
        val titles = state.cards.activeChallengeClaims()
        require(titles.any { it in keep }) { "${scenario.name}: no keepable from $keep in tableau $titles" }
        val baitDiscards = replaces.filter { it.claimCondition !in keep }
        require(baitDiscards.isNotEmpty()) { "${scenario.name}: no bait discard among $titles (keep=$keep)" }
        require(baitDiscards.all { it.claimCondition !in keep }) { "${scenario.name}: bait discard leaked into keep-set" }
    }

    /**
     * When true (default), opening legals must include a [focusCondition] annotation.
     * Multi-move drills override to false (card claims only after several budgeted actions).
     */
    protected open val requireFocusClaimOnOpening: Boolean = true

    /** Focus (optional) + no filler leaks. Subclasses call `super` then add drill-specific requires. */
    protected open fun validateClaimBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        if (requireFocusClaimOnOpening) requireFocusClaimAnnotated(legals)
        requireNoFillerClaimAnnotated(legals)
    }

    override fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (state.currentTurnState.currentPlayerAction != null) return false
        if (isReplacePending(state)) return isSoftMaskReplaceDiscard(action, state)
        if (!focusStillActive(state)) return false
        return if (state.currentTurnState.actionsThisTurn.isEmpty()) {
            isSoftMaskOpeningMove(action, state, trainerSide)
        } else {
            isSoftMaskFollowUpMove(action, state, trainerSide)
        }
    }

    override fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (state.currentTurnState.currentPlayerAction != null) return false
        if (isReplacePending(state)) return false
        return if (state.currentTurnState.actionsThisTurn.isEmpty()) {
            isPassingOpeningMove(action, state, trainerSide)
        } else {
            isPassingFollowUpMove(action, state, trainerSide)
        }
    }

    /** Default: any opening legal already annotated for [focusCondition]. */
    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        claimsFocus(action)

    /**
     * Follow-up soft-mask after the first budgeted action.
     * Default none — one-shot claims still soft-mask opening-only; PASS follow-up reuses
     * [isPassingOpeningMove] via [AbstractTrainingScenario.isPassingFollowUpMove] when
     * [expectedBudgetedActions] == 1. Multi-move drills override.
     */
    override fun isSoftMaskFollowUpMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        false

    protected fun isReplacePending(state: MatchState): Boolean =
        state.currentTurnState.pendingDecision is ReplaceChallenge

    /** Soft-mask: discard any active that is not in [keepConditionsForReplace]. */
    protected fun isSoftMaskReplaceDiscard(action: LegalAction, state: MatchState): Boolean {
        if (action !is LegalAction.ReplaceChallenge) return false
        return action.claimCondition !in keepConditionsForReplace()
    }

    protected open fun requireActiveFocus(state: MatchState) {
        val titles = state.cards.activeChallengeClaims()
        require(focusCondition in titles) { "${scenario.name}: focus $focusCondition missing from tableau $titles" }
        require(titles.count { it == focusCondition } >= 1) { "${scenario.name}: focus $focusCondition must appear once, was $titles" }
        require(state.cards.activeSlots.none { it == null }) { "${scenario.name}: filler slots must be filled before validate, was ${state.cards.activeSlots}" }
    }

    protected open fun focusStillActive(state: MatchState): Boolean =
        focusCondition in state.cards.activeChallengeClaims()

    /**
     * Opening move tagged for [focusCondition] on the expected action class.
     * Wrong-type focus tags return false (soft-mask / PASS) — [requireFocusClaimAnnotated]
     * rejects those boards at placement so AI step never crashes mid-enumeration.
     */
    protected fun claimsFocus(action: LegalAction): Boolean {
        if (action.potentialChallengeClaims.none { it.claimCondition == focusCondition }) return false
        return expectedType?.isInstance(action) ?: true
    }

    protected fun requireFocusClaimAnnotated(legals: List<LegalAction>) {
        val focusTagged = legals.filter { action ->
            action.potentialChallengeClaims.any { it.claimCondition == focusCondition }
        }
        require(focusTagged.isNotEmpty()) { "${scenario.name}: no legal action is annotated for $focusCondition" }
        val expected = expectedType ?: return
        val wrong = focusTagged.filter { !expected.isInstance(it) }
        require(wrong.isEmpty()) { "${scenario.name}: focus $focusCondition on ${wrong.map { it::class.simpleName }}, expected ${expected.simpleName}: $wrong" }
    }

    protected fun requireNoFillerClaimAnnotated(legals: List<LegalAction>) {
        val keep = keepConditionsForReplace()
        val leaked = legals.flatMap { it.potentialChallengeClaims }
            .map { it.claimCondition }
            .filter { it !in keep }
            .toSet()
        require(leaked.isEmpty()) { "${scenario.name}: filler claim(s) $leaked annotated on the opening decision — pick different fillers" }
    }

    /**
     * When true, submitting the flagged expected move grades PASS even if dice fail to bank the
     * card (Throw Check / Block / Armour). Deterministic claims leave this false.
     */
    protected open val succeedsOnAttempt: Boolean = false

    override fun expectedOpeningTurnPoints(state: MatchState): Int = focusCondition.points

    /** PASS once [focusCondition] is banked, or on the flagged attempt when [succeedsOnAttempt]. */
    override fun drillStatus(before: MatchState, after: MatchState, action: LegalAction): TrainingDrillStatus {
        if (trainerHolds(after, focusCondition)) return TrainingDrillStatus.PASS
        if (succeedsOnAttempt && isPassingScenarioMove(action, before, trainerSide(before))) {
            return TrainingDrillStatus.PASS
        }
        return TrainingDrillStatus.STILL_IN_PROGRESS
    }

    protected fun trainerHolds(state: MatchState, condition: ChallengeClaimCondition): Boolean =
        state.cards.handCards(trainerSide(state)).any { it.challenge == condition }
}
