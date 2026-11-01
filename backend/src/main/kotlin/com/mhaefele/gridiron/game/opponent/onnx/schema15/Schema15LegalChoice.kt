package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

import com.mhaefele.gridiron.shared.LegalActionId

/** One schema-15 legal row + submit sidecar. */
data class Schema15LegalChoice(
    val kind: Schema15LegalKind,
    val kindIndex: Int,
    val factors: List<Schema15Factor>,
    val sugarFactors: List<Schema15Factor> = emptyList(),
    val legalActionId: LegalActionId,
) {
    init {
        require(kindIndex == kind.ordinal) { "kindIndex=$kindIndex != kind.ordinal=${kind.ordinal} for $kind" }
    }
}
