package com.mhaefele.gridiron.api.ai.training.claim

import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition

/**
 * Pins a challenge tableau for claim drills: [focus] in slot 0, then [fillers] that the
 * scripted action family cannot also claim — so the model attends to one condition.
 */
internal object ChallengeClaimTableau {
    /** Past Coach A's turn-1 claim gate; still early enough for a full three-action turn. */
    const val OPENING_TURN = 3

    /** Focus in slot 0; bait slots filled by [ClaimFillerSelector.assembleFocusReplaceKeepSet] after placement. */
    fun withFocusOnly(focus: ChallengeClaimCondition): CardState {
        val focusCard = ChallengeCardCatalog.cardsForTitles(listOf(focus)).single()
        return CardState(
            activeSlots = listOf(focusCard, null, null),
            deck = ChallengeCardCatalog.allCards().filter { it.id != focusCard.id },
        )
    }

    fun withFocus(
        focus: ChallengeClaimCondition,
        vararg fillers: ChallengeClaimCondition,
    ): CardState {
        require(fillers.size == 2) { "Claim drills pin focus + exactly 2 fillers, was ${fillers.size}" }
        require(focus !in fillers) { "Focus $focus must not appear in fillers $fillers" }
        val titles = listOf(focus) + fillers.toList()
        val actives = ChallengeCardCatalog.cardsForTitles(titles)
        val used = actives.map { it.id }.toSet()
        return CardState(
            activeSlots = actives,
            deck = ChallengeCardCatalog.allCards().filter { it.id !in used },
        )
    }
}
