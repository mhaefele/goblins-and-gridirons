package com.mhaefele.gridiron.game.dice.dto

import com.mhaefele.gridiron.game.dice.DiceRoller

enum class BlockDieFace(val weight: Int = 1) {
    SMASH,
    FLATTEN,
    SHOVE(weight = 2),
    TACKLE,
    MISS,
    ;

    companion object {
        /** One block-die roll samples from this list (digest § Block). */
        fun weightedFaces(): List<BlockDieFace> =
            entries.flatMap { face -> List(face.weight) { face } }
    }
}

/** Blocker's preferred face when choosing the best of a rolled pool (digest § Block). */
private val blockFaceRank = mapOf(
    BlockDieFace.SMASH to 5,
    BlockDieFace.FLATTEN to 4,
    BlockDieFace.SHOVE to 3,
    BlockDieFace.TACKLE to 2,
    BlockDieFace.MISS to 1,
)

fun bestBlockFace(faces: List<BlockDieFace>): BlockDieFace {
    require(faces.isNotEmpty()) { "Block pool must include at least one die" }
    return faces.maxBy { blockFaceRank.getValue(it) }
}

/**
 * Enforcer resolve order: highest [blockFaceRank] first, then recurse on the rest.
 * Used when the engine auto-picks (non-HUMAN seats / single distinct perm) and by lookahead.
 */
fun bestEnforcerOrder(faces: List<BlockDieFace>): List<BlockDieFace> {
    if (faces.isEmpty()) return faces
    val best = bestBlockFace(faces)
    val rest = faces.toMutableList()
    rest.remove(best)
    return listOf(best) + bestEnforcerOrder(rest)
}

/** Distinct face-order permutations (same multiset → unique orders only). */
fun distinctFaceOrders(faces: List<BlockDieFace>): List<List<BlockDieFace>> {
    if (faces.isEmpty()) return listOf(emptyList())
    val result = linkedSetOf<List<BlockDieFace>>()
    fun permute(remaining: List<BlockDieFace>, prefix: List<BlockDieFace>) {
        if (remaining.isEmpty()) {
            result.add(prefix)
            return
        }
        for (i in remaining.indices) {
            permute(remaining.filterIndexed { idx, _ -> idx != i }, prefix + remaining[i])
        }
    }
    permute(faces, emptyList())
    return result.toList()
}

/** Dice to roll — block stat plus optional assist, capped at 3 in base game. */
fun blockDiceCount(blockStat: Int, assisted: Boolean = false): Int {
    require(blockStat > 0) { "Block stat must be positive" }
    return minOf(blockStat + if (assisted) 1 else 0, 3)
}

fun rollBlockPool(dice: DiceRoller, blockStat: Int, assisted: Boolean = false): BlockDieFace {
    val count = blockDiceCount(blockStat, assisted)
    return bestBlockFace(dice.blockDice(count))
}
