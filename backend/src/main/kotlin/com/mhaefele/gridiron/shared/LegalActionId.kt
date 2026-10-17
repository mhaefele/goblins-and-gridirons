package com.mhaefele.gridiron.shared

data class LegalActionId(override val value: String) : DomainId {
    init {
        requireDomainId(value)
    }
}
