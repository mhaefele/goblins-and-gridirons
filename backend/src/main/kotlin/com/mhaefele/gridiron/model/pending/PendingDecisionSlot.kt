package com.mhaefele.gridiron.model.pending

/**
 * Whether a [PendingDecision] may occupy [com.mhaefele.gridiron.model.TurnState.pendingDecision] as the root.
 * Exactly one of [TopLevel] / [NestedOnly] on every concrete pending.
 */
sealed interface PendingDecisionSlot {
    /** May occupy `TurnState.pendingDecision` as the root. */
    sealed interface TopLevel : PendingDecisionSlot
    /** Only valid under a parent `delegate` (e.g. RPA / Extra Effort → RPA). */
    sealed interface NestedOnly : PendingDecisionSlot
}
