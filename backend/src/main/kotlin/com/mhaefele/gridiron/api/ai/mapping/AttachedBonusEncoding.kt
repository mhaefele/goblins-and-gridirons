package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.model.catalog.BonusPlay

/**
 * [com.mhaefele.gridiron.api.ai.dto.AiFactor.AttachedBonus] sugar index for geometry cards.
 *
 * Only geometry-attached bonuses ([ActionAttachedBonuses.expandFromHand]) set
 * [com.mhaefele.gridiron.api.dto.response.LegalAction.requiredBonusCard] on path/throw actions.
 * Indices are sugar (not a MultiDiscrete head). When free keys collide, the AI mapper
 * prefers the lower index (Sprint before Weave, …).
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
