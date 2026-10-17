package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.TeamSide

/** Move one matching bonus-play card into the trainer's hand (from deck / tableau / discard). */
internal fun CardState.withBonusInHand(trainerSide: TeamSide, bonus: BonusPlay): CardState {
    val card = (deck + activeSlots.filterNotNull() + discard + homeHand + awayHand)
        .firstOrNull { it.bonusPlay == bonus } ?: error("No $bonus card available to put in hand")
    val cleared = copy(
        activeSlots = activeSlots.map { if (it == card) null else it },
        deck = deck.filter { it != card },
        discard = discard.filter { it != card },
        homeHand = homeHand.filter { it != card },
        awayHand = awayHand.filter { it != card },
    )
    return when (trainerSide) {
        TeamSide.HOME -> cleared.copy(homeHand = cleared.homeHand + card)
        TeamSide.AWAY -> cleared.copy(awayHand = cleared.awayHand + card)
    }
}
