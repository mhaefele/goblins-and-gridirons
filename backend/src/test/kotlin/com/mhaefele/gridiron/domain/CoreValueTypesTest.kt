package com.mhaefele.gridiron.domain

import com.mhaefele.gridiron.shared.Coordinate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreValueTypesTest {

    @Test
    fun `Coordinate equality is structural`() {
        assertThat(Coordinate(3, 4)).isEqualTo(Coordinate(3, 4))
        assertThat(Coordinate(3, 4)).isNotEqualTo(Coordinate(3, 5))
    }
}
