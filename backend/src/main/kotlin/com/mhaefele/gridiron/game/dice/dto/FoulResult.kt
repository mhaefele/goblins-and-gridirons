package com.mhaefele.gridiron.game.dice.dto

/**
 * Foul Action outcomes (digest § Foul). Same physical Block Die faces; reinterpreted via [foulResultFor].
 */
enum class FoulResult {
    /** Miss → fouler ejected; target unaffected. */
    SPOTTED,
    /** Tackle → target injured; fouler also spotted. */
    TAKE_THAT,
    /** Shove → target unaffected; fouler’s turn ends. */
    SLIPPED,
    /** Smash or Flatten → target injured. */
    GOT_THEM,
}

/** Map a rolled Block Die face to its Foul chart result. */
fun foulResultFor(face: BlockDieFace): FoulResult =
    when (face) {
        BlockDieFace.MISS                         -> FoulResult.SPOTTED
        BlockDieFace.TACKLE                       -> FoulResult.TAKE_THAT
        BlockDieFace.SHOVE                        -> FoulResult.SLIPPED
        BlockDieFace.SMASH, BlockDieFace.FLATTEN -> FoulResult.GOT_THEM
    }
