package com.mhaefele.gridiron.shared

/** Shared contract for type-safe string identifiers. Each ID is a distinct [data class]. */
interface DomainId {
    val value: String
}

/** Call from string [DomainId] data-class `init` blocks — blank values are never valid. */
fun requireDomainId(value: String) {
    require(value.isNotBlank()) { "DomainId value must not be blank" }
}
