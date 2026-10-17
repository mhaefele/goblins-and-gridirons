package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase

/**
 * PLAYER_ACTION / OFFER_DANCE_STEP — free Sidestep (or Skip) after the three-action budget is spent.
 * Accept parks a [SidestepContinuation] on [delegate] as a FREE turn-slot action.
 * [remainingPlayerIds] queues further eligible Glade Dancers; Skip advances or resolves.
 */
data class OfferDanceStep(
    override val localRespondingCoach: TeamSide,
    val playerId: PlayerId,
    val remainingPlayerIds: List<PlayerId> = emptyList(),
    val resolved: Boolean = false,
    override val delegate: SidestepContinuation? = null,
) : PendingDecision(), PendingDecisionSlot.TopLevel {
    override val playDecisionType: PlayerDecisionType
        get() = delegate?.playDecisionType ?: PlayerDecisionType.DANCE_STEP_OFFER
    override val phase: TurnPhase = TurnPhase.PLAYER_ACTION
    override val localAwaitsCoachInput: Boolean get() = !resolved
    override val localPromptAbility: PlayerAbility? get() = PlayerAbility.`Dance Step`
    override val localPromptPlayerId: PlayerId? get() = playerId
}
