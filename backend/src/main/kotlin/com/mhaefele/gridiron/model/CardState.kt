package com.mhaefele.gridiron.model

import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Challenge tableau, deck, discard, and per-coach bonus hands for a match.
 *
 * Active slots show the challenge face (public). Hands hold claimed cards as bonus plays (secret).
 * [deck] is ordered with **index 0 = top** (visible challenge face when non-empty).
 *
 * Stores [ChallengeCardDefinition] throughout — catalog ids stay on [ChallengeCardDefinition.id]
 * for uniqueness / refund identity, not as a parallel currency callers translate through.
 */
data class CardState(
    /** Exactly three tableau slots; `null` = empty (awaiting Refresh). */
    val activeSlots: List<ChallengeCardDefinition?>,
    /** Remaining challenge deck — first element is the visible top. */
    val deck: List<ChallengeCardDefinition>,
    val discard: List<ChallengeCardDefinition> = emptyList(),
    /** Seat-private hands — prefer [handCards] / [handCard] / [hasBonusInHand]; raw lists for mutators. */
    internal val homeHand: List<ChallengeCardDefinition> = emptyList(),
    internal val awayHand: List<ChallengeCardDefinition> = emptyList(),
    /** Claims by the active coach this turn (Clean Sweep when this reaches 3). */
    val claimsThisTurn: Int = 0,
    /**
     * Ball ids the active coach's team held at the start of their PLAYER_ACTION window
     * (Grab It!). Cleared / refreshed when a coach's action window opens.
     */
    val ballsPossessedAtTurnStart: Set<BallId> = emptySet(),
) {
    init {
        require(activeSlots.size == 3) { "Challenge tableau must have exactly 3 slots, was ${activeSlots.size}" }
        require(claimsThisTurn >= 0) { "claimsThisTurn must be ≥ 0, was $claimsThisTurn" }
        val placed = activeSlots.filterNotNull() + deck + discard + homeHand + awayHand
        val ids = placed.map { it.id }
        require(ids.distinct().size == ids.size) { "Duplicate challenge card id in CardState: $ids" }
    }

    /** Non-null active tableau cards (slot order). */
    fun activeCards(): List<ChallengeCardDefinition> =
        activeSlots.filterNotNull()

    fun activeChallengeClaims(): List<ChallengeClaimCondition> =
        activeCards().map { it.challenge }

    fun handCards(team: TeamSide): List<ChallengeCardDefinition> =
        when (team) {
            TeamSide.HOME -> homeHand
            TeamSide.AWAY -> awayHand
        }

    /** First hand card whose bonus face is [bonus], or null if absent. */
    fun handCard(team: TeamSide, bonus: BonusPlay): ChallengeCardDefinition? =
        handCards(team).firstOrNull { it.bonusPlay == bonus }

    fun hasBonusInHand(team: TeamSide, bonus: BonusPlay): Boolean =
        handCard(team, bonus) != null

    /** Visible top of the challenge deck; null when the deck is empty. */
    val deckTop: ChallengeCardDefinition? get() = deck.firstOrNull()

    companion object {
        /**
         * Setup deal: [orderedDeck] is already shuffled (26 cards). Top three become active slots
         * 1→3; the rest remain as the deck with the next card visible on top.
         */
        fun deal(orderedDeck: List<ChallengeCardDefinition>): CardState {
            require(orderedDeck.size == ChallengeCardCatalog.allCards().size) { "Challenge deck must have ${ChallengeCardCatalog.allCards().size} cards, was ${orderedDeck.size}" }
            require(orderedDeck.map { it.id }.distinct().size == orderedDeck.size) { "Challenge deck has duplicates" }
            return CardState(
                activeSlots = listOf(orderedDeck[0], orderedDeck[1], orderedDeck[2]),
                deck = orderedDeck.drop(3),
            )
        }

        /** Deterministic deal in catalog deck order (tests / scripted create). */
        fun dealInCatalogOrder(): CardState = deal(ChallengeCardCatalog.allCards())
    }
}
