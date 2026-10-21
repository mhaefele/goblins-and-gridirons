package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.model.MatchState

/**
 * Arena seat over the Spring [LookaheadOpponentStrategy].
 * [thinkBudgetOverride] replaces [LookaheadOpponentStrategy.thinkBudgetNanosFor] for this seat,
 * so two seats can search with different limits than the match's coaches imply.
 * [telemetry] is null when this seat does not print search time. The single-game arena passes a
 * counter so its summary can print search count and average think time.
 */
class FixedBudgetLookahead(
    private val lookahead: LookaheadOpponentStrategy,
    private val thinkBudgetOverride: Long,
    val telemetry: LookaheadOpponentStrategy.SearchTelemetry? = null,
) : OpponentStrategy {
    override fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction =
        lookahead.chooseAction(state, legal, thinkBudgetOverride, telemetry)
}
