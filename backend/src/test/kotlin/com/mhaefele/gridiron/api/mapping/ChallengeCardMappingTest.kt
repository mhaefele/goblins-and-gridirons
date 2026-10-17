package com.mhaefele.gridiron.api.mapping

import com.mhaefele.gridiron.api.dto.response.MatchGameStateDto
import com.mhaefele.gridiron.game.action.dto.ChallengeCardDto
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChallengeCardMappingTest {
    @Test
    fun `public tableau exposes challenge faces without bonus text`() {
        val cards = CardState.dealInCatalogOrder()
        val dto = toChallengeTableauDto(cards)
        val first = ChallengeCardCatalog.card(1)

        assertThat(dto.activeSlots).hasSize(3)
        assertThat(dto.activeSlots[0]).isEqualTo(
            ChallengeCardDto(
                first.challenge,
                title = first.challenge.displayName,
                points = first.points,
            ),
        )
        assertThat(dto.deckRemaining).isEqualTo(23)
        assertThat(ChallengeCardDto::class.java.declaredFields.map { it.name })
            .doesNotContain("bonusPlay")
    }

    @Test
    fun `shared game-state snapshot includes tableau but not bonus hands`() {
        val gameState = toMatchGameStateDto(matchState())

        assertThat(gameState.challenges.activeSlots).hasSize(3)
        assertThat(gameState.challenges.deckTop).isNotNull
        assertThat(MatchGameStateDto::class.java.declaredFields.map { it.name })
            .doesNotContain("homeHand", "awayHand", "bonusHand", "hand")
    }

    @Test
    fun `own-hand mapper exposes bonus play only for requested seat`() {
        val all = ChallengeCardCatalog.allCards()
        val claimed = all[0]
        val cards = CardState(
            activeSlots = listOf(null, all[1], all[2]),
            deck = all.drop(3),
            homeHand = listOf(claimed),
        )

        assertThat(toBonusHandDto(cards, TeamSide.HOME).cards.single().bonusPlay).isEqualTo(BonusPlay.`Stick Close`)
        assertThat(toBonusHandDto(cards, TeamSide.AWAY).cards).isEmpty()
    }
}
