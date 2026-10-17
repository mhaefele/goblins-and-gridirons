package com.mhaefele.gridiron.game.action.pending.standup

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId

data class OngoingStandUp(
    val playerId: PlayerId,
    val targetCoordinate: Coordinate,
    val stage: StandUpStage,
) {
    enum class StandUpStage {
        HANDLE_BACK_ON_YOUR_FEET,
        DONE,
        ;

        val awaitsInput: Boolean
            get() = this == HANDLE_BACK_ON_YOUR_FEET
    }

    val awaitsInput: Boolean get() = stage.awaitsInput
}
