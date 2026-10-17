package com.mhaefele.gridiron.game.action.pending.reserves

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId

data class OngoingReserves(
    val playerId: PlayerId,
    val placement: Coordinate,
    val ballEncounter: BallEncounter? = null,
    val stage: ReservesStage,
) {
    enum class ReservesStage {
        HANDLE_FRESH_LEGS_BONUS,
        DONE,
        ;

        val awaitsInput: Boolean
            get() = this == HANDLE_FRESH_LEGS_BONUS
    }

    val awaitsInput: Boolean get() = stage.awaitsInput
}
