package com.mhaefele.gridiron.model.board

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import jakarta.validation.constraints.Min

/** Dugout for one team — holds player ids only, never balls. */
data class ReservesArea(
    val playerIds: List<PlayerId> = emptyList(),
) {
    init {
        require(playerIds.distinct() == playerIds) { "Duplicate player in reserves: $playerIds" }
    }
}

/**
 * Static playable area size and special square locations.
 *
 * The runtime [Board] grid is [playWidth] + 2 by [playHeight] + 2, with a one-square
 * [OutOfBoundsSquare] border around the pitch interior.
 *
 * **Why the border:** pathfinding, bounce, and adjacency step square-by-square. When a step
 * lands on [OutOfBoundsSquare], movement stops — you never walk off the grid, so algorithms
 * do not need `x < 0` / `x >= width` guards on every neighbor. Off-map coordinates are not
 * valid lookups — [Board.squareAt] throws. Border [OutOfBoundsSquare] entries are real map keys.
 *
 * **Playable interior:** `x ∈ [1, playWidth]`, `y ∈ [1, playHeight]` (grid coordinates).
 * Trapdoor and blocked sets use those grid coordinates.
 */
data class BoardDefinition(
    @field:Min(1)
    val playWidth: Int,
    @field:Min(2)
    val playHeight: Int,
    val trapdoors: Set<Coordinate> = emptySet(),
    val blockedSquares: Set<Coordinate> = emptySet(),
) {
    init {
        require(playHeight >= 2) { "Board height must be at least 2 for HOME and AWAY end-zone rows" }
        val playableInterior = { coord: Coordinate -> isPlayableInterior(coord) }
        require(trapdoors.all(playableInterior)) { "Trapdoor outside playable area: $trapdoors" }
        require(blockedSquares.all(playableInterior)) { "Blocked square outside playable area: $blockedSquares" }
    }

    /** Validates trapdoor/blocked placement at layout build time only. */
    private fun isPlayableInterior(coord: Coordinate): Boolean =
        coord.x in 1..playWidth && coord.y in 1..playHeight

    fun emptyBoard(
        players: List<Player>
    ): Board {
        val groupBy = players.groupBy { it.team }
        val reserves = groupBy.map { it.key to ReservesArea(it.value.map { it.id }) }.toMap()
        return emptyBoard(players.associateBy { it.id }, reserves = reserves)
    }

    fun emptyBoard(
        players: Map<PlayerId, Player> = emptyMap(),
        balls: Map<BallId, Ball> = emptyMap(),
        reserves: Map<TeamSide, ReservesArea> = TeamSide.entries.associateWith { ReservesArea() },
    ): Board = Board(
        squares = buildSquares(),
        reserves = reserves,
        players = players,
        balls = balls,
    )

    private fun buildSquares(): Map<Coordinate, BoardSquare> =
        (0..playWidth + 1).flatMap { x ->
            (0..playHeight + 1).map { y -> Coordinate(x, y) }
        }.associateWith(::templateSquareAt)

    private fun templateSquareAt(coord: Coordinate): BoardSquare {
        if (coord.x == 0 || coord.y == 0 || coord.x == playWidth + 1 || coord.y == playHeight + 1) {
            return OutOfBoundsSquare(coord)
        }
        if (coord in blockedSquares) {
            return BlockedSquare(coord)
        }
        if (coord in trapdoors) {
            return TrapdoorSquare(coord)
        }
        if (coord.y == 1) {
            return EndZoneSquare(coord, TeamSide.HOME)
        }
        if (coord.y == playHeight) {
            return EndZoneSquare(coord, TeamSide.AWAY)
        }
        return FieldSquare(coord)
    }
}

/**
 * Physical table snapshot: padded pitch grid plus both dugouts.
 *
 * **Reference direction:** board → squares → occupants. [Player] and [Ball] maps hold
 * attributes; the coordinate→square map is an internal detail — use [squareAt] and [allSquares].
 * Layout is fully encoded in the internal grid — no separate definition reference at runtime.
 */
data class Board(
    private val squares: Map<Coordinate, BoardSquare>,
    val reserves: Map<TeamSide, ReservesArea>,
    val players: Map<PlayerId, Player>,
    val balls: Map<BallId, Ball>,
) {
    init {
        require(TeamSide.entries.all { it in reserves }) { "Board must have reserves for each team" }
        requireConsistent()
    }

    fun allSquares(): List<BoardSquare> = squares.values.toList()

    fun squareAt(coord: Coordinate): BoardSquare =
        squares[coord] ?: throw IllegalArgumentException("Coordinate $coord is not on the board")

    fun withSquareAt(
        coord: Coordinate,
        square: BoardSquare,
        players: Map<PlayerId, Player> = this.players,
    ): Board = copy(squares = squares + (coord to square), players = players)

    fun withSquareUpdates(
        updates: Map<Coordinate, BoardSquare>,
        players: Map<PlayerId, Player> = this.players,
        balls: Map<BallId, Ball> = this.balls,
        reserves: Map<TeamSide, ReservesArea> = this.reserves,
    ): Board = copy(squares = squares + updates, players = players, balls = balls, reserves = reserves)

    fun playerAt(coord: Coordinate): Player? =
        squareAt(coord).playerId?.let { players[it] }

    /** Occupant at [coord]. Throws when the square is empty — absence is not an expected lookup result. */
    fun getPlayerAt(coord: Coordinate): Player =
        playerAt(coord) ?: throw IllegalArgumentException("No player at $coord")

    fun findSquare(playerId: PlayerId): BoardSquare =
        squares.values.firstOrNull { it.playerId == playerId }
            ?: throw IllegalArgumentException("Player $playerId is not on the pitch")

    fun player(playerId: PlayerId): Player =
        players[playerId] ?: throw IllegalArgumentException("Unknown player ${playerId.value}")

    fun trapdoorCoordinates(): List<Coordinate> =
        allSquares().filter { it.isTrapdoor() }.map { it.coordinate }

    private fun requireConsistent() {
        val playerIdsOnSquares = squares.values.mapNotNull { it.playerId }
        require(playerIdsOnSquares.size == playerIdsOnSquares.toSet().size) { "Duplicate player on multiple squares" }

        val looseBallIds = squares.values.mapNotNull { it.looseBallId }
        require(looseBallIds.size == looseBallIds.toSet().size) { "Duplicate loose ball on multiple squares" }

        playerIdsOnSquares.forEach { playerId ->
            val player = players.getValue(playerId)
            require(player.status in setOf(PlayerStatus.STANDING, PlayerStatus.PRONE)) { "On-pitch square references ${player.status} player $playerId" }
        }

        players.values.forEach { player ->
            when (player.status) {
                PlayerStatus.RESERVES                     -> {
                    require(player.id in reserves.getValue(player.team).playerIds) { "Reserves player ${player.id} missing from ${player.team} dugout" }
                    require(player.heldBallId == null) { "Reserves player ${player.id} must not hold a ball" }
                }
                PlayerStatus.STANDING, PlayerStatus.PRONE -> require(player.id in playerIdsOnSquares) { "On-pitch player ${player.id} missing from squares map" }
            }
        }

        looseBallIds.forEach { ballId ->
            require(ballId in balls) { "Loose ball $ballId missing from balls map" }
            require(players.values.none { it.heldBallId == ballId }) { "Ball $ballId is both loose and held" }
        }

        players.values.mapNotNull { it.heldBallId }.forEach { ballId ->
            require(ballId in balls) { "Held ball $ballId missing from balls map" }
            require(ballId !in looseBallIds) { "Ball $ballId is both held and loose" }
        }
    }
}
