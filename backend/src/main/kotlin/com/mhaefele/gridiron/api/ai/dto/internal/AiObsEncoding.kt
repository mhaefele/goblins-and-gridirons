package com.mhaefele.gridiron.api.ai.dto.internal

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout

/**
 * Numeric codes when flattening DTO fields into the obs vector.
 *
 * Nested **JSON wire** uses 0-based ordinals ([AiEnumOrdinal]); this is the **vector** step:
 *
 * - Enums: JSON/`null` → `0`, else `ordinal + 1`
 * - Optional ints: `null` → [AiObsLayout.SENTINEL]
 * - Booleans: `false` → `0`, `true` → `1`
 */
object AiObsEncoding {
    fun <E : Enum<E>> E?.toObsCode(): Int = if (this == null) 0 else ordinal + 1

    fun wireOrdinalToObsCode(ordinal: Int?): Int = if (ordinal == null) 0 else ordinal + 1

    fun Int?.toObsCode(): Int = this ?: AiObsLayout.SENTINEL

    fun Boolean.toObsCode(): Int = if (this) 1 else 0
}
