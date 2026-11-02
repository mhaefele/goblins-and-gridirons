package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.model.catalog.BonusPlay

/**
 * Pack column index for geometry-attached bonuses.
 *
 * Only geometry-attached bonuses ([ActionAttachedBonuses.expandFromHand]) set
 * [com.mhaefele.gridiron.api.dto.response.LegalAction.requiredBonusCard] on path/throw actions.
 * Packed on each legal row (schema 16+) so Sprint/Weave twins to the same dest stay distinct.
 */
internal object AttachedBonusEncoding {
    private val INDEX_BY_BONUS = mapOf(
        BonusPlay.Sprint to 0,
        BonusPlay.Weave to 1,
        BonusPlay.Press to 2,
        BonusPlay.`Ice Cold` to 3,
        BonusPlay.`Hail Mary Pass` to 4,
    )

    fun indexFor(bonus: BonusPlay): Int =
        INDEX_BY_BONUS[bonus] ?: error("Bonus $bonus is not encoded as AttachedBonus")
}
