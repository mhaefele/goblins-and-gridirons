package com.mhaefele.gridiron.api.ai.dto

/**
 * AI legal-choice kind — policy vocabulary (schema 16+), not 1:1 with every GUI
 * [com.mhaefele.gridiron.api.dto.response.LegalAction] Jackson name.
 *
 * Packed as [AiLegalChoiceDto.kindIndex] (= [ordinal]) on each legal row.
 * Kind-axis width is [AiLegalKind.entries].size.
 *
 * Schema 16 drops MultiDiscrete-era coarse merges: Skip / DeclineBonus / ContinueBlock /
 * keep-reroll and the three Do-Over adjusts are distinct kinds; [cancelThrowTeammate] and
 * [chooseBlockFaceOrder] are included (no longer GUI-only omissions).
 *
 * `` `throw` `` / `` `reroll` `` are backticked only because they are Kotlin keywords;
 * [name] is still `"throw"` / `"reroll"`.
 *
 * TODO(AI2.19): promote to a shared (non-`Ai*`) wire kind on [LegalAction] DTOs — GUI + AI
 * read the same catalog; drop this AI-only mirror once ordinals / Jackson names land on the
 * engine wire. Still investigate per-candidate vs [ActionType] collapses (reroll/keep, …).
 */
enum class AiLegalKind {
    run,
    mark,
    sidestep,
    `throw`,
    throwTeammateSelect,
    throwTeammate,
    cancelThrowTeammate,
    block,
    foul,
    standUp,
    reserves,
    skip,
    declineBonus,
    continueBlock,
    keepOffer,
    placeLooseBall,
    `reroll`,
    adjustThrowByDoOver,
    adjustBlockByDoOver,
    adjustArmourByDoOver,
    quickReleaseThrow,
    declareRun,
    playGangUp,
    playHoldTheLine,
    playExtraEffort,
    playMomentum,
    playAllInTackle,
    playSlipAway,
    playCutOff,
    playStickClose,
    playBackOnYourFeet,
    playFreshLegs,
    playBrace,
    playUnderdog,
    playSpikedGauntlets,
    playDeadEye,
    playHailMary,
    playSprint,
    chooseBlockFaceOrder,
    claimChallenge,
    replaceChallenge,
    ;

    companion object {
        fun fromWireName(wireName: String): AiLegalKind =
            entries.find { it.name == wireName } ?: throw IllegalArgumentException("Unknown AiLegalKind: $wireName")
    }
}
