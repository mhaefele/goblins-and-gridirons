package com.mhaefele.gridiron.game.action.pending.holdtheline

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.OfferHoldTheLine
import org.springframework.stereotype.Component

/** Legal moves for [OfferHoldTheLine]. */
@Component
class OfferHoldTheLineLegalMovesGenerator : PendingDecisionLegalMovesGenerator<OfferHoldTheLine>() {
    override fun generate(
        state: MatchState,
        pending: OfferHoldTheLine,
    ): List<LegalAction> =
        playIfPresent(state.cards, checkNotNull(pending.respondingCoach), state.currentTurnState, BonusPlay.`Hold the Line`) + LegalAction.DeclineBonus
}
