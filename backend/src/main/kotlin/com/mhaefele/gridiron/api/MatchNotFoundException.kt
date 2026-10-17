package com.mhaefele.gridiron.api

import com.mhaefele.gridiron.shared.MatchId

class MatchNotFoundException(val matchId: MatchId) :
    RuntimeException("Match not found: ${matchId.value}")
