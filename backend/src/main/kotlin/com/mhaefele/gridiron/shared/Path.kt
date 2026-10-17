package com.mhaefele.gridiron.shared

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/** Ordered movement path on the pitch; includes the starting square. */
data class Path(
    @field:jakarta.validation.constraints.NotEmpty
    val squares: List<Coordinate>,
) {
    init {
        require(squares.isNotEmpty()) { "Path must include at least the starting square" }
    }

    val steps: Int get() = squares.size - 1

    /** Steps that change both x and y; among equal-length paths, fewer is less windy. */
    val diagonalSteps: Int
        get() = squares.zipWithNext().count { (from, to) -> from.x != to.x && from.y != to.y }

    @JsonValue
    fun toJson(): List<Coordinate> = squares

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromJson(squares: List<Coordinate>): Path = Path(squares)
    }
}
