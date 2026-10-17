package com.mhaefele.gridiron.shared

import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

enum class TurnPhase {
    GAME_START,
    PIECE_PLACEMENT,
    TURN_START,
    PRE_TURN,
    PLAYER_ACTION,
    TURN_END,
    GAME_OVER,
}

/** A beat inside a [TurnPhase]. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(
    JsonSubTypes.Type(value = PreTurnStep::class, name = "preTurn"),
    JsonSubTypes.Type(value = PiecePlacementStep::class, name = "piecePlacement"),
    JsonSubTypes.Type(value = TurnStartStep::class, name = "turnStart"),
    JsonSubTypes.Type(value = TurnEndStep::class, name = "turnEnd"),
    JsonSubTypes.Type(value = PlayerActionStep::class, name = "playerAction"),
)
sealed interface PhaseStep

/** Next Pre-Turn beat to run (digest § Turn sequence). */
enum class PreTurnStep(val description: String) : PhaseStep {
    TOUCHDOWN("Score if a ball carrier is already in the end zone from the opponent's turn."),
    CHALLENGE_REFRESH("Refresh Challenge Cards into empty tableau slots."),
    REPLACE_CHALLENGE("Coach discards a full-tableau slot and draws; no-op when Refresh left a gap."),
    NEW_BALL("If no ball is in play, emerge one at a trapdoor."),
    PLAYER_ABILITIES("Offer the active coach's pre-turn player abilities."),
    EMERGENCY_RESERVES("One free Reserves Action if dugout count is at least team ER."),
    BONUS_CARDS("Offer the active coach's pre-turn bonus cards."),
    /** Runs only after Hold the Line resolved (or was not offered) — enters Player Action. */
    AFTER_BONUS_CARDS("Leave PRE_TURN once the Hold the Line window is clear."),
}

enum class PiecePlacementStep(val description: String) : PhaseStep {
    PLACE_COACH_A("Enter Coach A's initial placement."),
    QUEUE_NEXT_COACH_A("Queue Coach A's next initial placement, or advance when their digout is empty."),
    PLACE_COACH_B("Swap to Coach B for initial placement."),
    QUEUE_NEXT_COACH_B("Queue Coach B's next initial placement, or advance when their digout is empty."),
    KICKOFF("Place the kickoff ball and open Coach A's first Player Action."),
}

enum class TurnStartStep(val description: String) : PhaseStep {
    SUDDEN_DEATH("Win immediately if the active coach leads by 10 or more."),
    GANG_UP("Offer Gang Up before Pre-Turn."),
    /** Runs only after Gang Up resolved (or was not offered) — advances to Pre-Turn / Player Action. */
    AFTER_GANG_UP("Leave TURN_START once the Gang Up window is clear."),
}

enum class TurnEndStep(val description: String) : PhaseStep {
    CLEANUP("Trim challenge hand and check Points Victory."),
    HANDOFF("Clear per-turn tracking, swap active coach, start the next turn."),
}

enum class PlayerActionStep(val description: String) : PhaseStep {
    SEED("Snapshot turn-start ball possession and arm the three-action budget."),
    /**
     * Second beat each turn-slot cycle: queue one [RegularPlayerAction] while
     * budget slots remain, otherwise advance to [AFTER_THIRD].
     */
    QUEUE_NEXT("Queue the next budgeted Player Action, or advance when the budget is spent."),
    SELECT("Wait for the next turn-slot action (budgeted or standalone free)."),
    /**
     * Open turn-slot action: budgeted, or standalone free (Extra Effort; Back on Your Feet / Reserves once
     * convert-in-place has begun). Interrupts, ability follow-ups, and after-own free actions
     * nest inside the action's Ongoing* continuation until it reaches DONE.
     */
    IN_ACTION("A turn-slot player action is open; mid-action pendings, follow-ups, and interrupts resolve it."),
    COMPLETE("Close the open action: log it, clear one-shot modifiers."),
    TOUCHDOWN("Once per turn-slot beat: score if a ball carrier is Open in the end zone."),
    CLAIM("Wait for a Claim Challenge pick if queued; otherwise continue to Thirst."),
    THIRST("Thirst and other after-claim follow-ups."),
    AFTER_THIRD("Offer Dance Step / Extra Effort after the budget is spent; else end the Player Action phase."),
    /**
     * Sidestep / Skip Dance Step after the third action. Skip clears → Extra Effort or TURN_END.
     * Accept nests [com.mhaefele.gridiron.model.pending.SidestepContinuation] on
     * [com.mhaefele.gridiron.model.pending.OfferDanceStep.delegate] → TurnEngine.reconcile → [IN_ACTION].
     */
    OFFER_DANCE_STEP("Wait for Dance Step Sidestep/Skip, or the free Sidestep after Accept."),
    /**
     * Play / Decline Extra Effort. Decline clears → TURN_END. Accept parks bare RPA on
     * [com.mhaefele.gridiron.model.pending.OfferExtraEffort.delegate] (still this step);
     * picking that action nests a *Continuation → TurnEngine.reconcile → [IN_ACTION].
     */
    OFFER_EXTRA_EFFORT("Wait for Extra Effort Play/Decline, or the fourth action after Accept."),
}
