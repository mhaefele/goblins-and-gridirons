package com.mhaefele.gridiron.model.catalog

/**
 * Built-in challenge/bonus deck — 26 physical card instances.
 * Pairings and copy counts are mirrored in `reference/cards.md`.
 */
object ChallengeCardCatalog {
    private val byId: Map<ChallengeCardId, ChallengeCardDefinition> = listOf(card(1, ChallengeClaimCondition.GRAB_IT, BonusPlay.`Stick Close`), card(2, ChallengeClaimCondition.GRAB_IT, BonusPlay.`Slip Away`), card(3, ChallengeClaimCondition.HUDDLE_UP, BonusPlay.`Fresh Legs`), card(4, ChallengeClaimCondition.PUSH_FORWARD, BonusPlay.Weave), card(5, ChallengeClaimCondition.PUSH_FORWARD, BonusPlay.`Do-Over`), card(6, ChallengeClaimCondition.FAN_OUT, BonusPlay.`Fresh Legs`), card(7, ChallengeClaimCondition.PLAY_TO_THE_STANDS, BonusPlay.`Hail Mary Pass`), card(8, ChallengeClaimCondition.ALL_HANDS, BonusPlay.Press), card(9, ChallengeClaimCondition.CONNECT, BonusPlay.`Extra Effort`), card(10, ChallengeClaimCondition.CONNECT, BonusPlay.`Ice Cold`), card(11, ChallengeClaimCondition.STRIP_IT, BonusPlay.Momentum), card(12, ChallengeClaimCondition.STRIP_IT, BonusPlay.`Cut Off`), card(13, ChallengeClaimCondition.KEEP_RUNNING, BonusPlay.Sprint), card(14, ChallengeClaimCondition.KEEP_RUNNING, BonusPlay.Press), card(15, ChallengeClaimCondition.HOLD_THE_PIT, BonusPlay.`Back on Your Feet`), card(16, ChallengeClaimCondition.THREAD_THE_NEEDLE, BonusPlay.`Dead Eye`), card(17, ChallengeClaimCondition.ALL_HANDS, BonusPlay.Brace), card(18, ChallengeClaimCondition.ALL_HANDS, BonusPlay.`Extra Effort`), card(19, ChallengeClaimCondition.STAR_TURN, BonusPlay.`Spiked Gauntlets`), card(20, ChallengeClaimCondition.STAR_TURN, BonusPlay.Underdog), card(21, ChallengeClaimCondition.DROP_THEM, BonusPlay.`Gang Up`), card(22, ChallengeClaimCondition.DROP_THEM, BonusPlay.`All-In Tackle`), card(23, ChallengeClaimCondition.LOCK_THEM_DOWN, BonusPlay.`Do-Over`), card(24, ChallengeClaimCondition.LOCK_THEM_DOWN, BonusPlay.`Hold the Line`), card(25, ChallengeClaimCondition.SEND_THEM_OFF, BonusPlay.`Slip Away`), card(26, ChallengeClaimCondition.SEND_THEM_OFF, BonusPlay.`Do-Over`)).associateBy { it.id }

    fun allCards(): List<ChallengeCardDefinition> = byId.values.sortedBy { it.deckIndex }

    fun allIds(): List<ChallengeCardId> = allCards().map { it.id }

    fun card(id: ChallengeCardId): ChallengeCardDefinition =
        byId[id] ?: throw IllegalArgumentException("Unknown challenge card: ${id.value}")

    fun card(deckIndex: Int): ChallengeCardDefinition =
        card(ChallengeCardId(idValue(deckIndex)))

    /** Resolve catalog instances for [titles] in order (each title consumes the next unused copy). */
    fun cardsForTitles(titles: List<ChallengeClaimCondition>): List<ChallengeCardDefinition> {
        val remaining = allCards().toMutableList()
        return titles.map { title ->
            val index = remaining.indexOfFirst { it.challenge == title }
            check(index >= 0) { "No unused challenge card left for $title" }
            remaining.removeAt(index)
        }
    }

    fun idsForTitles(titles: List<ChallengeClaimCondition>): List<ChallengeCardId> =
        cardsForTitles(titles).map { it.id }

    private fun card(
        deckIndex: Int,
        challenge: ChallengeClaimCondition,
        bonusPlay: BonusPlay,
    ): ChallengeCardDefinition =
        ChallengeCardDefinition(
            id = ChallengeCardId(idValue(deckIndex)),
            deckIndex = deckIndex,
            challenge = challenge,
            bonusPlay = bonusPlay,
        )

    private fun idValue(deckIndex: Int): String = "c%02d".format(deckIndex)
}
