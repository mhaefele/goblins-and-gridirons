package com.mhaefele.gridiron.game.action.pending.extraeffort

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.catalog.BonusPlay
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionResolver

/**
 * takeAction owner for [OfferExtraEffort] (PLAYER_ACTION / AFTER_THIRD).
 * Accept parks a selecting RegularPlayerAction on [OfferExtraEffort.delegate]; further POSTs
 * follow that RPA (same nest pattern as RPA → *Continuation).
 * Decline returns resolved Extra Effort; executor clears it and TurnEngine
 * [PlayerActionStep.OFFER_EXTRA_EFFORT] with an empty slot enters TURN_END.
 */
@Component
class OfferExtraEffortResolver(
    private val regularPlayerActionResolver: RegularPlayerActionResolver,
) : PendingDecisionResolver<OfferExtraEffort>() {
    fun begin(session: MatchMutator): OfferExtraEffort =
        OfferExtraEffort(session.activeCoach)

    override fun mutate(
        session: MatchMutator,
        pending: OfferExtraEffort,
        command: LegalAction,
    ): OfferExtraEffort =
        when (val child = pending.delegate) {
            null -> mutateOffer(session, pending, command)
            else -> pending.copy(delegate = regularPlayerActionResolver.mutate(session, child, command))
        }

    private fun mutateOffer(
        session: MatchMutator,
        offer: OfferExtraEffort,
        command: LegalAction,
    ): OfferExtraEffort {
        val coach = checkNotNull(offer.respondingCoach) { "OfferExtraEffort missing respondingCoach" }
        return when (command) {
            is LegalAction.DeclineBonus  -> offer.copy(resolved = true)
            is LegalAction.PlayBonusCard -> {
                check(command.bonusPlay == BonusPlay.`Extra Effort`) { "OfferExtraEffort expects Extra Effort, was ${command.bonusPlay}" }
                session.playBonusCard(coach, command.bonusPlay)
                session.returnRegularActionSlot()
                offer.copy(delegate = regularPlayerActionResolver.begin(session))
            }
            else                         -> error("OfferExtraEffort expects Play* or DeclineBonus, was ${command::class.simpleName}")
        }
    }
}
