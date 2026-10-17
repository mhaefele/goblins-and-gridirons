package com.mhaefele.gridiron.model

import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CardStateTest {
    @Test
    fun `deal places three actives and leaves next card as deck top`() {
        val cards = CardState.dealInCatalogOrder()
        val all = ChallengeCardCatalog.allCards()

        assertThat(cards.activeSlots).containsExactly(all[0], all[1], all[2])
        assertThat(cards.deckTop).isEqualTo(all[3])
        assertThat(cards.deck).hasSize(23)
        assertThat(cards.handCards(TeamSide.HOME)).isEmpty()
        assertThat(cards.handCards(TeamSide.AWAY)).isEmpty()
        assertThat(cards.discard).isEmpty()
        assertThat(cards.claimsThisTurn).isZero()
    }
}
