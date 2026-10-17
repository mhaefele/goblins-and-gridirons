package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AiObsLayoutPerspectiveTest {

    @Test
    fun `HOME perspective is identity on pitch and benches`() {
        val pitch = AiObsLayout.squareIndex(4, 7)
        val homeBench = AiObsLayout.homeBenchIndex(2)
        val awayBench = AiObsLayout.awayBenchIndex(5)

        assertThat(AiObsLayout.toPerspectiveIndex(pitch, TeamSide.HOME)).isEqualTo(pitch)
        assertThat(AiObsLayout.toPerspectiveIndex(homeBench, TeamSide.HOME)).isEqualTo(homeBench)
        assertThat(AiObsLayout.toPerspectiveIndex(awayBench, TeamSide.HOME)).isEqualTo(awayBench)
    }

    @Test
    fun `AWAY perspective rotates pitch 180 degrees`() {
        val nearHome = AiObsLayout.squareIndex(1, 1)
        val farAway = AiObsLayout.squareIndex(AiObsLayout.PLAY_WIDTH, AiObsLayout.PLAY_HEIGHT)

        assertThat(AiObsLayout.toPerspectiveIndex(nearHome, TeamSide.AWAY)).isEqualTo(farAway)
        assertThat(AiObsLayout.toPerspectiveIndex(farAway, TeamSide.AWAY)).isEqualTo(nearHome)
        assertThat(AiObsLayout.toPerspectiveIndex(AiObsLayout.squareIndex(6, 8), TeamSide.AWAY))
            .isEqualTo(AiObsLayout.squareIndex(6, 8))
    }

    @Test
    fun `AWAY perspective swaps bench regions so us is 165-174`() {
        val homeBench = AiObsLayout.homeBenchIndex(3)
        val awayBench = AiObsLayout.awayBenchIndex(3)

        assertThat(AiObsLayout.toPerspectiveIndex(homeBench, TeamSide.AWAY)).isEqualTo(AiObsLayout.awayBenchIndex(3))
        assertThat(AiObsLayout.toPerspectiveIndex(awayBench, TeamSide.AWAY)).isEqualTo(AiObsLayout.homeBenchIndex(3))
    }

    @Test
    fun `AWAY perspective is involutive on pitch and benches`() {
        val samples = listOf(
            AiObsLayout.squareIndex(2, 3),
            AiObsLayout.squareIndex(11, 15),
            AiObsLayout.homeBenchIndex(0),
            AiObsLayout.awayBenchIndex(9),
        )
        for (absolute in samples) {
            val flipped = AiObsLayout.toPerspectiveIndex(absolute, TeamSide.AWAY)
            assertThat(AiObsLayout.toPerspectiveIndex(flipped, TeamSide.AWAY)).isEqualTo(absolute)
        }
    }
}
