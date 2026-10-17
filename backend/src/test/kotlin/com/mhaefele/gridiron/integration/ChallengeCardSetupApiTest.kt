package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChallengeCardSetupApiTest : BaseApiTest() {

    @Test
    fun `create match deals three actives and a visible deck top`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val created = api.createMatch(api.defaultCreateMatchRequest())
        val challenges = created.snapshot.gameState.challenges
        val catalogOrder = ChallengeCardCatalog.allCards().map { it.challenge }

        // ScriptedDiceRoller identity-shuffles → catalog deal order
        assertThat(challenges.activeSlots.map { it!!.challengeCondition })
            .containsExactly(catalogOrder[0], catalogOrder[1], catalogOrder[2])
        assertThat(challenges.deckTop!!.challengeCondition).isEqualTo(catalogOrder[3])
        assertThat(challenges.deckRemaining).isEqualTo(23)
        assertThat(challenges.discardCount).isZero()
        assertThat(challenges.activeSlots.map { it!!.title }).doesNotContain("Stick Close", "Do-Over")
    }
}
