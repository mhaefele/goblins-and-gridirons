package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import org.assertj.core.api.Assertions.assertThat

fun List<Path>.destinations(): List<Coordinate> = map { it.squares.last() }

fun List<Path>.assertCanReach(vararg targets: Coordinate) {
    assertThat(destinations())
        .withFailMessage("Expected path destinations to include ${targets.toList()}")
        .contains(*targets)
}

fun List<Path>.assertCanReach(x: Int, y: Int) = assertCanReach(Coordinate(x, y))

fun List<Path>.assertCannotReach(vararg targets: Coordinate) {
    assertThat(destinations())
        .withFailMessage("Expected path destinations to exclude ${targets.toList()}")
        .doesNotContain(*targets)
}

fun List<Path>.assertCannotReach(x: Int, y: Int) = assertCannotReach(Coordinate(x, y))

fun List<Path>.assertCanReachVia(target: Coordinate, via: Coordinate) {
    val hits = filter { it.squares.last() == target && via in it.squares.dropLast(1) }
    assertThat(hits)
        .withFailMessage("Expected a path to $target via intermediate $via")
        .isNotEmpty()
}

fun List<Path>.assertCannotPassVia(via: Coordinate) {
    val hits = filter { via in it.squares.dropLast(1) }
    assertThat(hits)
        .withFailMessage("Expected no path through intermediate $via")
        .isEmpty()
}
