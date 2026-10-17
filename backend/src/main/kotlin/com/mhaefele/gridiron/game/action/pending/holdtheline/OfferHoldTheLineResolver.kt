package com.mhaefele.gridiron.game.action.pending.holdtheline

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.OfferHoldTheLine
import com.mhaefele.gridiron.model.catalog.BonusPlay
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/** takeAction owner for [OfferHoldTheLine] (PRE_TURN / BONUS_CARDS). */
@Component
class OfferHoldTheLineResolver : PendingDecisionResolver<OfferHoldTheLine>() {
    fun begin(session: MatchMutator): OfferHoldTheLine =
        OfferHoldTheLine(session.activeCoach)

    override fun mutate(
        session: MatchMutator,
        pending: OfferHoldTheLine,
        command: LegalAction,
    ): OfferHoldTheLine {
        val coach = checkNotNull(pending.respondingCoach) { "OfferHoldTheLine missing respondingCoach" }
        when (command) {
            is LegalAction.DeclineBonus  -> Unit
            is LegalAction.PlayBonusCard -> {
                check(command.bonusPlay == BonusPlay.`Hold the Line`) { "OfferHoldTheLine expects Hold the Line, was ${command.bonusPlay}" }
                session.playBonusCard(coach, command.bonusPlay)
                session.updateBonusModifiers { it.copy(holdTheLine = true) }
            }
            else                         -> error("OfferHoldTheLine expects Play* or DeclineBonus, was ${command::class.simpleName}")
        }
        return pending.copy(resolved = true)
    }
}
