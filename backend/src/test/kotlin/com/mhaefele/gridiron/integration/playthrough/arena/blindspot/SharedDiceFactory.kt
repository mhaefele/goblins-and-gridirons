package com.mhaefele.gridiron.integration.playthrough.arena.blindspot

import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import kotlin.random.Random

/**
 * Builds identical [ScriptedDiceRoller] queues from a seed so two forks consume the same
 * roll stream in order (not event-aligned — different action trees still share luck order).
 */
internal object SharedDiceFactory {
    private const val QUEUE_LEN = 512

    fun identicalPair(seed: Long): Pair<ScriptedDiceRoller, ScriptedDiceRoller> {
        val d6 = IntArray(QUEUE_LEN)
        val d8 = IntArray(QUEUE_LEN)
        val faces = Array(QUEUE_LEN) { BlockDieFace.SHOVE }
        val rng = Random(seed)
        val weighted = BlockDieFace.weightedFaces()
        for (i in 0 until QUEUE_LEN) {
            d6[i] = rng.nextInt(1, 7)
            d8[i] = rng.nextInt(1, 9)
            faces[i] = weighted[rng.nextInt(weighted.size)]
        }
        return fill(d6, d8, faces) to fill(d6, d8, faces)
    }

    private fun fill(d6: IntArray, d8: IntArray, faces: Array<BlockDieFace>): ScriptedDiceRoller =
        ScriptedDiceRoller(fallbackToDefaultPattern = true)
            .queueD6(d6.toList())
            .queueD8(d8.toList())
            .queueBlockDice(faces.toList())
}
