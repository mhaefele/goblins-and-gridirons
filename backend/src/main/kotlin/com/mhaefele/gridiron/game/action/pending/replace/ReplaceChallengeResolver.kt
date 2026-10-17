package com.mhaefele.gridiron.game.action.pending.replace

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/** takeAction owner for [ReplaceChallenge]. */
@Component
class ReplaceChallengeResolver : PendingDecisionResolver<ReplaceChallenge>() {
    fun begin(): ReplaceChallenge = ReplaceChallenge()

    override fun mutate(
        session: MatchMutator,
        pending: ReplaceChallenge,
        command: LegalAction,
    ): ReplaceChallenge {
        val replace = command as? LegalAction.ReplaceChallenge ?: error("ReplaceChallenge expects ReplaceChallenge action, was ${command::class.simpleName}")
        session.replaceChallengeCard(replace.slotIndex)
        return ReplaceChallenge(resolved = true)
    }
}
