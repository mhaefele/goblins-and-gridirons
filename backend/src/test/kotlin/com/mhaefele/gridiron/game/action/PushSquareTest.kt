package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.game.action.utils.pushSquareAwayFrom
import com.mhaefele.gridiron.shared.Coordinate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PushSquareTest {
    @Test
    fun `push continues in line away from blocker`() {
        assertThat(pushSquareAwayFrom(Coordinate(5, 6), Coordinate(6, 6)))
            .isEqualTo(Coordinate(7, 6))
        assertThat(pushSquareAwayFrom(Coordinate(5, 5), Coordinate(6, 6)))
            .isEqualTo(Coordinate(7, 7))
    }
}
