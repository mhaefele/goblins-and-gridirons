package com.mhaefele.gridiron.game.opponent.search

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.PlayerActionExecutor
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.game.turn.TurnEngine
import com.mhaefele.gridiron.model.MatchState
import org.springframework.stereotype.Component

/**
 * Shared in-process apply for search dry-runs — takeAction + advance only.
 *
 * No settle policy, no scoring, no candidate ranking, no dice. Tree search that needs menus
 * after apply uses [DeterministicActionSearch] or a private lookahead settle heuristic.
 */
@Component
class DryRunApply(
    private val executor: PlayerActionExecutor,
    private val turnEngine: TurnEngine,
) {
    fun play(state: MatchState, action: LegalAction): MatchState {
        val session = MatchMutator(state)
        executor.takeAction(session, action)
        turnEngine.advance(session)
        return session.toMatchState()
    }
}
