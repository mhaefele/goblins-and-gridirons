package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.canHoldBall
import com.mhaefele.gridiron.shared.Direction
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.game.action.utils.standingStatus
import org.springframework.stereotype.Component

/** D8 bounce resolution — owns the [DiceRoller] port for bounce rolls. */
@Component
class Bounce(
    private val dice: DiceRoller,
) {
    /** Place the loose ball on [from] onto [destination] without rolling D8 (Ball Guard). */
    fun placeLooseBall(mutator: BoardMutator, from: Coordinate, destination: Coordinate) {
        check(mutator.square(from).looseBallId != null) { error("No loose ball at $from") }
        val nextBoundFrom = settleBounce(mutator, from, destination, cannotPickUp = null)
        if (nextBoundFrom != null) {
            //  Landed but couldn't stay in that square (player who couldn't pick it up?)
            //  Now just handle it like a regular bounce until it settles somewhere.
            bounce(mutator, nextBoundFrom)
        }
    }

    /**
     * Roll from [origin] until the ball settles or re-bounce chain completes. Ball must already be loose at [origin].
     *
     * [cannotPickUp]: player about to leave the pitch (injury / eject). They must not regain possession if the
     * bounce lands on them — ball stays loose on their square so removal can proceed.
     */
    fun bounce(
        mutator: BoardMutator,
        origin: Coordinate,
        cannotPickUp: PlayerId? = null,
    ) {
        check(mutator.square(origin).looseBallId!= null) { error("No loose ball at $origin") }
        var bounceFrom = origin

        val boucesSoFar = mutableListOf<Coordinate>()

        repeat(20) {
            val roll = dice.d8()
            mutator.record(DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = roll))

            val landing = landingSquare(mutator.board, bounceFrom, directionForD8(roll))
            val rebounceFrom = settleBounce(mutator, bounceFrom, landing, cannotPickUp)
            boucesSoFar.add(landing)
            bounceFrom = rebounceFrom ?: return
        }

        error("Bounce chain exceeded limit from $origin.. Bounces;  $boucesSoFar")
    }

    private fun directionForD8(roll: Int): Direction =
        Direction.entries.first { it.d8Value == roll }

    private fun landingSquare(board: com.mhaefele.gridiron.model.board.Board, from: Coordinate, start: Direction): Coordinate {
        var direction = start
        repeat(Direction.entries.size) {
            val target = from.neighbor(direction)
            val square = board.squareAt(target)
            if (!square.isBlocked()) return target
            direction = direction.nextClockwise()
        }
        error("No valid bounce landing from $from")
    }

    private fun settleBounce(
        mutator: BoardMutator,
        from: Coordinate,
        landing: Coordinate,
        cannotPickUp: PlayerId?,
    ): Coordinate? {
        val square = mutator.square(landing)
        if (square.looseBallId != null) {
            mutator.ballBounceDeflected(from, landing)
            return from
        }

        mutator.ballBounced(from, landing)

        val occupantId = mutator.square(landing).playerId
        if (occupantId == null) {
            return null
        } else if (occupantId == cannotPickUp) {
            // Departing player: leave loose here; caller removes them next.
            return null
        } else {
            val occupant = mutator.getPlayer(occupantId)
            if (
                occupant.status == PlayerStatus.STANDING &&
                standingStatus(occupant, mutator.board) == StandingStatus.OPEN &&
                occupant.profile.stats.throwStat.canHoldBall() &&
                occupant.heldBallId == null
            ) {
                mutator.ballPickedUpByPlayer(occupantId, landing)
                return null
            } else {
                return landing
            }
        }
    }

    private fun Direction.nextClockwise(): Direction {
        val next = if (d8Value == 8) 1 else d8Value + 1
        return Direction.entries.first { it.d8Value == next }
    }
}
