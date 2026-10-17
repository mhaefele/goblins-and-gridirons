package com.mhaefele.gridiron.api.mapping

import com.mhaefele.gridiron.api.dto.response.BonusHandCardDto
import com.mhaefele.gridiron.api.dto.response.BonusHandDto
import com.mhaefele.gridiron.api.dto.response.ChallengeTableauDto
import com.mhaefele.gridiron.game.action.dto.ChallengeCardDto
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.shared.TeamSide

/** Public tableau — challenge cards only; never includes bonus-play text. */
fun toChallengeTableauDto(cards: CardState): ChallengeTableauDto =
    ChallengeTableauDto(
        activeSlots = cards.activeSlots.map { it?.let(::toChallengeCardDto) },
        deckTop = cards.deckTop?.let(::toChallengeCardDto),
        deckRemaining = cards.deck.size,
        discardCount = cards.discard.size,
        claimsThisTurn = cards.claimsThisTurn,
    )

/**
 * Seat-private bonus hand. Call only for the viewing coach — never fold into a shared
 * snapshot that both seats read.
 */
fun toBonusHandDto(cards: CardState, seat: TeamSide): BonusHandDto =
    BonusHandDto(
        cards = cards.handCards(seat)
            .map { def ->
                BonusHandCardDto(
                    bonusPlay = def.bonusPlay,
                    challengeTitle = def.challenge.displayName,
                    points = def.points,
                )
            }
            .distinctBy { it.bonusPlay },
    )

private fun toChallengeCardDto(card: ChallengeCardDefinition): ChallengeCardDto =
    ChallengeCardDto(card.challenge)
