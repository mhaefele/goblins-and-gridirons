package com.mhaefele.gridiron.model.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChallengeCardCatalogTest {
    @Test
    fun `catalog lists all twenty six physical cards`() {
        val cards = ChallengeCardCatalog.allCards()

        assertThat(cards).hasSize(26)
        assertThat(cards.map { it.id }).doesNotHaveDuplicates()
        assertThat(cards.map { it.deckIndex }).containsExactlyElementsOf((1..26).toList())
    }

    @Test
    fun `points and bonus keys match the cards reference pairings`() {
        val expected = listOf(
            Triple(1, ChallengeClaimCondition.GRAB_IT, BonusPlay.`Stick Close`),
            Triple(2, ChallengeClaimCondition.GRAB_IT, BonusPlay.`Slip Away`),
            Triple(3, ChallengeClaimCondition.HUDDLE_UP, BonusPlay.`Fresh Legs`),
            Triple(4, ChallengeClaimCondition.PUSH_FORWARD, BonusPlay.Weave),
            Triple(5, ChallengeClaimCondition.PUSH_FORWARD, BonusPlay.`Do-Over`),
            Triple(6, ChallengeClaimCondition.FAN_OUT, BonusPlay.`Fresh Legs`),
            Triple(7, ChallengeClaimCondition.PLAY_TO_THE_STANDS, BonusPlay.`Hail Mary Pass`),
            Triple(8, ChallengeClaimCondition.ALL_HANDS, BonusPlay.Press),
            Triple(9, ChallengeClaimCondition.CONNECT, BonusPlay.`Extra Effort`),
            Triple(10, ChallengeClaimCondition.CONNECT, BonusPlay.`Ice Cold`),
            Triple(11, ChallengeClaimCondition.STRIP_IT, BonusPlay.Momentum),
            Triple(12, ChallengeClaimCondition.STRIP_IT, BonusPlay.`Cut Off`),
            Triple(13, ChallengeClaimCondition.KEEP_RUNNING, BonusPlay.Sprint),
            Triple(14, ChallengeClaimCondition.KEEP_RUNNING, BonusPlay.Press),
            Triple(15, ChallengeClaimCondition.HOLD_THE_PIT, BonusPlay.`Back on Your Feet`),
            Triple(16, ChallengeClaimCondition.THREAD_THE_NEEDLE, BonusPlay.`Dead Eye`),
            Triple(17, ChallengeClaimCondition.ALL_HANDS, BonusPlay.Brace),
            Triple(18, ChallengeClaimCondition.ALL_HANDS, BonusPlay.`Extra Effort`),
            Triple(19, ChallengeClaimCondition.STAR_TURN, BonusPlay.`Spiked Gauntlets`),
            Triple(20, ChallengeClaimCondition.STAR_TURN, BonusPlay.Underdog),
            Triple(21, ChallengeClaimCondition.DROP_THEM, BonusPlay.`Gang Up`),
            Triple(22, ChallengeClaimCondition.DROP_THEM, BonusPlay.`All-In Tackle`),
            Triple(23, ChallengeClaimCondition.LOCK_THEM_DOWN, BonusPlay.`Do-Over`),
            Triple(24, ChallengeClaimCondition.LOCK_THEM_DOWN, BonusPlay.`Hold the Line`),
            Triple(25, ChallengeClaimCondition.SEND_THEM_OFF, BonusPlay.`Slip Away`),
            Triple(26, ChallengeClaimCondition.SEND_THEM_OFF, BonusPlay.`Do-Over`),
        )

        expected.forEach { (index, challenge, bonus) ->
            val card = ChallengeCardCatalog.card(index)
            assertThat(card.challenge).isEqualTo(challenge)
            assertThat(card.bonusPlay).isEqualTo(bonus)
            assertThat(card.points).isEqualTo(challenge.points)
        }
    }

    @Test
    fun `deck composition matches the cards reference copy counts`() {
        val byTitle = ChallengeCardCatalog.allCards().groupingBy { it.challenge }.eachCount()

        assertThat(byTitle[ChallengeClaimCondition.GRAB_IT]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.HUDDLE_UP]).isEqualTo(1)
        assertThat(byTitle[ChallengeClaimCondition.PUSH_FORWARD]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.FAN_OUT]).isEqualTo(1)
        assertThat(byTitle[ChallengeClaimCondition.PLAY_TO_THE_STANDS]).isEqualTo(1)
        assertThat(byTitle[ChallengeClaimCondition.CONNECT]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.STRIP_IT]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.KEEP_RUNNING]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.HOLD_THE_PIT]).isEqualTo(1)
        assertThat(byTitle[ChallengeClaimCondition.THREAD_THE_NEEDLE]).isEqualTo(1)
        assertThat(byTitle[ChallengeClaimCondition.ALL_HANDS]).isEqualTo(3)
        assertThat(byTitle[ChallengeClaimCondition.STAR_TURN]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.DROP_THEM]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.LOCK_THEM_DOWN]).isEqualTo(2)
        assertThat(byTitle[ChallengeClaimCondition.SEND_THEM_OFF]).isEqualTo(2)

        val byBonus = ChallengeCardCatalog.allCards().groupingBy { it.bonusPlay }.eachCount()
        assertThat(byBonus[BonusPlay.`Do-Over`]).isEqualTo(3)
        assertThat(byBonus[BonusPlay.`Extra Effort`]).isEqualTo(2)
        assertThat(byBonus[BonusPlay.Press]).isEqualTo(2)
        assertThat(byBonus[BonusPlay.`Gang Up`]).isEqualTo(1)
    }

    @Test
    fun `every bonus play has a description`() {
        BonusPlay.entries.forEach { bonus ->
            assertThat(bonus.description).isNotBlank
        }
    }
}
