package com.mhaefele.gridiron.game.action.pending.claim

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.cards.ChallengeTurnSteps
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.ClaimChallenge
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/** takeAction owner for [ClaimChallenge]. Pending is built in [ChallengeTurnSteps.maybeClaimAfterAction]. */
@Component
class ClaimChallengeResolver : PendingDecisionResolver<ClaimChallenge>() {
    override fun mutate(
        session: MatchMutator,
        pending: ClaimChallenge,
        command: LegalAction,
    ): ClaimChallenge {
        val claim = command as? LegalAction.ClaimChallenge ?: error("ClaimChallenge expects ClaimChallenge action, was ${command::class.simpleName}")
        check(claim.slotIndex in pending.eligibleSlotIndexes) { "Slot ${claim.slotIndex} is not among eligible ${pending.eligibleSlotIndexes}" }
        ChallengeTurnSteps.applyClaim(session, session.activeCoach, claim.slotIndex)
        return ClaimChallenge(pending.eligibleSlotIndexes, resolved = true)
    }
}
