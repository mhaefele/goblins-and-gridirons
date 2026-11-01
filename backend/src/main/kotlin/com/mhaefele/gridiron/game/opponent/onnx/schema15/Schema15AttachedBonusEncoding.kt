package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

import com.mhaefele.gridiron.model.catalog.BonusPlay

/** Schema-15 AttachedBonus sugar indices (frozen; match main at schema 15). */
object Schema15AttachedBonusEncoding {
    private val INDEX_BY_BONUS = mapOf(
        BonusPlay.Sprint to 0,
        BonusPlay.Weave to 1,
        BonusPlay.Press to 2,
        BonusPlay.`Ice Cold` to 3,
        BonusPlay.`Hail Mary Pass` to 4,
    )

    fun indexFor(bonus: BonusPlay): Int =
        INDEX_BY_BONUS[bonus] ?: error("Bonus $bonus is not encoded as Schema15 AttachedBonus")
}
