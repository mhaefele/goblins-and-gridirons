package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.api.dto.response.PotentialChallengeClaim
import com.mhaefele.gridiron.shared.LegalActionId

/**
 * One legal choice in AI form — [kind] + ordered free [factors] + [sugarFactors] + submit sidecar.
 *
 * ## Factors (schema 10)
 *
 * [factors] = **free choices only** (MultiDiscrete / `resolve_action` match key).
 * [sugarFactors] = pending-fixed identity (actor/target implied by continuation) — debug / docs;
 * not matched. [legalActionId] still encodes full domain identity for submit.
 * Harness joint head: free [AiFactor.Actor] playerIndex × [kindIndex] (actor `0` when omitted).
 *
 * | kind | free factors |
 * |------|--------------|
 * | path / throw / block / foul / standUp / reserves / declareRun / throwTeammateSelect | `[Actor, BoardTarget]` |
 * | `throwTeammate` / `quickReleaseThrow` / `placeLooseBall` | `[BoardTarget]` (actor sugar) |
 * | `declineOffer` / `reroll` / `adjustByDoOver` / play-bonus | `[]` |
 * | `claimChallenge` / `replaceChallenge` | `[ChallengeSlot]` |
 *
 * Geometry [AiFactor.AttachedBonus] rides in [sugarFactors] (schema 15+).
 */
data class AiLegalChoiceDto(
    /**
     * Policy kind (`run`, `declineOffer`, …) — human/debug; same as [AiLegalKind.name].
     * Not used as the MultiDiscrete kind head (see [kindIndex]).
     */
    val kind: AiLegalKind,

    /**
     * 0-based [AiLegalKind.ordinal] — joint MultiDiscrete axis with free actor in the harness
     * (`actorPlayerIndex * kindHeadSize + kindIndex`). Always equals [kind].ordinal.
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
