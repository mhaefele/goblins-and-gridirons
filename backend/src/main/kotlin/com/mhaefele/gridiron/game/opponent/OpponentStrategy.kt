package com.mhaefele.gridiron.game.opponent

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState

/** Port — pick one submit command from the closed legal set for the active coach. */
fun interface OpponentStrategy {
    fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction
}
