package com.mhaefele.gridiron.model

/**
 * Challenge bonus modifiers active for the current coach turn.
 * Cleared at coach hand-off with other per-turn tracking.
 *
 * One-shot action geometry (Sprint / Weave / Press) is applied via
 * [com.mhaefele.gridiron.game.legalmoves.ActionAttachedBonuses] + `requiredBonusCard` at
 * submit time — not stored here. All-In Tackle prone-after-block lives on
 * [com.mhaefele.gridiron.game.action.pending.OngoingBlock].
 *
 * Process-scoped block/throw flags (Brace, Underdog, Bladed, Hail Mary, Dead Eye, …)
 * live on [com.mhaefele.gridiron.game.action.pending.OngoingBlock] /
 * [com.mhaefele.gridiron.game.action.pending.OngoingThrow] instead.
 *
 * Hail Mary / Nerves geometry for legal-move generation comes from cards in hand
 * ([com.mhaefele.gridiron.game.legalmoves.ActionAttachedBonuses]), not from this struct.
 *
 * Named like the card text so call sites read as the rulebook.
 */
data class TurnBonusModifiers(
    /** Hold the Line — ignore interference on Run; entering adjacent ends the Run. */
    val holdTheLine: Boolean = false,
    /** Gang Up — all blocks Assisted this turn. */
    val gangUp: Boolean = false,
)
