package com.mhaefele.gridiron.shared

data class PlayerId(override val value: String) : DomainId {
    init {
        requireDomainId(value)
    }
}
