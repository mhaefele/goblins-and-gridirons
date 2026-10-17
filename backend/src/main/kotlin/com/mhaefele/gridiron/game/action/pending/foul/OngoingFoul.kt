package com.mhaefele.gridiron.game.action.pending.foul

import com.mhaefele.gridiron.shared.PlayerId

data class OngoingFoul(
    val playerId: PlayerId,
    val stage: FoulStage = FoulStage.DONE,
) {
    enum class FoulStage {
        DONE,
        ;

        val awaitsInput: Boolean get() = false
    }

    val awaitsInput: Boolean get() = stage.awaitsInput
}
