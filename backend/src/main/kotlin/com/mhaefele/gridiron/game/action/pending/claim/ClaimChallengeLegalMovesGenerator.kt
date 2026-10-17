package com.mhaefele.gridiron.game.action.pending.claim

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.ClaimChallenge
import org.springframework.stereotype.Component

/** Legal moves for [ClaimChallenge] — one ClaimChallenge per eligible active slot. */
@Component
class ClaimChallengeLegalMovesGenerator : PendingDecisionLegalMovesGenerator<ClaimChallenge>() {
    override fun generate(
        state: MatchState,
        pending: ClaimChallenge,
    ): List<LegalAction> =
        pending.eligibleSlotIndexes.map { slot ->
            val card = state.cards.activeSlots[slot] ?: error("ClaimChallenge slot $slot is empty")
            LegalAction.ClaimChallenge(
                slotIndex = slot,
                claimCondition = card.challenge,
                challengeTitle = card.challenge.displayName,
                points = card.points,
            )
        }
}
