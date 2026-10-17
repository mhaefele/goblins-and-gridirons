package com.mhaefele.gridiron.game.action.pending

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.PendingDecision

/**
 * One legal-move generator per concrete [PendingDecision] type — mirrors [PendingDecisionResolver].
 * Parents follow `delegate` / nested Ongoing* into child generators; board geometry stays in
 * [com.mhaefele.gridiron.game.legalmoves].
 *
 * Player / action-type scoping for selecting RPA is not on this API — only
 * [com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator]
 * knows how to enumerate a single roster player / action kind.
 */
abstract class PendingDecisionLegalMovesGenerator<P : PendingDecision> {
    abstract fun generate(state: MatchState, pending: P): List<LegalAction>
}
