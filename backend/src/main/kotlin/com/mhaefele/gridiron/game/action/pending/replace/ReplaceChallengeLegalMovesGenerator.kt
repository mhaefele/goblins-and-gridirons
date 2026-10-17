package com.mhaefele.gridiron.game.action.pending.replace

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import org.springframework.stereotype.Component

/** Legal moves for [ReplaceChallenge] — one ReplaceChallenge per occupied active slot. */
@Component
class ReplaceChallengeLegalMovesGenerator : PendingDecisionLegalMovesGenerator<ReplaceChallenge>() {
    override fun generate(
        state: MatchState,
        pending: ReplaceChallenge,
    ): List<LegalAction> =
        state.cards.activeSlots.mapIndexedNotNull { slot, card ->
            card ?: return@mapIndexedNotNull null
            LegalAction.ReplaceChallenge(
                slotIndex = slot,
                claimCondition = card.challenge,
                challengeTitle = card.challenge.displayName,
            )
        }
}
