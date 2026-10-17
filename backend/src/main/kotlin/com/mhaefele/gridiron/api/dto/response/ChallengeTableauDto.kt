package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.game.action.dto.ChallengeCardDto
import com.mhaefele.gridiron.model.catalog.BonusPlay
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Public challenge tableau — active slots, deck top, and discard (no bonus-play text).")
data class ChallengeTableauDto(
    @field:Schema(description = "Three active slots; null entry = empty slot awaiting refresh.")
    val activeSlots: List<ChallengeCardDto?>,
    @field:Schema(description = "Face-up card on top of the deck; null when the deck is empty.", nullable = true)
    val deckTop: ChallengeCardDto?,
    @field:Schema(description = "Cards remaining in the deck (excluding deck top).")
    val deckRemaining: Int,
    @field:Schema(description = "Cards in the discard pile.")
    val discardCount: Int,
    @field:Schema(description = "Challenge cards claimed by the active coach this turn.")
    val claimsThisTurn: Int,
)

@Schema(description = "One coach's hidden bonus-play hand.")
data class BonusHandDto(
    @field:Schema(description = "Bonus cards currently in hand.")
    val cards: List<BonusHandCardDto>,
)

@Schema(description = "One bonus card in a coach's hand.")
data class BonusHandCardDto(
    @field:Schema(description = "Bonus play type (e.g. `Sprint`, `Stick Close`).")
    val bonusPlay: BonusPlay,
    @field:Schema(description = "Challenge title this bonus was claimed from.")
    val challengeTitle: String,
    @field:Schema(description = "Points value when the parent challenge was claimed.")
    val points: Int,
) {
    @get:Schema(description = "Bonus-play card text.")
    val description: String get() = bonusPlay.description
}
