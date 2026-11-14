package com.mhaefele.gridiron.api.ai.dto.internal

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout

/**
 * Sole null→vector step for packed obs / legal rows.
 *
 * Nested **JSON wire** uses 0-based ordinals ([AiEnumOrdinal]) or JSON null.
 * Flat vector: **`null` → [AiObsLayout.SENTINEL]**; present enum → `ordinal`; present int → value;
 * boolean → `0`/`1`.
 */
object AiObsEncoding {
    fun <E : Enum<E>> E?.toObsCode(): Int = this?.ordinal ?: AiObsLayout.SENTINEL

    fun wireOrdinalToObsCode(ordinal: Int?): Int = ordinal ?: AiObsLayout.SENTINEL

    fun Int?.toObsCode(): Int = this ?: AiObsLayout.SENTINEL

    fun Boolean.toObsCode(): Int = if (this) 1 else 0
}
