package com.mhaefele.gridiron.game.action.pending.ballguard

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId

data class OngoingBallGuard(
    val playerId: PlayerId,
    val dropAt: Coordinate,
    val stage: BallGuardStage,
) {
    enum class BallGuardStage {
        HANDLE_PLACE,
        DONE,
        ;

        val awaitsInput: Boolean
            get() = this == HANDLE_PLACE
    }
}

class OngoingBallGuardMutator(
    val playerId: PlayerId,
    val dropAt: Coordinate,
    var stage: OngoingBallGuard.BallGuardStage,
) {
    constructor(ongoing: OngoingBallGuard) : this(ongoing.playerId, ongoing.dropAt, ongoing.stage)

    val ongoingBallGuard: OngoingBallGuard
        get() = OngoingBallGuard(playerId, dropAt, stage)
}
