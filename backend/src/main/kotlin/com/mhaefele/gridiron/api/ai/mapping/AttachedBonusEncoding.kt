package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.model.catalog.BonusPlay

/**
 * Pack column codes for [com.mhaefele.gridiron.api.dto.response.LegalAction.requiredBonusCard]
 * (geometry twins **and** [com.mhaefele.gridiron.api.dto.response.LegalAction.BonusCardChoice]).
 *
 * Same codes as the hand tensor: [AiObsLayout.ATTACHED_BONUS_UNUSED]=0 none;
 * `1..BONUS_PLAY_COUNT` = [BonusPlay.ordinal]+1.
 */
internal object AttachedBonusEncoding {
    init {
        check(BonusPlay.entries.size == AiObsLayout.BONUS_PLAY_COUNT) { "BonusPlay.entries=${BonusPlay.entries.size} != AiObsLayout.BONUS_PLAY_COUNT=${AiObsLayout.BONUS_PLAY_COUNT}" }
    }

    fun indexFor(bonus: BonusPlay): Int = bonus.ordinal + 1
}
