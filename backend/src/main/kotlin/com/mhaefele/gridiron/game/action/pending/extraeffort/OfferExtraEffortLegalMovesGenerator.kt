package com.mhaefele.gridiron.game.action.pending.extraeffort

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import org.springframework.stereotype.Component

/**
 * Legal moves for [OfferExtraEffort].
 * With a [OfferExtraEffort.delegate], follows [RegularPlayerActionLegalMovesGenerator]; otherwise Play / Decline.
 */
@Component
class OfferExtraEffortLegalMovesGenerator(
    private val regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<OfferExtraEffort>() {
    override fun generate(
        state: MatchState,
        pending: OfferExtraEffort,
    ): List<LegalAction> {
        val rpa = pending.delegate
        return if (rpa != null) {
            regularPlayerActionLegalMovesGenerator.generate(state, rpa)
        } else {
            playIfPresent(state.cards, checkNotNull(pending.respondingCoach), state.currentTurnState, BonusPlay.`Extra Effort`) + LegalAction.DeclineBonus
        }
    }
}
