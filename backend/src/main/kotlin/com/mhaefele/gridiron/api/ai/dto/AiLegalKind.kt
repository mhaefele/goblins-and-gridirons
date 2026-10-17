package com.mhaefele.gridiron.api.ai.dto

/**
 * AI legal-choice kind — policy vocabulary (schema 10+), not 1:1 with every GUI
 * [com.mhaefele.gridiron.api.dto.response.LegalAction] Jackson name.
 *
 * MultiDiscrete joint head uses free actor playerIndex × [AiLegalChoiceDto.kindIndex]
 * (= [ordinal]). Kind-axis width is [AiLegalKind.entries].size (joint actor×kind head).
 *
 * Coarse merges (engine wire kinds stay distinct for submit):
 * - [declineOffer] — Skip / DeclineBonus / ContinueBlock / Choose*Reroll(false)
 * - [reroll] — ChooseBlockReroll(true) / ChooseThrowReroll(true)
 * - [adjustByDoOver] — AdjustThrow / AdjustBlock / AdjustArmour ByDoOver
 *
 * [CancelThrowTeammate] and [ChooseBlockFaceOrder] are omitted from AI legals
 * (GUI only — Enforcer order is humans-only; TRAINER/COMPUTER auto-sort).
 *
 * `` `throw` `` / `` `reroll` `` are backticked only because they are Kotlin keywords;
 * [name] is still `"throw"` / `"reroll"`.
 */
enum class AiLegalKind {
    run,
    mark,
    sidestep,
    `throw`,
    throwTeammateSelect,
    throwTeammate,
    block,
    foul,
    standUp,
    reserves,
    declineOffer,
    placeLooseBall,
    `reroll`,
    adjustByDoOver,
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
    claimChallenge,
    replaceChallenge,
    ;

    companion object {
        fun fromWireName(wireName: String): AiLegalKind =
            entries.find { it.name == wireName } ?: throw IllegalArgumentException("Unknown AiLegalKind: $wireName")
    }
}
