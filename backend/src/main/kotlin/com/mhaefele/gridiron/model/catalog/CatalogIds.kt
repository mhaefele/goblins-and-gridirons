package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.shared.DomainId
import com.mhaefele.gridiron.shared.requireDomainId

/** Catalog key for a team card roster ([TeamCard]). */
data class TeamCatalogId(override val value: String) : DomainId {
    init {
        requireDomainId(value)
    }
}

/** Catalog key for a pitch layout ([BoardDefinition]). */
data class PitchDefinitionId(override val value: String) : DomainId {
    init {
        requireDomainId(value)
    }
}
