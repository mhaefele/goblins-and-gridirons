package com.mhaefele.gridiron.shared

/**
 * Compass step on the board grid. Maps to the D8 bounce chart via [d8Value] (1–8).
 *
 * **Reference orientation** (+x east/right, +y south/down; HOME end zone at `y = 1`):
 * - [N], [NE], [NW] — toward the HOME end zone (decreasing `y`).
 * - [S], [SE], [SW] — toward the AWAY end zone (increasing `y`).
 * - [E] — along the pitch toward higher `x` (east sideline), **not** toward either end zone.
 * - [W] — toward lower `x` (west sideline).
 *
 * Never persisted — derived during pathfinding, adjacency, and bounce resolution.
 */
enum class Direction(val d8Value: Int, val deltaX: Int, val deltaY: Int) {
    N(1, 0, -1),
    NE(2, 1, -1),
    E(3, 1, 0),
    SE(4, 1, 1),
    S(5, 0, 1),
    SW(6, -1, 1),
    W(7, -1, 0),
    NW(8, -1, -1),
}
