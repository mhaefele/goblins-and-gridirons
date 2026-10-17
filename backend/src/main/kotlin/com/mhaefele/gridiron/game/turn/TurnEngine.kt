package com.mhaefele.gridiron.game.turn

import com.mhaefele.gridiron.game.action.Bounce
import com.mhaefele.gridiron.game.action.NewBall
import com.mhaefele.gridiron.game.action.ability.FreeFollowUpAbilityHook
import com.mhaefele.gridiron.game.action.pending.emergencyreserves.EmergencyReservesResolver
import com.mhaefele.gridiron.game.action.pending.initialplacement.InitialPlayerPlacementResolver
import com.mhaefele.gridiron.game.action.pending.gangup.OfferGangUpResolver
import com.mhaefele.gridiron.game.action.pending.holdtheline.OfferHoldTheLineResolver
import com.mhaefele.gridiron.game.action.pending.extraeffort.OfferExtraEffortResolver
import com.mhaefele.gridiron.game.action.pending.dancestep.OfferDanceStepResolver
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionResolver
import com.mhaefele.gridiron.game.action.pending.replace.ReplaceChallengeResolver
import com.mhaefele.gridiron.game.action.scoreTouchdownIfPossible
import com.mhaefele.gridiron.game.cards.Thirst
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.cards.ChallengeTurnSteps
import com.mhaefele.gridiron.game.legalmoves.reservesPlacements
import com.mhaefele.gridiron.model.MatchResult
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.pending.PendingDecision
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.pending.OfferDanceStep
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.shared.PiecePlacementStep
import com.mhaefele.gridiron.shared.PlayerActionStep
import com.mhaefele.gridiron.shared.PreTurnStep
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnEndStep
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.shared.TurnStartStep
import org.springframework.stereotype.Component

/**
 * The turn crank: mutate a caller-owned [MatchMutator] until player input is required or the
 * match is over. Isolates callers from copy-on-write; every beat lands on the session including
 * [DomainEvent.InputRequired] via [MatchMutator.cueNextPlayerDecision].
 *
 * Action resolution lives on [com.mhaefele.gridiron.game.action.PlayerActionExecutor] — callers run
 * takeAction then [advance].
 *
 * ## Pending / open-action invariants (see also [PendingDecision])
 * 1. **Stage owns the pending** — each phase/step parks the pending *it* needs ([setPendingDecision]
 *    into an empty slot); do not share one pending type across unrelated steps.
 * 2. **One pending type ⇒ one phase (ideally one step)** — clear takeAction owner per type; resolvers
 *    may be delegated to / nested.
 * 3. Empty slot ⇒ set; selecting RPA parks children via `RegularPlayerAction.copy(delegate = …)`.
 * 4. [MatchMutator.currentPlayerAction] only while in [TurnPhase.PLAYER_ACTION]; COMPLETE clears
 *    finished open-action roots (RPA + *Continuation delegate).
 * 5. Crank steps advance here — resolvers may [MatchMutator.beginPlayerAction] and nest a
 *    *Continuation during takeAction; [reconcilePlayerActionStep] aligns SELECT / OFFER_* ↔
 *    IN_ACTION from pending shape before the loop (see that method's KDoc).
 */
@Component
class TurnEngine(
    private val newBall: NewBall,
    private val freeFollowUpAbilityHook: FreeFollowUpAbilityHook,
    private val bounce: Bounce,
    private val regularPlayerActionResolver: RegularPlayerActionResolver,
    private val initialPlayerPlacementResolver: InitialPlayerPlacementResolver,
    private val emergencyReservesResolver: EmergencyReservesResolver,
    private val offerGangUpResolver: OfferGangUpResolver,
    private val offerHoldTheLineResolver: OfferHoldTheLineResolver,
    private val offerExtraEffortResolver: OfferExtraEffortResolver,
    private val offerDanceStepResolver: OfferDanceStepResolver,
    private val replaceChallengeResolver: ReplaceChallengeResolver,
) {
    /**
     * Turn the phase crank until player input is required or the match is over.
     * Emits [DomainEvent.InputRequired] (or game-over beats) when it stops.
     */
    fun advance(session: MatchMutator) {
        reconcilePlayerActionStep(session)
        while (!session.needsPlayerInput() && !session.matchOver) {
            when (session.phase) {
                TurnPhase.GAME_START      -> session.enterPhase(TurnPhase.PIECE_PLACEMENT, PiecePlacementStep.PLACE_COACH_A)
                TurnPhase.PIECE_PLACEMENT -> when (session.piecePlacementStep) {
                    PiecePlacementStep.PLACE_COACH_A      -> session.setStep(PiecePlacementStep.QUEUE_NEXT_COACH_A)
                    PiecePlacementStep.QUEUE_NEXT_COACH_A -> if(!queueNextInitialPlacement(session)) session.setStep(PiecePlacementStep.PLACE_COACH_B)
                    PiecePlacementStep.PLACE_COACH_B      -> {
                        session.swapActiveCoach()
                        session.setStep(PiecePlacementStep.QUEUE_NEXT_COACH_B)
                    }
                    PiecePlacementStep.QUEUE_NEXT_COACH_B -> if(!queueNextInitialPlacement(session)) session.setStep(PiecePlacementStep.KICKOFF)
                    PiecePlacementStep.KICKOFF            -> {
                        newBall.placeKickoffBall(session.board)
                        handOffToIncomingCoach(session)
                        // Coach A's first activation skips Turn Start / Pre-Turn (digest § Turn sequence).
                        session.enterPhase(TurnPhase.PLAYER_ACTION, PlayerActionStep.SEED)
                    }
                }
                TurnPhase.TURN_START      -> when (session.turnStartStep) {
                    TurnStartStep.SUDDEN_DEATH        -> {
                        maybeEndBySuddenDeath(session)
                        session.setStep(TurnStartStep.GANG_UP)
                    }
                    TurnStartStep.GANG_UP       -> {
                        if (BonusOffers.canOfferGangUp(session)) {
                            session.setPendingDecision(offerGangUpResolver.begin(session))
                        }
                        // Advance step even when an offer is parked — crank pauses on needsPlayerInput;
                        // AFTER_GANG_UP runs only once the window is clear (no re-offer).
                        session.setStep(TurnStartStep.AFTER_GANG_UP)
                    }
                    TurnStartStep.AFTER_GANG_UP -> {
                        // Coach B's first activation still gets Gang Up, then skips Pre-Turn.
                        if (isFirstActivationForActiveCoach(session)) {
                            session.enterPhase(TurnPhase.PLAYER_ACTION, PlayerActionStep.SEED)
                        } else {
                            session.enterPhase(TurnPhase.PRE_TURN, PreTurnStep.TOUCHDOWN)
                        }
                    }
                }
                TurnPhase.PRE_TURN        -> when (session.preTurnStep) {
                    PreTurnStep.TOUCHDOWN          -> {
                        scoreTouchdownIfPossible(session)
                        session.setStep(PreTurnStep.CHALLENGE_REFRESH)
                    }
                    PreTurnStep.CHALLENGE_REFRESH  -> {
                        if (ChallengeTurnSteps.refreshAndMaybeOfferReplace(session)) {
                            session.setPendingDecision(replaceChallengeResolver.begin())
                        }
                        session.setStep(PreTurnStep.REPLACE_CHALLENGE)
                    }
                    PreTurnStep.REPLACE_CHALLENGE  -> session.setStep(PreTurnStep.NEW_BALL)
                    PreTurnStep.NEW_BALL           -> {
                        if (session.board.board.balls.isEmpty()) {
                            newBall.emergeAtTrapdoor(session.board)
                        }
                        session.setStep(PreTurnStep.PLAYER_ABILITIES)
                    }
                    PreTurnStep.PLAYER_ABILITIES   -> {
                        // TODO(pending): set-if-present then always advance step — if a grant is parked,
                        // EMERGENCY_RESERVES still runs with a pending already set (setPendingDecision
                        // would blow up). Workflow relies on skip/accept clearing before ER queues.
                        freeFollowUpAbilityHook.pendingAtPreTurn(session.toMatchState(), session.activeCoach)
                            ?.let { session.setPendingDecision(it) }
                        session.setStep(PreTurnStep.EMERGENCY_RESERVES)
                    }
                    PreTurnStep.EMERGENCY_RESERVES -> {
                        queueEmergencyReserves(session)
                        session.setStep(PreTurnStep.BONUS_CARDS)
                    }
                    PreTurnStep.BONUS_CARDS        -> {
                        if (BonusOffers.canOfferHoldTheLine(session)) {
                            session.setPendingDecision(offerHoldTheLineResolver.begin(session))
                        }
                        session.setStep(PreTurnStep.AFTER_BONUS_CARDS)
                    }
                    PreTurnStep.AFTER_BONUS_CARDS  -> { session.enterPhase(TurnPhase.PLAYER_ACTION, PlayerActionStep.SEED) }
                }
                TurnPhase.PLAYER_ACTION   -> when (session.playerActionStep) {
                    PlayerActionStep.SEED       -> {
                        session.seedRegularActionBudget()
                        ChallengeTurnSteps.openActionWindow(session)
                        session.setStep(PlayerActionStep.QUEUE_NEXT)
                    }
                    PlayerActionStep.QUEUE_NEXT -> {
                        if (session.regularActionSlotsRemaining <= 0) {
                            session.setStep(PlayerActionStep.AFTER_THIRD)
                        } else {
                            // TODO(pending): defensive !hasPendingDecision before set — QUEUE_NEXT
                            // should already know the slot is empty (caller invariant, not a peek).
                            check(!session.hasPendingDecision()) { "QUEUE_NEXT expects no pending before inserting RegularPlayerAction" }
                            session.setPendingDecision(regularPlayerActionResolver.begin(session))
                            session.setStep(PlayerActionStep.SELECT)
                        }
                    }
                    // Waiting for a turn-slot pick pauses via needsPlayerInput (bare RegularPlayerAction).
                    // Reconcile already moved SELECT → IN_ACTION once RPA nested a *Continuation;
                    // empty slot returns to QUEUE_NEXT.
                    PlayerActionStep.SELECT    -> {
                        check(!session.hasPendingDecision() || session.pendingAs<RegularPlayerAction>().delegate == null) { "SELECT with RegularPlayerAction.delegate set — reconcile should have entered IN_ACTION" }
                        session.setStep(PlayerActionStep.QUEUE_NEXT)
                    }
                    // Finished continuation parked at DONE → COMPLETE pops it.
                    PlayerActionStep.IN_ACTION -> {
                        check(session.currentPlayerAction != null) { "IN_ACTION requires a current player action" }
                        session.requireFinishedOpenActionPending()
                        session.setStep(PlayerActionStep.COMPLETE)
                    }
                    PlayerActionStep.COMPLETE  -> {
                        session.requireFinishedOpenActionPending()
                        resolveOpenPlayerAction(session)
                        session.completePendingDecision()
                        session.setStep(PlayerActionStep.TOUCHDOWN)
                    }
                    // Once per turn-slot beat — after the action (and nested follow-ups) truly complete.
                    PlayerActionStep.TOUCHDOWN         -> {
                        scoreTouchdownIfPossible(session)
                        ChallengeTurnSteps.maybeClaimAfterAction(session, session.requireCurrentPlayerAction())
                            ?.let { session.setPendingDecision(it) }
                        session.setStep(PlayerActionStep.CLAIM)
                    }
                    PlayerActionStep.CLAIM             -> {
                        // ClaimChallenge (if any) pauses via needsPlayerInput; once gone → THIRST.
                        session.setStep(PlayerActionStep.THIRST)
                    }
                    PlayerActionStep.THIRST         -> {
                        Thirst.afterClaimStep(session, session.requireCurrentPlayerAction(), bounce)
                        session.clearCurrentPlayerAction()
                        session.setStep(PlayerActionStep.QUEUE_NEXT)
                    }
                    PlayerActionStep.AFTER_THIRD       -> {
                        if (OfferDanceStepResolver.canOffer(session)) {
                            session.setPendingDecision(offerDanceStepResolver.begin(session))
                            session.setStep(PlayerActionStep.OFFER_DANCE_STEP)
                        } else if (BonusOffers.canOfferExtraEffort(session)) {
                            session.setPendingDecision(offerExtraEffortResolver.begin(session))
                            // Advance step even when the offer is parked — crank pauses on
                            // needsPlayerInput; OFFER_EXTRA_EFFORT runs once the window is clear.
                            session.setStep(PlayerActionStep.OFFER_EXTRA_EFFORT)
                        } else {
                            session.enterPhase(TurnPhase.TURN_END, TurnEndStep.CLEANUP)
                        }
                    }
                    PlayerActionStep.OFFER_DANCE_STEP    -> {
                        // Skip/drain cleared the pending; Accept+Sidestep nests a continuation → reconcile → IN_ACTION.
                        check(!session.hasPendingDecision()) { "OFFER_DANCE_STEP with pending — Accept should still be awaiting input" }
                        // Do not re-enter AFTER_THIRD (would re-offer the same Skip) — chain Extra Effort or end.
                        if (BonusOffers.canOfferExtraEffort(session)) {
                            session.setPendingDecision(offerExtraEffortResolver.begin(session))
                            session.setStep(PlayerActionStep.OFFER_EXTRA_EFFORT)
                        } else {
                            session.enterPhase(TurnPhase.TURN_END, TurnEndStep.CLEANUP)
                        }
                    }
                    PlayerActionStep.OFFER_EXTRA_EFFORT -> {
                        // Decline cleared the pending; Accept alone parks bare RPA (still this step);
                        // picking the fourth action nests a *Continuation → reconcile → IN_ACTION.
                        check(!session.hasPendingDecision()) { "OFFER_EXTRA_EFFORT with pending — Accept should still be awaiting input" }
                        session.enterPhase(TurnPhase.TURN_END, TurnEndStep.CLEANUP)
                    }
                }
                TurnPhase.TURN_END        -> when (session.turnEndStep) {
                    TurnEndStep.CLEANUP -> {
                        ChallengeTurnSteps.endOfTurnCleanup(session)
                        maybeEndByPointsVictory(session)
                        session.setStep(TurnEndStep.HANDOFF)
                    }
                    TurnEndStep.HANDOFF -> {
                        handOffToIncomingCoach(session)
                        session.incrementTurnNumber()
                        session.enterPhase(TurnPhase.TURN_START, TurnStartStep.SUDDEN_DEATH)
                    }
                }
                TurnPhase.GAME_OVER       -> {}
            }
        }
        if (!session.matchOver) {
            session.cueNextPlayerDecision()
        } else {
            session.enterPhase(TurnPhase.GAME_OVER)
            session.recordMatchEnded()
        }
    }

    /**
     * Align SELECT / OFFER_* ↔ IN_ACTION after takeAction, from pending nest shape.
     *
     * Why not a SELECT/OFFER arm: resolvers nest a *Continuation (and [MatchMutator.beginPlayerAction])
     * during takeAction, then [advance] runs. The crank loop is `while (!needsPlayerInput())`, so if
     * the new action immediately awaits input those arms never run. This pre-loop pass is that
     * transition.
     *
     * Nest (one slot, child on [PendingDecision.delegate]):
     * - SELECT: [RegularPlayerAction] → *Continuation once an action type is picked
     * - OFFER_DANCE_STEP: [OfferDanceStep] → [SidestepContinuation] on Accept
     * - OFFER_EXTRA_EFFORT: [OfferExtraEffort] → bare [RegularPlayerAction] on Play (still selecting);
     *   that RPA → *Continuation once the fourth action is picked
     *
     * Empty pending on OFFER_*: Skip/Decline already cleared — the OFFER arm below advances.
     * [MatchMutator.beginPlayerAction] stays in the resolver (needs the LegalAction pick).
     */
    private fun reconcilePlayerActionStep(session: MatchMutator) {
        if (session.phase != TurnPhase.PLAYER_ACTION) return
        when (session.playerActionStep) {
            PlayerActionStep.SELECT            -> {
                if (!session.hasPendingDecision()) return
                val rpa = session.pendingAs<RegularPlayerAction>()
                if (rpa.delegate != null) enterInAction(session)
            }
            PlayerActionStep.OFFER_DANCE_STEP    -> {
                // No pending: Skip cleared the offer — OFFER_DANCE_STEP arm runs next.
                if (!session.hasPendingDecision()) return
                val offer = session.pendingAs<OfferDanceStep>()
                if (offer.delegate != null) enterInAction(session)
            }
            PlayerActionStep.OFFER_EXTRA_EFFORT -> {
                // No pending: Decline cleared — OFFER_EXTRA_EFFORT arm runs next.
                if (!session.hasPendingDecision()) return
                val offer = session.pendingAs<OfferExtraEffort>()
                val selectingRpa = offer.delegate
                // Play parks bare RPA (still this step). Fourth-action pick nests *Continuation under that RPA.
                if (selectingRpa?.delegate != null) enterInAction(session)
            }
            PlayerActionStep.IN_ACTION         -> {
                val pending = session.requirePendingDecision()
                val continuationGone = when (pending) {
                    is RegularPlayerAction -> pending.delegate == null
                    is OfferDanceStep       -> pending.delegate == null
                    is OfferExtraEffort    -> pending.delegate?.delegate == null
                    else                   -> error("IN_ACTION unexpected pending $pending")
                }
                if (!continuationGone) return
                // Throw Team-mate cancel (etc.): *Continuation cleared, selecting / offer root again.
                check(session.currentPlayerAction == null) { "turn-slot continuation cleared but currentPlayerAction still set" }
                when (pending) {
                    is OfferDanceStep       -> session.setStep(PlayerActionStep.OFFER_DANCE_STEP)
                    is OfferExtraEffort    -> session.setStep(PlayerActionStep.OFFER_EXTRA_EFFORT)
                    is RegularPlayerAction -> session.setStep(PlayerActionStep.SELECT)
                    else                   -> error("unreachable")
                }
            }
            else                               -> Unit
        }
    }

    private fun enterInAction(session: MatchMutator) {
        check(session.currentPlayerAction != null) { "turn-slot *Continuation nested but beginPlayerAction never ran" }
        session.setStep(PlayerActionStep.IN_ACTION)
    }

    /**
     * Resolve the current turn-slot player action (ActionComplete, log, one-shot modifiers).
     * Keeps [MatchMutator.currentPlayerAction] through claim / Thirst.
     */
    private fun resolveOpenPlayerAction(session: MatchMutator) {
        val resolved = session.resolveCurrentPlayerAction()
        session.recordTurnAction(
            resolved.playerId,
            resolved.actionType,
            isFree = resolved.origin == OpenPlayerAction.Origin.FREE,
        )
        if (resolved.origin == OpenPlayerAction.Origin.BUDGETED) {
            session.recordPlayerActionConsumed()
        }
    }

    private fun queueNextInitialPlacement(session: MatchMutator): Boolean {
        check(!session.hasPendingDecision()) { "QUEUE_NEXT expects no pending before inserting InitialPlayerPlacement" }
        val dugoutIds = session.board.board.reserves.getValue(session.activeCoach).playerIds
        if (dugoutIds.isEmpty()) return false
        session.setPendingDecision(initialPlayerPlacementResolver.begin(dugoutIds.last()))
        return true
    }

    private fun handOffToIncomingCoach(session: MatchMutator) {
        session.clearPerTurnTracking()
        session.swapActiveCoach()
    }

    private fun maybeEndBySuddenDeath(session: MatchMutator) {
        val active = session.activeCoach
        val lead = session.scoreFor(active).leadOver(session.scoreFor(active.opposite()))
        if (lead >= 10) {
            session.setMatchResult(MatchResult.SuddenDeath(active))
        }
    }

    private fun maybeEndByPointsVictory(session: MatchMutator) {
        val remaining = session.pointsVictoryTurnsRemaining ?: return
        val after = remaining - 1
        if (after <= 0) {
            session.setPointsVictoryTurnsRemaining(0)
            session.setMatchResult(MatchResult.PointsVictory(winnerByScore(session)))
        } else {
            session.setPointsVictoryTurnsRemaining(after)
        }
    }

    /** Coach A’s first turn is turn 1; Coach B’s first is turn 2 (number bumps at each TurnEnd). */
    private fun isFirstActivationForActiveCoach(session: MatchMutator): Boolean =
        when {
            session.activeCoach == session.config.coachA -> session.turnNumber == 1
            else                                         -> session.turnNumber == 2
        }

    /**
     * If dugout count ≥ team ER and at least one reserve has a legal end-zone square, enqueue a
     * **single** free Reserves Action for this pre-turn (digest § Reserves). Not re-queued after placement.
     */
    private fun queueEmergencyReserves(session: MatchMutator) {
        val coach = session.activeCoach
        val emergencyReservesThreshold = when (coach) {
            TeamSide.HOME -> session.config.homeEmergencyReserves
            TeamSide.AWAY -> session.config.awayEmergencyReserves
        }
        val dugoutIds = session.board.board.reserves.getValue(coach).playerIds
        if (dugoutIds.size < emergencyReservesThreshold) {
            return
        }
        val canPlace = dugoutIds.any { playerId ->
            reservesPlacements(session.board.getPlayer(playerId), session.board.board).isNotEmpty()
        }
        if (!canPlace) {
            return
        }
        // TODO(pending): setPendingDecision without asserting slot empty in this helper — relies on
        // pre-turn grant already cleared; fragile if PLAYER_ABILITIES/ER ordering drifts.
        session.setPendingDecision(emergencyReservesResolver.begin())
    }

    private fun winnerByScore(session: MatchMutator): TeamSide? {
        val lead = session.scoreFor(TeamSide.HOME).leadOver(session.scoreFor(TeamSide.AWAY))
        return when {
            lead > 0 -> TeamSide.HOME
            lead < 0 -> TeamSide.AWAY
            else     -> null
        }
    }
}
