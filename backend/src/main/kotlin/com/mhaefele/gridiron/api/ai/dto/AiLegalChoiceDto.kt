package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.api.dto.response.PotentialChallengeClaim
import com.mhaefele.gridiron.shared.LegalActionId

/**
 * One legal choice in AI form — [kind] + non-pitch [factors] / [sugarFactors] + pitch
 * [target] / [source] + submit sidecar.
 *
 * ## Pitch (schema 19+)
 *
 * Dest and actor-from squares are [AiPitchSquare] on the row — not [AiFactor]s.
 * Pack columns: identity (actor, kindIndex, targetX, targetY, challengeSlot, attachedBonus,
 * face0..2) then metadata (sourceX, sourceY). Who-id = roster actor, not source coords.
 *
 * | kind | free factors | target |
 * |------|--------------|--------|
 * | path / throw / block / foul / standUp / reserves / declareRun / throwTeammateSelect | `[Actor]` | dest |
 * | `throwTeammate` / `quickReleaseThrow` / `placeLooseBall` | `[]` (actor sugar) | dest |
 * | `chooseBlockFaceOrder` | `[FaceOrder]` (actor sugar) | — |
 * | `skip` / `declineBonus` / `continueBlock` / `keepOffer` / `reroll` / adjust* / play-bonus / `cancelThrowTeammate` | `[]` | sometimes dest (continue / Do-Over) |
 * | `claimChallenge` / `replaceChallenge` | `[ChallengeSlot]` | — |
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

    /** Free non-pitch factors after [kind], in question order. Empty when kind needs none. */
    val factors: List<AiFactor>,

    /**
     * Pending-fixed non-pitch factors (actor sugar, attached bonus, …).
     * Sugar also lives inside [legalActionId] for engine submit.
     */
    val sugarFactors: List<AiFactor> = emptyList(),

    /** Destination / path end / … — perspective 0-based pitch; null when kind has no dest. */
    val target: AiPitchSquare? = null,

    /**
     * Acting player’s current pitch square (metadata); null when dugout / no actor on pitch.
     * Who-id remains roster [AiFactor.Actor].
     */
    val source: AiPitchSquare? = null,

    /** Sidecar for `POST /api/ai/matches/{id}/submit` (and GUI `POST .../legal-actions/submit`) — not part of the policy output vector. */
    val legalActionId: LegalActionId,

    /**
     * Challenge cards this action could claim (same as GUI LegalAction.potentialChallengeClaims).
     * Harness / shaping sidecar — not part of the packed factor tuple.
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
