package com.mhaefele.gridiron.shared

/**
 * A square location on the padded board grid.
 *
 * **Indexing:** 0-based. For a [com.mhaefele.gridiron.model.board.BoardDefinition] with play size `playWidth × playHeight`,
 * the grid is `(playWidth + 2) × (playHeight + 2)`. Every coordinate in that range exists
 * as a [com.mhaefele.gridiron.model.board.BoardSquare] — including the one-square [com.mhaefele.gridiron.model.board.OutOfBoundsSquare] border — so neighbor
 * lookups never need separate bounds checks.
 *
 * **Playable interior:** `x ∈ [1, playWidth]`, `y ∈ [1, playHeight]`.
 *
 * **Orientation** (ASCII / UI diagrams: +x right, +y down):
 * - `(0, 0)` — northwest out-of-bounds corner.
 * - `(1, 1)` — northwest playable corner: [HOME] end zone, west sideline.
 * - `(playWidth, 1)` — northeast playable corner: HOME end zone, east sideline.
 * - `(1, playHeight)` — southwest playable corner: [AWAY] end zone, west sideline.
 * - `(playWidth, playHeight)` — southeast playable corner: AWAY end zone, east sideline.
 *
 * Row `y = 1` is the HOME end-zone row; `y = playHeight` is the AWAY end-zone row
 * (rulebook: one row of squares at each end). Midfield is everything between.
 */
data class Coordinate(
    val x: Int,
    val y: Int,
) {
    fun neighbor(direction: Direction): Coordinate =
        Coordinate(x + direction.deltaX, y + direction.deltaY)

    fun display(): String = "($x, $y)"
}
