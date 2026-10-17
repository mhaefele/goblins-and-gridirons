package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import org.springframework.stereotype.Component

/**
 * Pre-turn “No Ball in Play” / New Ball! (digest § New ball): pick a trapdoor, clear/injure as needed,
 * spawn a loose ball, bounce. Used by [com.mhaefele.gridiron.game.turn.TurnEngine], not match create.
 */
@Component
class NewBall(
    private val dice: DiceRoller,
    private val bounce: Bounce,
) {
    /**
     * Trapdoor emergence on the shared session — injure standing occupant, place and bounce a new
     * ball. Beats land on [mutator]'s event log.
     *
     * Clearing a loose ball already on the trapdoor is rulebook-correct but unreachable on the
     * base-game pre-turn path: we only call this when no ball is on the pitch (`board.balls` empty).
     * It matters when multiple balls are in play (digest § New ball) or if this helper is reused outside
     * No Ball in Play.
     */
    fun emergeAtTrapdoor(mutator: BoardMutator) {
        val trapdoor = chooseTrapdoor(mutator.board)
        // UE New Ball!: another ball on this trapdoor is removed from play (not the carrier's held ball).
        mutator.square(trapdoor).looseBallId?.let { mutator.ballRemovedFromPlay(it) }
        mutator.square(trapdoor).playerId?.let { playerId ->
            val player = mutator.getPlayer(playerId)
            if (player.status == PlayerStatus.STANDING) {
                player.heldBallId?.let { mutator.ballRemovedFromPlay(it) }
                mutator.playerInjured(playerId)
            }
        }
        mutator.ballSpawnedLooseAt(trapdoor)
        bounce.bounce(mutator, trapdoor)
    }

    /**
     * Kickoff setup ball (digest § Pitch and board): place loose on a trapdoor with **no** Bounce roll.
     * Distinct from [emergeAtTrapdoor] / New Ball! which always bounces.
     */
    fun placeKickoffBall(mutator: BoardMutator) {
        mutator.ballSpawnedLooseAt(chooseTrapdoor(mutator.board))
    }

    private fun chooseTrapdoor(board: Board): Coordinate {
        val trapdoors = board.trapdoorCoordinates()
        require(trapdoors.isNotEmpty()) { "Board has no trapdoors" }
        return if (trapdoors.size == 1) {
            trapdoors.single()
        } else {
            trapdoors[(dice.d6() - 1) % trapdoors.size]
        }
    }
}
