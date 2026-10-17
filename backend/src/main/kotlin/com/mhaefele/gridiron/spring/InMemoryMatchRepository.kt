package com.mhaefele.gridiron.spring

import com.mhaefele.gridiron.api.MatchNotFoundException
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.model.MatchState
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemoryMatchRepository {
    private val matches = ConcurrentHashMap<MatchId, MatchState>()

    fun save(state: MatchState) {
        matches[state.id] = state
    }

    fun findById(matchId: MatchId): MatchState =
        matches[matchId] ?: throw MatchNotFoundException(matchId)

    fun delete(matchId: MatchId) {
        matches.remove(matchId)
    }
}
