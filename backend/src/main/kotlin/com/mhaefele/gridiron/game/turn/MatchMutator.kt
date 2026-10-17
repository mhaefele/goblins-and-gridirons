package com.mhaefele.gridiron.game.turn

import com.mhaefele.gridiron.model.pending.PendingDecision
import com.mhaefele.gridiron.model.pending.PendingDecisionSlot
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.game.action.dto.ChallengeCardDto
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.DomainEvent.InputRequired.PlayerAction
import com.mhaefele.gridiron.game.action.dto.MatchEndKind
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.model.*
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.shared.*
import kotlin.collections.plus

/**
 * Mutable working state for one state-changing operation. Wraps the whole [MatchState]: a
 * [BoardMutator] for pitch beats plus the turn clock and match progress, all appending to one
 * ordered [events] log. Build once when an action begins; read [events] and [toMatchState] back
 * only after the operation finishes, then persist and publish.
 *
 * Turn-clock verbs are atomic (enter phase, bump turn number, swap coach). [TurnEngine] composes
 * them into phase recipes — callers never `copy(...)` a [TurnState] or [MatchProgress] themselves.
 *
 * ## Pending / open-action invariants (see also [PendingDecision])
 * 1. **One slot** — null ⇒ [setPendingDecision]; same class ⇒ [updatePendingDecision]. Selecting RPA
 *    parks a child with `copy(delegate = …)` on the RPA (no type-swap helper).
 *    Parked pending's [PendingDecision.phase] must match the current [TurnPhase].
 * 2. **Stage owns the pending** — park the type the current phase/step needs; no cross-phase funnels.
 * 3. **Clear takeAction owner per pending type** — one executor dispatch path; resolvers may be
 *    delegated to / nested (not a hard 1:1 PendingDecision↔Resolver).
 * 4. **One phase (ideally one step)** — each pending type is tied to exactly one [TurnPhase] and
 *    ideally one turn step.
 * 5. **[currentPlayerAction] only in [TurnPhase.PLAYER_ACTION]** — never begin that bracket elsewhere.
 * 6. **Finish = [PendingDecision.isFinished]** — COMPLETE / executor clear; no phase peeks
 *    to finish an “open action” outside PLAYER_ACTION.
 * 7. **Do not set crank steps here** as a side effect of begin* — steps belong on [TurnEngine].
 */
class MatchMutator(
    private val initial: MatchState,
    events: MutableList<DomainEvent> = mutableListOf(),
) {
    private val _events = events

    val board = BoardMutator(initial.board, _events, ::stampTurnClock)
    val events: List<DomainEvent> get() = _events

    val matchId: MatchId get() = initial.id
    val config: MatchConfig get() = initial.config
    val trainingEpisode: TrainingEpisodeConfig? get() = _trainingEpisode
    private var _trainingEpisode: TrainingEpisodeConfig? = initial.trainingEpisode
    private var _lastEventId: Int = initial.lastEventId
    var turn = initial.currentTurnState
    private var _progress = initial.progress
    private var cards = initial.cards

    val phase: TurnPhase get() = turn.phase
    val activeCoach: TeamSide get() = turn.activeCoach
    val actionsRemaining: Int get() = turn.actionsRemaining
    val progress: MatchProgress get() = _progress
    val turnNumber: Int get() = _progress.turnNumber
    val matchOver: Boolean get() = _progress.result != null
    val lastEventId: Int get() = _lastEventId
    val cardState: CardState get() = cards
    val pointsVictoryTurnsRemaining: Int? get() = _progress.pointsVictoryTurnsRemaining
    val bonusPlaysUsedThisTurn: Set<com.mhaefele.gridiron.model.catalog.BonusPlay>
        get() = turn.bonusPlaysUsedThisTurn

    /** Current coach prompt accessors — prefer these over peaking [TurnState.pendingDecision] on the mutator. */
    fun hasPendingDecision(): Boolean = turn.pendingDecision != null

    fun requirePendingDecision(): PendingDecision {
        val pending = checkNotNull(turn.pendingDecision) { "Expected a pending decision" }
        check(pending is PendingDecisionSlot.TopLevel) { "Expected TopLevel pending root, was ${pending::class.simpleName}" }
        return pending
    }

    inline fun <reified T : PendingDecision> pendingAs(): T {
        val pending = requirePendingDecision()
        check(pending is T) { "Expected ${T::class.simpleName}, was $pending" }
        return pending
    }

    inline fun <reified T : PendingDecision> pendingIs(): Boolean = turn.pendingDecision is T

    /** See [BoardMutator.stampTurnClock] — board mutator cannot read [turn]. */
    private fun stampTurnClock(event: DomainEvent) {
        event.turnPhase = turn.phase
        event.turnStep = turn.step
    }

    private fun record(event: DomainEvent) {
        stampTurnClock(event)
        _events.add(event)
    }

    fun setLastEventId(id: Int) { _lastEventId = id }

    fun replaceTrainingEpisode(episode: TrainingEpisodeConfig) { _trainingEpisode = episode }

    fun toMatchState(): MatchState {
        val turnToPersist = turn.currentPlayerAction?.let { current ->
            turn.copy(
                currentPlayerAction = current.copy(
                    eventsSinceStart = current.eventsSinceStart + currentBracketSlice(),
                ),
            )
        } ?: turn
        return initial.copy(
            trainingEpisode = _trainingEpisode,
            board = board.board,
            currentTurnState = turnToPersist,
            progress = _progress,
            cards = cards,
            lastEventId = _lastEventId,
        )
    }

    fun scoreFor(team: TeamSide): Score =
        when (team) {
            TeamSide.HOME -> progress.homeScore
            TeamSide.AWAY -> progress.awayScore
        }

    // --- Player action beats ---------------------------------------------------------------

    /** In-flight basic action for this beat ([TurnState.currentPlayerAction]). */
    val currentPlayerAction: OpenPlayerAction? get() = turn.currentPlayerAction

    fun requireCurrentPlayerAction(): OpenPlayerAction =
        checkNotNull(currentPlayerAction) { "Expected a current player action" }

    /** Index into [events] at [beginPlayerAction] — claim facts fold from this slice. */
    private var actionBracketStartIndex: Int = 0

    fun beginPlayerAction(playerId: PlayerId, actionType: ActionType, origin: OpenPlayerAction.Origin) {
        check(phase == TurnPhase.PLAYER_ACTION) { "currentPlayerAction is PLAYER_ACTION bookkeeping only, was $phase" }
        check(currentPlayerAction == null) { "Cannot begin $actionType while $currentPlayerAction is still current" }
        actionBracketStartIndex = _events.size
        val held = heldBallsByTeam(board.board)
        var nextTurn = turn.copy(
            currentPlayerAction = OpenPlayerAction(
                playerId = playerId,
                actionType = actionType,
                origin = origin,
                ballsHeldAtActionStart = held,
            ),
        )
        // Budget slot spent only when the coach actually begins the action — not when QUEUE_NEXT parks RPA.
        if (origin == OpenPlayerAction.Origin.BUDGETED) {
            check(nextTurn.regularActionSlotsRemaining > 0) { "No regular action slot left to begin $actionType" }
            nextTurn = nextTurn.copy(regularActionSlotsRemaining = nextTurn.regularActionSlotsRemaining - 1)
        }
        turn = nextTurn
        board.actionStarted(playerId, actionType)
    }

    /**
     * PRE_TURN / PIECE_PLACEMENT one-shot: ActionStarted → [body] → ActionComplete + free turn log.
     * Does **not** open [currentPlayerAction] (that bracket is PLAYER_ACTION only).
     */
    // TODO(pending): drop the lambda nest — caller should make two explicit calls (start/complete),
    // not AOP-style wrap. Also assumes a one-shot body; out-of-phase moves need a proper turn-crank
    // apparatus, not a helper that pretends the whole move is one nested block.
    fun runStandalonePlayerMove(playerId: PlayerId, actionType: ActionType, body: () -> Unit) {
        check(phase != TurnPhase.PLAYER_ACTION) { "runStandalonePlayerMove is for phases outside PLAYER_ACTION, was $phase" }
        check(currentPlayerAction == null) { "Standalone move while $currentPlayerAction is still current" }
        board.actionStarted(playerId, actionType)
        body()
        board.actionComplete(playerId, actionType)
        recordTurnAction(playerId, actionType, isFree = true)
    }

    /** Back on Your Feet / Reserves convert-in-place: mark the current action free (slot already returned at offer). */
    fun markCurrentPlayerActionFree() {
        val current = requireCurrentPlayerAction()
        check(current.origin == OpenPlayerAction.Origin.BUDGETED) { "Only a budgeted action can convert to free, was $current" }
        turn = turn.copy(currentPlayerAction = current.copy(origin = OpenPlayerAction.Origin.FREE))
    }

    /** Events for the current action bracket (prior HTTP resumes + this request). */
    fun actionBracketEvents(): List<DomainEvent> {
        val current = currentPlayerAction ?: return emptyList()
        return current.eventsSinceStart + currentBracketSlice()
    }

    fun ballsHeldAtActionStart(): Map<TeamSide, Map<BallId, Coordinate>> =
        currentPlayerAction?.ballsHeldAtActionStart ?: emptyMap()

    /**
     * Board [ActionComplete] + flush bracket events onto [currentPlayerAction].
     * Keeps the field for claim / Thirst — clear with [clearCurrentPlayerAction].
     */
    fun resolveCurrentPlayerAction(): OpenPlayerAction {
        val current = requireCurrentPlayerAction()
        board.actionComplete(current.playerId, current.actionType)
        val resolved = current.copy(eventsSinceStart = current.eventsSinceStart + currentBracketSlice())
        turn = turn.copy(currentPlayerAction = resolved)
        actionBracketStartIndex = _events.size
        return resolved
    }

    /** Drop the current action after the turn-slot beat (or abort). */
    fun clearCurrentPlayerAction() { turn = turn.copy(currentPlayerAction = null) }

    /**
     * Cancel an in-flight select (e.g. Throw Team-mate): pair ActionComplete, clear current.
     * Caller clears the *Continuation on the pending; TurnEngine.reconcile returns the crank to
     * SELECT / OFFER_DANCE_STEP / OFFER_EXTRA_EFFORT from that pending root.
     */
    fun abortCurrentPlayerAction() {
        val current = requireCurrentPlayerAction()
        board.actionComplete(current.playerId, current.actionType)
        turn = turn.copy(currentPlayerAction = null)
    }

    private fun currentBracketSlice(): List<DomainEvent> =
        _events.subList(actionBracketStartIndex, _events.size).toList()

    fun recordPlayerActionConsumed() { record(DomainEvent.TeamActionConsumed(actionsRemaining = turn.actionsRemaining)) }

    /** Append one resolved action to the ordered turn log (same-action-once + Charge sequence). */
    fun recordTurnAction(playerId: PlayerId, actionType: ActionType, isFree: Boolean = false) { turn = turn.copy(actionsThisTurn = turn.actionsThisTurn + TurnAction(playerId, actionType, isFree)) }

    val actionsThisTurn: List<TurnAction> get() = turn.actionsThisTurn

    /** A block/foul that ends the player's turn (Tackle, Miss, or Foul Slipped!) — no more actions for them this turn. */
    fun endPlayerTurn(playerId: PlayerId) {
        turn = turn.copy(turnEndedPlayers = turn.turnEndedPlayers + playerId)
        record(DomainEvent.PlayerTurnEnded(playerId))
    }

    fun scoreTouchdown(scorerId: PlayerId) {
        val scorer = board.getPlayer(scorerId)
        val ballId = scorer.heldBallId ?: error("Touchdown scorer ${scorerId.value} holds no ball")
        val at = board.playerCoordinate(scorerId)
        val team = scorer.team

        // Score on the square first (scorer still holding the ball), then clear the pitch —
        // stream order is the client's animation contract (celebrate → ball out → dugout).
        _progress = _progress.addTouchdown(team)
        turn = clearActionsUsedForPlayer(turn, scorerId)
        record(DomainEvent.TouchdownScored(scorerId, team, at, progress.homeScore.points, progress.awayScore.points))
        board.ballRemovedFromPlay(ballId)
        board.playerRemovedAfterTouchdown(scorerId)
    }

    /**
     * Stop the turn crank for HTTP when the parked pending needs a coach choice.
     *
     * Continuations: parked ⇒ [Ongoing*.stage.awaitsInput] (crank already ran to the park).
     * Null pending ⇒ keep cranking (e.g. open action DONE → COMPLETE).
     * Everything else (RegularPlayerAction, offers, …) ⇒ needs input.
     */
    fun needsPlayerInput(): Boolean {
        // TODO(pending): null ⇒ false is the crank's "keep going" signal — fine, but callers that
        // *expect* a pending should use requirePendingDecision(), not this.
        val pending = turn.pendingDecision ?: return false
        return pending.awaitsCoachInput()
    }

    fun cueNextPlayerDecision() {
        check(needsPlayerInput()) { "cueNextPlayerDecision requires a pending that awaits coach input, was ${turn.pendingDecision}" }
        val pending = requirePendingDecision()
        val coach = pending.respondingCoach ?: activeCoach
        record(PlayerAction(coach, pending.playDecisionType, ability = pending.promptAbility(), playerId = pending.promptPlayerId(), bonusPlay = pending.promptBonusPlay()))
    }

    /** Park [pendingDecision] into an empty slot — root must be [PendingDecisionSlot.TopLevel]. */
    fun <T> setPendingDecision(pendingDecision: T) where T : PendingDecision, T : PendingDecisionSlot.TopLevel {
        check(turn.pendingDecision == null) { "setPendingDecision expects no pending, was ${turn.pendingDecision}" }
        check(pendingDecision.phase == phase) { "setPendingDecision ${pendingDecision::class.simpleName} expects phase ${pendingDecision.phase}, was $phase" }
        turn = turn.copy(pendingDecision = pendingDecision)
    }

    /**
     * Replace the parked pending in place — **same Kotlin class** only (continuation stage /
     * one-step `resolved`, PreTurnAbility advance, RPA `copy(delegate = …)`). Root stays TopLevel.
     */
    fun <T> updatePendingDecision(pendingDecision: T) where T : PendingDecision, T : PendingDecisionSlot.TopLevel {
        val current = requirePendingDecision()
        check(current::class == pendingDecision::class) { "updatePendingDecision expects ${current::class.simpleName}, was ${pendingDecision::class.simpleName}" }
        check(pendingDecision.phase == phase) { "updatePendingDecision ${pendingDecision::class.simpleName} expects phase ${pendingDecision.phase}, was $phase" }
        turn = turn.copy(pendingDecision = pendingDecision)
    }

    /** Remove a finished pending ([PendingDecision.isFinished]). */
    fun completePendingDecision() {
        val pending = requirePendingDecision()
        check(pending.isFinished()) { "completePendingDecision expects a finished pending, was $pending" }
        turn = turn.copy(pendingDecision = null)
    }

    /** IN_ACTION / COMPLETE: require a finished open-action continuation. */
    fun requireFinishedOpenActionPending(): PendingDecision {
        val pending = requirePendingDecision()
        check(pending.awaitsOpenActionComplete()) { "Expected a finished open-action pending, was $pending" }
        return pending
    }

    /** Test / claim-prep helper: clear whatever is parked (no completeness check). */
    // TODO(pending): clearPendingDecision with no completeness check — only tests/claim-prep should
    // need this; production paths should completeFinished / never wipe arbitrarily.
    fun clearPendingDecision() { turn = turn.copy(pendingDecision = null) }

    // --- Atomic turn / progress beats ------------------------------------------------------

    /** Enter [phase] on [step] and emit [DomainEvent.PhaseStarted] with the current clock snapshot. */
    fun enterPhase(phase: TurnPhase, step: PhaseStep? = null) {
        turn = turn.copy(phase = phase, step = step)
        record(DomainEvent.PhaseStarted(phase = phase, turnNumber = progress.turnNumber, activeCoach = turn.activeCoach, actionsRemaining = if (phase == TurnPhase.PLAYER_ACTION) turn.actionsRemaining.takeIf { it > 0 } ?: 3 else null))
    }

    fun setStep(step: PhaseStep) { turn = turn.copy(step = step) }

    val preTurnStep: PreTurnStep get() = requireStep(TurnPhase.PRE_TURN)
    val piecePlacementStep: PiecePlacementStep get() = requireStep(TurnPhase.PIECE_PLACEMENT)
    val turnStartStep: TurnStartStep get() = requireStep(TurnPhase.TURN_START)
    val turnEndStep: TurnEndStep get() = requireStep(TurnPhase.TURN_END)
    val playerActionStep: PlayerActionStep get() = requireStep(TurnPhase.PLAYER_ACTION)

    private inline fun <reified T : PhaseStep> requireStep(expected: TurnPhase): T {
        check(phase == expected) { "$expected step requested during $phase" }
        val step = turn.step
        check(step is T) { "$expected requires a ${T::class.simpleName}, was $step" }
        return step
    }

    fun incrementTurnNumber() { _progress = _progress.copy(turnNumber = progress.turnNumber + 1) }

    fun swapActiveCoach() { turn = turn.copy(activeCoach = turn.activeCoach.opposite()) }

    fun clearPerTurnTracking() { turn = turn.copy(actionsThisTurn = emptyList(), turnEndedPlayers = emptySet(), currentPlayerAction = null, bonusPlaysUsedThisTurn = emptySet(), bonusModifiers = TurnBonusModifiers(), regularActionSlotsRemaining = 0) }

    fun updateBonusModifiers(transform: (TurnBonusModifiers) -> TurnBonusModifiers) { turn = turn.copy(bonusModifiers = transform(turn.bonusModifiers)) }

    val bonusModifiers: TurnBonusModifiers get() = turn.bonusModifiers

    /**
     * Arm the three-action budget for this coach turn. Pending must already be null;
     * TurnEngine QUEUE_NEXT parks one [RegularPlayerAction] without decrementing slots.
     */
    fun seedRegularActionBudget() {
        check(!hasPendingDecision()) { "seedRegularActionBudget expects no pending, was ${turn.pendingDecision}" }
        turn = turn.copy(regularActionSlotsRemaining = 3)
    }

    val regularActionSlotsRemaining: Int get() = turn.regularActionSlotsRemaining

    /** Undo a begun budgeted slot (Back on Your Feet / Reserves offer holds the slot; Throw Team-mate cancel). */
    fun returnRegularActionSlot() { turn = turn.copy(regularActionSlotsRemaining = turn.regularActionSlotsRemaining + 1) }

    /** Re-take a held slot when declining Back on Your Feet / Reserves convert (budgeted resolve). */
    fun consumeRegularActionSlot() {
        check(turn.regularActionSlotsRemaining > 0) { "No regular action slot left to consume" }
        turn = turn.copy(regularActionSlotsRemaining = turn.regularActionSlotsRemaining - 1)
    }

    fun setMatchResult(result: MatchResult) { _progress = _progress.copy(result = result) }

    fun setPointsVictoryTurnsRemaining(remaining: Int) {
        require(remaining >= 0) { "pointsVictoryTurnsRemaining must be ≥ 0, was $remaining" }
        _progress = _progress.copy(pointsVictoryTurnsRemaining = remaining)
        record(DomainEvent.PointsVictoryCountdown(turnsRemaining = remaining))
    }

    fun recordMatchEnded() {
        val result = checkNotNull(progress.result) { "recordMatchEnded requires a MatchResult" }
        val endKind = when (result) {
            is MatchResult.SuddenDeath   -> MatchEndKind.suddenDeath
            is MatchResult.PointsVictory -> MatchEndKind.pointsVictory
        }
        val winner = when (result) {
            is MatchResult.SuddenDeath   -> result.winner
            is MatchResult.PointsVictory -> result.winner
        }
        record(DomainEvent.MatchEnded(turnNumber = progress.turnNumber, phase = turn.phase, activeCoach = turn.activeCoach, endKind = endKind, winner = winner))
    }

    /**
     * Arm Points Victory once: last challenge card just left the deck into an active slot.
     * Each coach then gets one more action window (including the turn that just refreshed).
     */
    fun maybeArmPointsVictoryAfterDeal() {
        if (progress.pointsVictoryTurnsRemaining != null) return
        if (cards.deck.isNotEmpty()) return
        _progress = _progress.copy(pointsVictoryTurnsRemaining = 2)
        record(DomainEvent.PointsVictoryArmed(turnsRemaining = 2))
    }

    // --- Challenge cards (D1) ----------------------------------------------------------------

    fun snapshotBallsPossessedAtTurnStart(team: TeamSide) { cards = cards.copy(ballsPossessedAtTurnStart = possessionByTeam(board.board)[team].orEmpty()) }

    /** Fill empty active slots from the deck top (Pre-Turn Refresh). */
    fun refreshChallengeTableau() {
        val filledSlots = mutableListOf<Int>()
        val drawn = mutableListOf<ChallengeCardDefinition>()
        val slots = cards.activeSlots.toMutableList()
        var deck = cards.deck
        for (i in slots.indices) {
            if (slots[i] == null && deck.isNotEmpty()) {
                slots[i] = deck.first()
                deck = deck.drop(1)
                filledSlots += i
                drawn += slots[i]!!
            }
        }
        if (filledSlots.isEmpty()) return
        cards = cards.copy(activeSlots = slots, deck = deck)
        record(DomainEvent.ChallengeTableauRefreshed(filledSlots = filledSlots, filledCards = drawn.map { ChallengeCardDto(it.challenge) }, deckTop = cards.deckTop?.let { ChallengeCardDto(it.challenge) }, deckRemaining = cards.deck.size))
        maybeArmPointsVictoryAfterDeal()
    }

    /**
     * When all three actives are filled, discard [slotIndex] and draw a replacement.
     * Coach picks [slotIndex] via [ReplaceChallenge] (D1.B.choice).
     */
    fun replaceChallengeCard(slotIndex: Int) {
        require(slotIndex in 0..2) { "Challenge slot index must be 0–2, was $slotIndex" }
        val discarded = cards.activeSlots[slotIndex] ?: error("Cannot replace empty challenge slot $slotIndex")
        val slots = cards.activeSlots.toMutableList()
        var deck = cards.deck
        val drawn = deck.firstOrNull()
        deck = if (drawn != null) deck.drop(1) else deck
        slots[slotIndex] = drawn
        cards = cards.copy(
            activeSlots = slots,
            deck = deck,
            discard = cards.discard + discarded,
        )
        record(DomainEvent.ChallengeCardReplaced(slotIndex = slotIndex, discardedClaimCondition = discarded.challenge, drawn = drawn?.let { ChallengeCardDto(it.challenge) }, deckTop = cards.deckTop?.let { ChallengeCardDto(it.challenge) }, deckRemaining = cards.deck.size))
        // Only arm when a card actually left the deck into the slot (not an empty replace).
        if (drawn != null) { maybeArmPointsVictoryAfterDeal() }
    }

    /** Claim an active card into [team]'s hand; leave the slot empty until Refresh. */
    fun claimChallengeCard(team: TeamSide, slotIndex: Int): ChallengeCardDefinition {
        require(slotIndex in 0..2) { "Challenge slot index must be 0–2, was $slotIndex" }
        val card = cards.activeSlots[slotIndex] ?: error("Cannot claim empty challenge slot $slotIndex")
        val slots = cards.activeSlots.toMutableList()
        slots[slotIndex] = null
        val hand = cards.handCards(team) + card
        cards = when (team) {
            TeamSide.HOME -> cards.copy(
                activeSlots = slots,
                homeHand = hand,
                claimsThisTurn = cards.claimsThisTurn + 1,
            )
            TeamSide.AWAY -> cards.copy(
                activeSlots = slots,
                awayHand = hand,
                claimsThisTurn = cards.claimsThisTurn + 1,
            )
        }
        _progress = _progress.recordChallengeCardClaim(team, card.points)
        record(DomainEvent.ChallengeCardClaimed(team = team, slotIndex = slotIndex, claimCondition = card.challenge, challengeTitle = card.challenge.displayName, pointsAwarded = card.points, homeScore = progress.homeScore.points, awayScore = progress.awayScore.points, claimsThisTurn = cards.claimsThisTurn))
        return card
    }

    fun awardCleanSweep(team: TeamSide) {
        _progress = _progress.addPoints(team, 2)
        record(DomainEvent.CleanSweep(team = team, pointsAwarded = 2, homeScore = progress.homeScore.points, awayScore = progress.awayScore.points))
    }

    /** Discard oldest hand cards until size ≤ 3. Returns discarded cards. */
    fun trimChallengeHand(team: TeamSide, maxHand: Int = 3): List<ChallengeCardDefinition> {
        val hand = cards.handCards(team)
        if (hand.size <= maxHand) return emptyList()
        val discarded = hand.dropLast(maxHand)
        val kept = hand.takeLast(maxHand)
        cards = when (team) {
            TeamSide.HOME -> cards.copy(homeHand = kept, discard = cards.discard + discarded)
            TeamSide.AWAY -> cards.copy(awayHand = kept, discard = cards.discard + discarded)
        }
        record(DomainEvent.ChallengeHandTrimmed(team = team, discardedBonusPlays = discarded.map { it.bonusPlay }, handSize = kept.size))
        return discarded
    }

    fun resetClaimsThisTurn() { cards = cards.copy(claimsThisTurn = 0, ballsPossessedAtTurnStart = emptySet()) }

    /**
     * Play the first hand card matching [bonus] for [team]. Coaches choose a bonus type;
     * when duplicates exist, the first matching hand card is used.
     * @return the card that was played (for refund-on-Skip grants).
     */
    fun playBonusCard(team: TeamSide, bonus: BonusPlay): ChallengeCardDefinition {
        val card = cards.handCard(team, bonus) ?: error("No $bonus card in ${team}'s hand")
        check(bonus !in turn.bonusPlaysUsedThisTurn) { "Bonus type $bonus already played this turn" }
        val newHand = cards.handCards(team) - card
        cards = when (team) {
            TeamSide.HOME -> cards.copy(homeHand = newHand, discard = cards.discard + card)
            TeamSide.AWAY -> cards.copy(awayHand = newHand, discard = cards.discard + card)
        }
        turn = turn.copy(bonusPlaysUsedThisTurn = turn.bonusPlaysUsedThisTurn + bonus)
        record(DomainEvent.BonusPlayed(team = team, bonusPlay = bonus))
        return card
    }

    /**
     * Undo [playBonusCard] when a free grant from that card is skipped (card unused).
     * Moves [card] from discard back to hand and clears the type from [bonusPlaysUsedThisTurn].
     */
    fun returnBonusCard(team: TeamSide, card: ChallengeCardDefinition) {
        check(card in cards.discard) { "Card ${card.id.value} is not in discard" }
        val newDiscard = cards.discard - card
        val newHand = cards.handCards(team) + card
        cards = when (team) {
            TeamSide.HOME -> cards.copy(homeHand = newHand, discard = newDiscard)
            TeamSide.AWAY -> cards.copy(awayHand = newHand, discard = newDiscard)
        }
        turn = turn.copy(bonusPlaysUsedThisTurn = turn.bonusPlaysUsedThisTurn - card.bonusPlay)
        record(
            DomainEvent.BonusReturned(
                team = team,
                bonusPlay = card.bonusPlay,
            ),
        )
    }
}

private fun possessionByTeam(board: Board): Map<TeamSide, Set<BallId>> =
    board.players.values
        .mapNotNull { player -> player.heldBallId?.let { player.team to it } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, ids) -> ids.toSet() }

private fun heldBallsByTeam(board: Board): Map<TeamSide, Map<BallId, Coordinate>> =
    board.players.values
        .mapNotNull { player ->
            val ballId = player.heldBallId ?: return@mapNotNull null
            // Pitch coordinates only — dugout holders are illegal and must not call findSquare.
            if (player.status == PlayerStatus.RESERVES) return@mapNotNull null
            Triple(player.team, ballId, board.findSquare(player.id).coordinate)
        }
        .groupBy({ it.first }, { it.second to it.third })
        .mapValues { (_, balls) -> balls.toMap() }
