package com.mhaefele.gridiron.game.dice

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Random

/** Production [DiceRoller] — uniform D6/D8 and rulebook block-die face weights. */
@Component
class RandomDiceRoller(
    private val random: Random = SecureRandom(),
) : DiceRoller {

    override fun d6(): Int = random.nextInt(6) + 1

    override fun d8(): Int = random.nextInt(8) + 1

    override fun blockDice(count: Int): List<BlockDieFace> {
        require(count > 0) { "Block dice count must be positive" }
        val faces = BlockDieFace.weightedFaces()
        return List(count) { faces[random.nextInt(faces.size)] }
    }

    override fun <T> shuffle(items: List<T>): List<T> {
        val copy = items.toMutableList()
        for (i in copy.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = copy[i]
            copy[i] = copy[j]
            copy[j] = tmp
        }
        return copy
    }
}
