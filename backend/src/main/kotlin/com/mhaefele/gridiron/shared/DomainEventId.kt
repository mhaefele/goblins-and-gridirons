package com.mhaefele.gridiron.shared

/** Per-match monotonic event sequence — first stored event for a match is `domainId = 1`. */
data class DomainEventId(
    val matchId: MatchId,
    val domainId: Int,
) {
    init {
        require(domainId >= 1) { "domainId must be >= 1, got $domainId" }
    }
}
