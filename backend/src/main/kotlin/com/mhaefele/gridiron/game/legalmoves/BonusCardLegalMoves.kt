package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.playBonusLegalAction
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.TeamSide

/** Play* when [bonus] is in [coach]'s hand and unused this turn. */
fun playIfPresent(
    cards: CardState,
    coach: TeamSide,
    turn: TurnState,
    bonus: BonusPlay,
): List<LegalAction> {
    if (bonus in turn.bonusPlaysUsedThisTurn) return emptyList()
    if (!cards.hasBonusInHand(coach, bonus)) return emptyList()
    return listOf(playBonusLegalAction(bonus))
}

internal fun hasDoOver(cards: CardState, coach: TeamSide, turn: TurnState): Boolean {
    if (BonusPlay.`Do-Over` in turn.bonusPlaysUsedThisTurn) return false
    return cards.hasBonusInHand(coach, BonusPlay.`Do-Over`)
}
