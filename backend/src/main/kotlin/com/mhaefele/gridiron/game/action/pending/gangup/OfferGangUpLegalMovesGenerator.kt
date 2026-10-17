package com.mhaefele.gridiron.game.action.pending.gangup

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.OfferGangUp
import org.springframework.stereotype.Component

/** Legal moves for [OfferGangUp]. */
@Component
class OfferGangUpLegalMovesGenerator : PendingDecisionLegalMovesGenerator<OfferGangUp>() {
    override fun generate(
        state: MatchState,
        pending: OfferGangUp,
    ): List<LegalAction> =
        playIfPresent(state.cards, checkNotNull(pending.respondingCoach), state.currentTurnState, BonusPlay.`Gang Up`) + LegalAction.DeclineBonus
}
