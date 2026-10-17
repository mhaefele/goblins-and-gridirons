package com.mhaefele.gridiron.shared

data class MatchId(override val value: String) : DomainId {
    init {
        requireDomainId(value)
    }
}
