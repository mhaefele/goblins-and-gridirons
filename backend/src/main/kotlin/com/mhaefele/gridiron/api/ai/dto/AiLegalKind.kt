package com.mhaefele.gridiron.api.ai.dto

/**
 * Pack-only legal-choice kind vocabulary (schema 16+) — ordinal on each packed legal row.
 * Not on the harness wire; harness rows carry [com.mhaefele.gridiron.api.dto.response.LegalAction].
 *
 * Kind-axis width is [AiLegalKind.entries].size. Not 1:1 with every GUI Jackson `kind`
 * (`Choose*Reroll` → [keepOffer] / [reroll]).
 *
 * `` `throw` `` / `` `reroll` `` are backticked only because they are Kotlin keywords;
 * [name] is still `"throw"` / `"reroll"`.
 *
 * TODO(AI2.19): promote to a shared (non-`Ai*`) wire kind on [LegalAction] DTOs — GUI + AI
 * read the same catalog; drop this pack-only mirror once ordinals land on the engine wire.
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
