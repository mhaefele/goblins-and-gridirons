package com.mhaefele.gridiron.game.action.pending

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.PendingDecision

/**
 * One takeAction owner per concrete [PendingDecision] type.
 * Resolvers never write the pending slot — they return the next [P]; callers
 * [MatchMutator.setPendingDecision] / [MatchMutator.updatePendingDecision] (or a parent
 * parks via `copy(delegate = …)`).
 */
abstract class PendingDecisionResolver<P : PendingDecision> {
    abstract fun mutate(session: MatchMutator, pending: P, command: LegalAction): P
}
