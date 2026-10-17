package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.shared.TurnPhase

class ClaimChallenge(
    val eligibleSlotIndexes: List<Int>,
    val resolved: Boolean = false,
) : PendingDecision(), PendingDecisionSlot.TopLevel {
    init {
        require(eligibleSlotIndexes.size >= 2) { "ClaimChallenge needs ≥2 eligible slots, was ${eligibleSlotIndexes.size}" }
        require(eligibleSlotIndexes.all { it in 0..2 }) { "ClaimChallenge slots must be 0..2, was $eligibleSlotIndexes" }
    }

    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.CLAIM_CHALLENGE
    override val phase: TurnPhase = TurnPhase.PLAYER_ACTION
    override val localAwaitsCoachInput: Boolean get() = !resolved
}
