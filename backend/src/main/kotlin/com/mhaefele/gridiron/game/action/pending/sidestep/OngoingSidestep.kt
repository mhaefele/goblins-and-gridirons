package com.mhaefele.gridiron.game.action.pending.sidestep

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

data class OngoingSidestep(
    val playerId: PlayerId,
    val stage: SidestepStage,
    val vacatedSquare: Coordinate,
    val eligibleMarkerIds: List<PlayerId>,
    val respondingCoach: TeamSide? = null,
) {
    init {
        when (stage) {
            SidestepStage.OFFER_STICK_CLOSE, SidestepStage.DONE -> require(respondingCoach == null) { "respondingCoach only at HANDLE_STICK_CLOSE" }
            SidestepStage.HANDLE_STICK_CLOSE                    -> requireNotNull(respondingCoach) { "HANDLE_STICK_CLOSE requires respondingCoach" }
        }
    }

    enum class SidestepStage {
        OFFER_STICK_CLOSE,
        HANDLE_STICK_CLOSE,
        DONE,
        ;

        val awaitsInput: Boolean
            get() = this == HANDLE_STICK_CLOSE
    }
}

class OngoingSidestepMutator(
    val playerId: PlayerId,
    var stage: OngoingSidestep.SidestepStage,
    var vacatedSquare: Coordinate,
    var eligibleMarkerIds: List<PlayerId>,
    var respondingCoach: TeamSide? = null,
) {
    constructor(ongoing: OngoingSidestep) : this(
        ongoing.playerId,
        ongoing.stage,
        ongoing.vacatedSquare,
        ongoing.eligibleMarkerIds,
        ongoing.respondingCoach,
    )

    val ongoingSidestep: OngoingSidestep
        get() = OngoingSidestep(playerId, stage, vacatedSquare, eligibleMarkerIds, respondingCoach)
}
