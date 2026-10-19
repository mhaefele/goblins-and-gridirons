package com.mhaefele.gridiron.game.action.pending.gangup

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.OfferGangUp
import com.mhaefele.gridiron.model.catalog.BonusPlay
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/** takeAction owner for [OfferGangUp] (TURN_START / GANG_UP). */
@Component
class OfferGangUpResolver : PendingDecisionResolver<OfferGangUp>() {
    fun begin(session: MatchMutator): OfferGangUp =
        OfferGangUp(session.activeCoach)

    override fun mutate(
        session: MatchMutator,
        pending: OfferGangUp,
        command: LegalAction,
    ): OfferGangUp {
        val coach = checkNotNull(pending.respondingCoach) { "OfferGangUp missing respondingCoach" }
        when (command) {
            is LegalAction.DeclineBonus  -> Unit
            is LegalAction.PlayBonusCard -> {
                check(command.bonusPlay == BonusPlay.`Gang Up`) { "OfferGangUp expects Gang Up, was ${command.bonusPlay}" }
                // TODO: can we make this require a card and follow that path instead of implicitly playing the card based on the action?
                session.playBonusCard(coach, command.bonusPlay)
                session.updateBonusModifiers { it.copy(gangUp = true) }
            }
            else                         -> error("OfferGangUp expects Play* or DeclineBonus, was ${command::class.simpleName}")
        }
        return pending.copy(resolved = true)
    }
}
