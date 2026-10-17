package com.mhaefele.gridiron.model

import com.mhaefele.gridiron.model.pending.PendingDecision
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PhaseStep
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/** One resolved player action on the active coach's turn (budget or free grant). */
data class TurnAction(
    val playerId: PlayerId,
    val actionType: ActionType,
    /** Free ability grants / free Reserves — excluded from Star Turn. */
    val isFree: Boolean = false,
    /**
     * After a touchdown the scorer's entries stay in the turn log for challenge aggregates
     * (Keep Running!, All Hands!, …) but are excluded from same-action-once so a Reserves
     * return may reuse action types (digest § Reserves).
     */
    val countsTowardSameActionOnce: Boolean = true,
)

/** Per-turn clock and action rules for the coach currently playing. Persisted on [MatchState]. */
data class TurnState(
    val phase: TurnPhase,
    val step: PhaseStep? = null,
    val activeCoach: TeamSide,
    /**
     * Ordered log of player actions this coach turn. Same-action-once and Charge sequence are
     * derived from this list — do not store parallel used-set / per-player maps.
     */
    val actionsThisTurn: List<TurnAction> = emptyList(),
    /** Blockers who tackled or missed — cannot take further actions this turn (digest § Block). */
    val turnEndedPlayers: Set<PlayerId> = emptySet(),
    /**
     * Single coach prompt, or null when the turn crank should advance.
     * Mid-action multi-beat work lives on Ongoing* inside one *Continuation.
     */
    val pendingDecision: PendingDecision? = null,
    /**
     * In-flight basic player action (claim bracket included). Set only in
     * [com.mhaefele.gridiron.game.turn.MatchMutator.beginPlayerAction] during [TurnPhase.PLAYER_ACTION];
     * cleared after Thirst or abort. PRE_TURN / placement use
     * [com.mhaefele.gridiron.game.turn.MatchMutator.runStandalonePlayerMove] instead.
     */
    val currentPlayerAction: OpenPlayerAction? = null,
    /**
     * Bonus play types already used this coach turn — no two of the same type (reference/cards.md § Rules reminders).
     * Cleared with other per-turn tracking at coach hand-off.
     */
    val bonusPlaysUsedThisTurn: Set<BonusPlay> = emptySet(),
    /** Active challenge bonus modifiers for this coach turn / next action. */
    val bonusModifiers: TurnBonusModifiers = TurnBonusModifiers(),
    /**
     * Base Player Action slots still available this coach turn (0–3), including the slot for a
     * parked [RegularPlayerAction] that has not been begun yet.
     * Decremented only in [com.mhaefele.gridiron.game.turn.MatchMutator.beginPlayerAction] for
     * budgeted actions — not when QUEUE_NEXT parks the pick.
     */
    val regularActionSlotsRemaining: Int = 0,
) {
    /** Action types already taken by each player this turn (digest § Turn budget — same action once). */
    val actionsUsedThisTurn: Map<PlayerId, Set<ActionType>>
        get() = actionsThisTurn
            .filter { it.countsTowardSameActionOnce }
            .groupBy { it.playerId }
            .mapValues { (_, entries) -> entries.map { it.actionType }.toSet() }

    /** Actions left this turn for [activeCoach] — unspent budget slots (parked RPA not yet begun still counts). */
    val actionsRemaining: Int get() = regularActionSlotsRemaining

    fun actionSequenceFor(playerId: PlayerId): List<ActionType> =
        actionsThisTurn
            .filter { it.playerId == playerId && it.countsTowardSameActionOnce }
            .map { it.actionType }

    init {
        require(regularActionSlotsRemaining in 0..3) { "regularActionSlotsRemaining must be 0–3, was $regularActionSlotsRemaining" }
        if (phase == TurnPhase.PLAYER_ACTION) {
            require(actionsRemaining in 0..3) { "actionsRemaining must be 0–3 during player action phase, was $actionsRemaining" }
        }
    }
}

/**
 * A basic player action ([actionType] = RUN, BLOCK, MARK, …) from commit through the end of its
 * turn-slot beat (claim / Thirst). Never SKIP / choose-reroll / place-ball wire kinds.
 *
 * [ballsHeldAtActionStart] and [eventsSinceStart] are claim-bracket state: each HTTP resume builds
 * a fresh [com.mhaefele.gridiron.game.turn.MatchMutator], so these must live on the persisted
 * current action.
 */
data class OpenPlayerAction(
    val playerId: PlayerId,
    val actionType: ActionType,
    val origin: Origin,
    /** Possession snapshot at action start — do not re-derive from the board after drops. */
    val ballsHeldAtActionStart: Map<TeamSide, Map<BallId, Coordinate>> = emptyMap(),
    /** Bracket events from prior requests while this action stayed open. */
    val eventsSinceStart: List<DomainEvent> = emptyList(),
) {
    enum class Origin {
        /** From the coach's three RegularPlayerAction slots — turn log, budget consume, ability follow-ups. */
        BUDGETED,

        /** Free follow-up / convert-in-place inside PLAYER_ACTION — still uses currentPlayerAction. */
        FREE,
    }
}
