package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.board.Ball
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.BlockedSquare
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.EndZoneSquare
import com.mhaefele.gridiron.model.board.FieldSquare
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.model.board.TrapdoorSquare
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.game.action.utils.standingStatus

/** Fluent board setup for tests — generates player ids; override only what the test cares about. */
class BoardScenario(
    board: Board = GameScenario.emptyBoard(),
) {
    var board: Board = board
        private set

    private val nextIndex = TeamSide.entries.associateWith { 0 }.toMutableMap()

    fun addPlayer(
        at: Coordinate,
        status: PlayerStatus = PlayerStatus.STANDING,
        team: TeamSide = TeamSide.HOME,
        profile: PositionProfile = TestProfiles.lineman(),
    ): PlacedPlayer = addPlayer(at.x, at.y, status, team, profile)

    fun addPlayer(
        x: Int,
        y: Int,
        status: PlayerStatus = PlayerStatus.STANDING,
        team: TeamSide = TeamSide.HOME,
        profile: PositionProfile = TestProfiles.lineman(),
    ): PlacedPlayer {
        val id = nextPlayerId(team)
        val player = Player(id, team, profile, status, heldBallId = null)
        val coord = Coordinate(x, y)
        val occupied = occupySquare(board.squareAt(coord), id)
        board = board.withSquareAt(coord, occupied, players = board.players + (id to player))
        return PlacedPlayer(player, this)
    }

    fun addReservesPlayer(
        team: TeamSide = TeamSide.HOME,
        profile: PositionProfile = TestProfiles.lineman(),
    ): PlacedPlayer {
        val id = nextPlayerId(team)
        val player = Player(id, team, profile, PlayerStatus.RESERVES, heldBallId = null)
        val dugout = board.reserves.getValue(team)
        board = board.copy(
            players = board.players + (id to player),
            reserves = board.reserves + (team to dugout.copy(playerIds = dugout.playerIds + id)),
        )
        return PlacedPlayer(player, this)
    }

    fun holdingBall(placed: PlacedPlayer, ball: Ball = nextBall()): PlacedPlayer {
        check(placed.player.status == PlayerStatus.STANDING)
        val carrier = placed.player.copy(heldBallId = ball.id)
        board = board.copy(
            players = board.players + (carrier.id to carrier),
            balls = board.balls + (ball.id to ball),
        )
        return PlacedPlayer(carrier, this)
    }

    fun addLooseBall(at: Coordinate, ball: Ball = nextBall()): Ball {
        val withBall = when (val square = board.squareAt(at)) {
            is EndZoneSquare     -> square.copy(looseBallId = ball.id)
            is FieldSquare       -> square.copy(looseBallId = ball.id)
            is BlockedSquare     -> square.copy(looseBallId = ball.id)
            is TrapdoorSquare    -> square.copy(looseBallId = ball.id)
            is OutOfBoundsSquare -> error("Cannot place loose ball on out-of-bounds square $at")
        }
        board = board.withSquareUpdates(
            mapOf(at to withBall),
            balls = board.balls + (ball.id to ball),
        )
        return ball
    }

    fun addLooseBall(x: Int, y: Int, ball: Ball = nextBall()): Ball =
        addLooseBall(Coordinate(x, y), ball)

    fun removeAllBalls() {
        val clearedSquares = board.allSquares().associate { square ->
            square.coordinate to when (square) {
                is EndZoneSquare     -> square.copy(looseBallId = null)
                is FieldSquare       -> square.copy(looseBallId = null)
                is BlockedSquare     -> square.copy(looseBallId = null)
                is TrapdoorSquare    -> square.copy(looseBallId = null)
                is OutOfBoundsSquare -> square
            }
        }
        val players = board.players.mapValues { (_, player) -> player.copy(heldBallId = null) }
        board = board.withSquareUpdates(clearedSquares, players = players, balls = emptyMap())
    }

    private var nextBallIndex = 0

    private fun nextBall(): Ball {
        val id = BallId("ball$nextBallIndex")
        nextBallIndex++
        return Ball(id)
    }

    private fun nextPlayerId(team: TeamSide): PlayerId {
        val prefix = if (team == TeamSide.HOME) "h" else "a"
        while (true) {
            val index = nextIndex.getValue(team)
            nextIndex[team] = index + 1
            val id = PlayerId("$prefix$index")
            // fromBoard fixtures already occupy h0/a0… — skip collisions.
            if (id !in board.players) return id
        }
    }
}

/** A player placed by [BoardScenario] — use [player] for domain calls, [team] to place opposition. */
class PlacedPlayer internal constructor(
    val player: Player,
    private val scenario: BoardScenario,
) {
    val id: PlayerId get() = player.id
    val team: TeamSide get() = player.team

    fun standingStatus(): StandingStatus = standingStatus(player, scenario.board)
    override fun toString(): String {
        return "PlacedPlayer(id=${id.value})"
    }
}

private fun occupySquare(square: BoardSquare, playerId: PlayerId): BoardSquare = when (square) {
    is EndZoneSquare     -> square.copy(playerId = playerId)
    is FieldSquare       -> square.copy(playerId = playerId)
    is BlockedSquare     -> square.copy(playerId = playerId)
    is TrapdoorSquare    -> square.copy(playerId = playerId)
    is OutOfBoundsSquare -> error("Cannot place player on out-of-bounds square ${square.coordinate}")
}
