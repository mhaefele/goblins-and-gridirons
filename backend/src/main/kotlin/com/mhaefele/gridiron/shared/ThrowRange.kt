package com.mhaefele.gridiron.shared

/**
 * Short vs long throw check bands (digest § Throw).
 * Cardboard ruler: Euclidean centre-to-centre Short ≤4, full ≤8 — see docs/throw-ruler.md.
 * Obstruction uses centre-to-centre ruler line interiors (not shortest-path).
 */
enum class ThrowRange {
    /** Adjacent teammate — auto-success, no throw check. */
    HAND_OFF,
    SHORT,
    LONG,
}