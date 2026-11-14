package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.nested.AiCellObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiChallengeSlotObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiHeldCheckObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedObsDto
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AiObsPackerTest {
    private val packer = AiObsPacker()

    @Test
    fun `pack length matches OBSERVATION_VECTOR_LENGTH`() {
        val obs = emptyObs()
        val packed = packer.pack(obs)
        assertThat(packed).hasSize(AiObsLayout.OBSERVATION_VECTOR_LENGTH)
        assertThat(AiObsLayout.OBSERVATION_VECTOR_LENGTH).isEqualTo(4477)
    }

    @Test
    fun `pack scalars prefix matches declaration order`() {
        val obs = emptyObs().copy(
            turnNumber = 3,
            phase = TurnPhase.PLAYER_ACTION,
            actionsRemaining = 2,
            scoreUs = 4,
            scoreThem = 1,
        )
        val packed = packer.pack(obs)
        assertThat(packed[0]).isEqualTo(3f)
        assertThat(packed[1]).isEqualTo(TurnPhase.PLAYER_ACTION.ordinal.toFloat())
        assertThat(packed[2]).isEqualTo(2f)
        assertThat(packed[3]).isEqualTo(4f)
        assertThat(packed[4]).isEqualTo(1f)
    }

    private fun emptyObs(): AiNestedObsDto =
        AiNestedObsDto(
            turnNumber = 1,
            phase = TurnPhase.GAME_START,
            actionsRemaining = 0,
            scoreUs = 0,
            scoreThem = 0,
            touchdownsUs = 0,
            touchdownsThem = 0,
            challengeCardsUs = 0,
            challengeCardsThem = 0,
            pointsVictoryTurnsRemaining = null,
            emergencyReservesUs = 0,
            emergencyReservesThem = 0,
            claimsThisTurn = 0,
            oppHandSize = 0,
            modHoldTheLine = false,
            modGangUp = false,
            modAllInTackleProneAfterBlock = false,
            challenges = List(AiObsLayout.ACTIVE_CHALLENGE_SLOTS) { AiChallengeSlotObs(null, AiObsLayout.SENTINEL) },
            deckTopCondition = null,
            deckSize = 0,
            discardSize = 0,
            ownBonusPlay = List(AiObsLayout.BONUS_HAND_SLOTS) { null },
            heldCheck = AiHeldCheckObs.empty(),
            cells = List(AiObsLayout.BOARD_SLOT_COUNT) { AiCellObs.emptyBench() },
        )
}
