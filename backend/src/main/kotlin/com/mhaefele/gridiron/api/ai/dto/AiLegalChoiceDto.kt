package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.api.dto.response.PotentialChallengeClaim
import com.mhaefele.gridiron.shared.LegalActionId

/**
 * One legal choice in AI form — [kind] + ordered free [factors] + [sugarFactors] + submit sidecar.
 *
 * ## Factors (schema 17+)
 *
 * [factors] = free choices (packed into [AiLegalPacker] legal-row ints).
 * [sugarFactors] = pending-fixed identity / attached bonus / source square — also packed onto the row.
 * [legalActionId] still encodes full domain identity for submit.
 * Pack columns: identity (actor, kindIndex, boardTarget, challengeSlot, attachedBonus, face0..2)
 * then metadata (sourceSquare). Who-id = roster actor, not source coordinate.
 *
 * | kind | free factors |
 * |------|--------------|
 * | path / throw / block / foul / standUp / reserves / declareRun / throwTeammateSelect | `[Actor, BoardTarget]` |
 * | `throwTeammate` / `quickReleaseThrow` / `placeLooseBall` | `[BoardTarget]` (actor sugar) |
 * | `chooseBlockFaceOrder` | `[FaceOrder]` (actor sugar) |
 * | `skip` / `declineBonus` / `continueBlock` / `keepOffer` / `reroll` / adjust* / play-bonus / `cancelThrowTeammate` | `[]` |
 * | `claimChallenge` / `replaceChallenge` | `[ChallengeSlot]` |
 */
data class AiLegalChoiceDto(
    /**
     * Policy kind (`run`, `skip`, …) — human/debug; same as [AiLegalKind.name].
     * Packed as [kindIndex] on each legal row.
     */
    val kind: AiLegalKind,

    /**
     * 0-based [AiLegalKind.ordinal] — packed on each legal row (schema 16+). Always equals [kind].ordinal.
     */
    val kindIndex: Int,

    /** Free decision factors after [kind], in question order. Empty when kind needs no further pick. */
    val factors: List<AiFactor>,

    /**
     * Pending-fixed factors (not free). Omitted from MultiDiscrete matching; still useful for
     * humans / exports. Sugar also lives inside [legalActionId] for engine submit.
     */
    val sugarFactors: List<AiFactor> = emptyList(),

    /** Sidecar for `POST /api/ai/matches/{id}/submit` (and GUI `POST .../legal-actions/submit`) — not part of the policy output vector. */
    val legalActionId: LegalActionId,

    /**
     * Challenge cards this action could claim (same as GUI [LegalAction.potentialChallengeClaims]).
     * Harness / shaping sidecar — not part of the MultiDiscrete factor tuple.
     */
    val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),

    /**
     * Would grade [TrainingDrillStatus.PASS] on the opening coach turn.
     * Choice drills (throw target, marked-run follow-up, …) may flag a strict PASS subset while
     * [softMaskLegal] covers a wider action class (e.g. all carrier throws).
     */
    val passingLegal: Boolean = false,

    /**
     * Rows that receive soft-mask logit bias. Does **not** remove other legal rows.
     */
    val softMaskLegal: Boolean = false,
) {
    init {
        require(kindIndex == kind.ordinal) { "kindIndex=$kindIndex != kind.ordinal=${kind.ordinal} for $kind" }
    }
}
