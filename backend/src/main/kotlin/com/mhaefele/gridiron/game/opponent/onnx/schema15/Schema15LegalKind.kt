package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

/**
 * Frozen schema-15 AI kind catalog (main `AiLegalKind` at schema 15).
 *
 * Ordinals feed the joint actor×kind MultiDiscrete head — **never reorder**.
 *
 * Coarse merges (engine wire kinds stay distinct for submit):
 * - [declineOffer] — Skip / DeclineBonus / ContinueBlock / Choose*Reroll(false)
 * - [reroll] — ChooseBlockReroll(true) / ChooseThrowReroll(true)
 * - [adjustByDoOver] — AdjustThrow / AdjustBlock / AdjustArmour ByDoOver
 *
 * CancelThrowTeammate and ChooseBlockFaceOrder are omitted (filtered / pre-settled).
 */
enum class Schema15LegalKind {
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
}
