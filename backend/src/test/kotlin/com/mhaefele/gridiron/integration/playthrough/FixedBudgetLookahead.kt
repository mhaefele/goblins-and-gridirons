package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.model.MatchState

/**
 * Arena seat over the Spring [LookaheadOpponentStrategy].
 * [thinkBudgetOverride] replaces [LookaheadOpponentStrategy.thinkBudgetNanosFor] for this seat,
 * so two seats can search with different limits than the match's coaches imply.
 * Each call returns how long that search took. This seat adds those up for the game summary.
 */
class FixedBudgetLookahead(
    private val lookahead: LookaheadOpponentStrategy,
    private val thinkBudgetOverride: Long,
) : OpponentStrategy {
    var searchMoves: Int = 0
        private set
    private var searchNanos: Long = 0L

    val averageThinkMs: Double
        get() = if (searchMoves == 0) 0.0 else searchNanos / 1_000_000.0 / searchMoves

    fun resetSearchSummary() {
        searchMoves = 0
        searchNanos = 0L
    }

    override fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction {
        val chosen = lookahead.chooseAction(state, legal, thinkBudgetOverride)
        chosen.searchNanos?.let { nanos ->
            searchMoves++
            searchNanos += nanos
        }
        return chosen.action
    }
}
