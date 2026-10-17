package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.springframework.stereotype.Component
import java.util.UUID

/** Test helper — fixed-coach setup persisted via [InMemoryMatchRepository]. */
@Component
class MatchBootstrap(
    private val repository: InMemoryMatchRepository,
) {

    fun getMatch(matchId: MatchId): MatchState = repository.findById(matchId)

    /** Persist a mid-game [MatchState] so action POSTs can drive it through the HTTP API. */
    fun seedMatch(state: MatchState): MatchId {
        repository.save(state)
        return state.id
    }

    fun seedMatch(
        board: Board,
        actionsRemaining: Int = 3,
        activeCoach: TeamSide = TeamSide.HOME,
        id: MatchId = MatchId(UUID.randomUUID().toString()),
    ): MatchId =
        seedMatch(
            matchState(
                board = board,
                id = id,
                activeCoach = activeCoach,
                actionsRemaining = actionsRemaining,
            ),
        )
}
