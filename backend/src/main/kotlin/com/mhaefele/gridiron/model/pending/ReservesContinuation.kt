package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.game.action.pending.reserves.OngoingReserves
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.PlayerId

data class ReservesContinuation(
    val ongoingReserves: OngoingReserves,
) : ActionContinuation() {
    override val playDecisionType: PlayerDecisionType = PlayerDecisionType.RESERVES_CONTINUE
    override val localAwaitsCoachInput: Boolean get() = ongoingReserves.awaitsInput
    override fun isFinished(): Boolean =
        ongoingReserves.stage == OngoingReserves.ReservesStage.DONE

    override val localPromptPlayerId: PlayerId? get() = ongoingReserves.playerId
    override val localPromptBonusPlay: BonusPlay?
        get() = if (ongoingReserves.stage == OngoingReserves.ReservesStage.HANDLE_FRESH_LEGS_BONUS) BonusPlay.`Fresh Legs` else null
}
