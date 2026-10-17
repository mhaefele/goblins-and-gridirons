package com.mhaefele.gridiron.game.dice.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FoulResultTest {

    @Test
    fun `block faces map to foul chart`() {
        assertThat(foulResultFor(BlockDieFace.MISS)).isEqualTo(FoulResult.SPOTTED)
        assertThat(foulResultFor(BlockDieFace.TACKLE)).isEqualTo(FoulResult.TAKE_THAT)
        assertThat(foulResultFor(BlockDieFace.SHOVE)).isEqualTo(FoulResult.SLIPPED)
        assertThat(foulResultFor(BlockDieFace.SMASH)).isEqualTo(FoulResult.GOT_THEM)
        assertThat(foulResultFor(BlockDieFace.FLATTEN)).isEqualTo(FoulResult.GOT_THEM)
    }
}
