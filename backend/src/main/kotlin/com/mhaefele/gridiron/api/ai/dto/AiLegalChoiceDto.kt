package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.api.dto.response.LegalAction

/**
 * One harness legal row — engine [LegalAction] plus AI-only curriculum flags.
 *
 * Packed `legalMoves` are derived separately ([com.mhaefele.gridiron.api.ai.mapping.AiLegalPacker]).
 * Board / roster facts live on [HarnessRewardSnapshotDto.players] — join by `playerId` / coords.
 * Submit via [LegalAction.id].
 */
data class AiLegalChoiceDto(
    val action: LegalAction,
    /**
     * Would grade [TrainingDrillStatus.PASS] on the opening coach turn.
     * Choice drills may flag a strict PASS subset while [softMaskLegal] covers a wider class.
     */
    val passingLegal: Boolean = false,
    /**
     * Rows that receive soft-mask logit bias. Does **not** remove other legal rows.
     */
    val softMaskLegal: Boolean = false,
)
