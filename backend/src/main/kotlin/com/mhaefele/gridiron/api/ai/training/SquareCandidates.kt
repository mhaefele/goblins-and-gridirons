package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.board.BoardSquare

/**
 * Staged square filter for training placement.
 *
 * Prefer this over nested [TrainingBoardBuilder.allOf] / opaque `(Square) -> Boolean` stacks:
 * each [filter] records how many squares survived, so an empty set reports
 * `seed=N → emptyPlayable=M → beyondMove=0` instead of a bare "No square matches filter".
 *
 * One lane only — build the candidate set with named stages; do not also maintain a parallel
 * "invalid squares" list for the same decision.
 */
class SquareCandidates(
    private val label: String,
    seed: Collection<BoardSquare>,
) {
    private var current: List<BoardSquare> = seed.toList()
    private val stages = mutableListOf("seed=${current.size}")

    val size: Int get() = current.size
    fun isEmpty(): Boolean = current.isEmpty()
    fun isNotEmpty(): Boolean = current.isNotEmpty()
    fun asList(): List<BoardSquare> = current
    fun stageSummary(): String = stages.joinToString(" → ")

    fun filter(name: String, pred: (BoardSquare) -> Boolean): SquareCandidates {
        current = current.filter(pred)
        stages += "$name=${current.size}"
        return this
    }

    fun requireNonEmpty(): List<BoardSquare> {
        check(current.isNotEmpty()) { "No squares for $label after ${stageSummary()}" }
        return current
    }

    fun random(): BoardSquare = requireNonEmpty().random()

    fun shuffled(): List<BoardSquare> = requireNonEmpty().shuffled()
}
