package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.game.action.utils.inThrowRulerRange
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.legalmoves.runPaths as engineRunPaths
import com.mhaefele.gridiron.model.board.Ball
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.BlockedSquare
import com.mhaefele.gridiron.model.board.EndZoneSquare
import com.mhaefele.gridiron.model.board.FieldSquare
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.board.TrapdoorSquare
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.canHoldBall
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import kotlin.math.abs

/**
 * Fluent board setup for training scenarios — deploys from reserves onto the pitch without
 * emitting domain events. Modeled after [com.mhaefele.gridiron.support.BoardScenario]; keep
 * placement helpers aligned if we merge the two later.
 */
class TrainingBoardBuilder(
    board: Board,
) {
    var board: Board = board
        private set

    /** Set during CLAIM_STAR_TURN placement; copied into [TrainingEpisodeConfig.starTurnStarId]. */
    var starTurnStarId: PlayerId? = null
        private set

    private val scatterAvoid = mutableListOf<BoardSquare>()
    private val oppositionScatterAvoid = mutableListOf<BoardSquare>()
    private var ballCarrierThrowFrom: BoardSquare? = null

    /** Hold-ball scatter guard — call after the lesson thrower is on the board. */
    fun registerBallCarrierThrowScatter(from: BoardSquare) {
        ballCarrierThrowFrom = from
    }

    /** Hold-ball reserves must not scatter into any throw target square (including End Zones). */
    fun holdBallNotInThrowRange(player: Player, square: BoardSquare): Boolean {
        if (!canHoldBall(player)) return true
        val from = ballCarrierThrowFrom ?: return true
        return square.coordinate == from.coordinate || !inThrowRulerRange(from, square)
    }

    /** Lesson piece square plus neighbors — difficulty scatter skips these. */
    fun markScatterAvoid(vararg placed: PlacedPlayer) {
        placed.forEach { markScatterAvoid(it.square) }
    }

    fun markScatterAvoid(placed: Collection<PlacedPlayer>) {
        markScatterAvoid(*placed.toTypedArray())
    }

    /** Square plus neighbors (loose ball, trapdoor cue, …). */
    fun markScatterAvoid(vararg squares: BoardSquare) {
        squares.forEach { sq ->
            if (sq !in scatterAvoid) scatterAvoid.add(sq)
            adjacentSquares(sq).forEach { adj ->
                if (adj !in scatterAvoid) scatterAvoid.add(adj)
            }
        }
    }

    /** Single squares only — e.g. throw-range cells where neighbor expansion would over-constrain scatter. */
    fun markScatterAvoidExact(vararg squares: BoardSquare) {
        squares.forEach { sq -> if (sq !in scatterAvoid) scatterAvoid.add(sq) }
    }

    internal fun scatterAvoidZones(): Collection<BoardSquare> = scatterAvoid

    /** Exact squares only — keeps scoring lanes open for trainer scatter but blocks opposition clutter. */
    fun markOppositionScatterAvoid(vararg squares: BoardSquare) {
        squares.forEach { sq -> if (sq !in oppositionScatterAvoid) oppositionScatterAvoid.add(sq) }
    }

    internal fun oppositionScatterAvoidZones(): Collection<BoardSquare> = oppositionScatterAvoid

    /** On-pitch [team] tokens plus neighbors — keeps difficulty scatter from adjacency leaks. */
    fun onPitchScatterAvoid(team: TeamSide): Collection<BoardSquare> {
        val zones = linkedSetOf<BoardSquare>()
        board.allSquares().forEach { square ->
            val playerId = square.playerId ?: return@forEach
            if (board.players[playerId]?.team != team) return@forEach
            zones.add(square)
            adjacentSquares(square).forEach { adj -> zones.add(adj) }
        }
        return zones
    }

    /** Tags the Show Them How star for episode grading; also [markScatterAvoid]s the star and extras. */
    fun tagStarTurnStar(star: PlacedPlayer, vararg alsoAvoid: PlacedPlayer) {
        starTurnStarId = star.player.id
        markScatterAvoid(star, *alsoAvoid)
    }

    fun build(): Board = board

    fun reserves(team: TeamSide): List<Player> =
        board.reserves.getValue(team).playerIds.map { board.players.getValue(it) }

    fun randomReserve(team: TeamSide, playerFilter: (Player) -> Boolean = { true }): Player =
        reserves(team).filter(playerFilter).random()

    fun placeRandomPlayerWithBall(
        team: TeamSide,
        vararg squareFilters: (BoardSquare) -> Boolean,
    ): PlacedPlayer = placeRandomPlayerWithBall(team, playerFilter = { true }, squareFilters = squareFilters)

    fun placeRandomPlayerWithBall(
        team: TeamSide,
        playerFilter: (Player)              -> Boolean,
        vararg squareFilters: (BoardSquare) -> Boolean,
    ): PlacedPlayer = placeCompatiblePlayerWithBall(team, playerFilter, "placeRandomPlayerWithBall") { _ ->
        filter("notOnOwnEndZone", notOnOwnEndZone(team))
        squareFilters.forEachIndexed { i, f -> filter("squareFilter[$i]", f) }
    }

    fun placeRandomPlayerWithBall(
        team: TeamSide,
        playerFilter: (Player) -> Boolean,
        squareFilter: (Player) -> (BoardSquare) -> Boolean,
    ): PlacedPlayer = placeCompatiblePlayerWithBall(team, playerFilter, "placeRandomPlayerWithBall") { player ->
        filter("notOnOwnEndZone", notOnOwnEndZone(team))
        filter("playerSquareFilter", squareFilter(player))
    }

    /** Like [placeCompatibleReserve] but the chosen reserve also receives a ball. */
    fun placeCompatiblePlayerWithBall(
        team: TeamSide,
        playerFilter: (Player) -> Boolean,
        label: String,
        stages: SquareCandidates.(Player) -> Unit,
    ): PlacedPlayer {
        val eligible = reserves(team).filter { playerFilter(it) && it.profile.stats.throwStat.canHoldBall() }.shuffled()
        check(eligible.isNotEmpty()) { "$label: no ball-capable reserve matches player filter for $team" }
        val failures = mutableListOf<String>()
        for (player in eligible) {
            val pipeline = squareCandidates("$label/${player.id}").also { it.stages(player) }
            if (pipeline.isEmpty()) {
                failures += "${player.id}: ${pipeline.stageSummary()}"
                continue
            }
            return placePlayerWithBall(player, pipeline.asList())
        }
        error("$label: no player×square pair for $team. Tried:\n${failures.joinToString("\n")}")
    }

    fun placeRandomReserve(
        team: TeamSide,
        vararg squareFilters: (BoardSquare) -> Boolean,
    ): PlacedPlayer = placeRandomReserve(team, playerFilter = { true }, squareFilters = squareFilters)

    fun placeRandomReserve(
        team: TeamSide,
        playerFilter: (Player)              -> Boolean,
        vararg squareFilters: (BoardSquare) -> Boolean,
    ): PlacedPlayer = placeCompatibleReserve(team, playerFilter, "placeRandomReserve") { _ ->
        filter("emptyPlayable", ::emptyPlayable)
        squareFilters.forEachIndexed { i, f -> filter("squareFilter[$i]", f) }
    }

    fun placeRandomReserve(
        team: TeamSide,
        playerFilter: (Player) -> Boolean,
        squareFilter: (Player) -> (BoardSquare) -> Boolean,
    ): PlacedPlayer = placeCompatibleReserve(team, playerFilter, "placeRandomReserve") { player ->
        filter("playerSquareFilter", squareFilter(player))
    }

    fun placeRandomReserve(
        team: TeamSide,
        candidateSquares: Collection<BoardSquare>,
        playerFilter: (Player) -> Boolean = { true },
    ): PlacedPlayer =
        placePlayer(randomReserve(team, playerFilter), candidateSquares)

    /**
     * Pick a reserve that still has a legal square — try shuffled eligible players until one fits.
     * Avoids the old pick-player-then-fail pattern when Move / MA / beyond-move shrink the set.
     */
    fun placeCompatibleReserve(
        team: TeamSide,
        playerFilter: (Player) -> Boolean,
        label: String,
        stages: SquareCandidates.(Player) -> Unit,
    ): PlacedPlayer {
        val eligible = reserves(team).filter(playerFilter).shuffled()
        check(eligible.isNotEmpty()) { "$label: no reserve matches player filter for $team" }
        val failures = mutableListOf<String>()
        for (player in eligible) {
            val pipeline = squareCandidates("$label/${player.id}").also { it.stages(player) }
            if (pipeline.isEmpty()) {
                failures += "${player.id}: ${pipeline.stageSummary()}"
                continue
            }
            return placePlayer(player, pipeline.random())
        }
        error("$label: no player×square pair for $team. Tried:\n${failures.joinToString("\n")}")
    }

    /** Deploy a Prone reserve — Foul drills need an already-down target. */
    fun placeRandomProneReserve(
        team: TeamSide,
        candidateSquares: Collection<BoardSquare>,
        playerFilter: (Player) -> Boolean = { true },
    ): PlacedPlayer {
        val playable = candidateSquares.filter(::emptyPlayable)
        check(playable.isNotEmpty()) { "No playable square among candidates for prone place" }
        val prone = randomReserve(team, playerFilter).copy(status = PlayerStatus.PRONE)
        placePlayerOnSquare(prone, playable.random())
        return PlacedPlayer(prone, this)
    }

    fun placeLooseBallAt(square: BoardSquare): BoardSquare {
        require(emptyPlayable(square)) { "Square ${square.coordinate} is not empty playable" }
        val ball = nextBall()
        val coord = square.coordinate
        board = board.withSquareUpdates(
            mapOf(coord to square.withLooseBall(ball.id)),
            balls = board.balls + (ball.id to ball),
        )
        return square
    }

    /** Give an on-pitch reserve a ball (training setup only). */
    fun giveBallTo(placed: PlacedPlayer): PlacedPlayer {
        require(canHoldBall(placed.player)) { "Player ${placed.player.id} cannot hold a ball" }
        val ball = nextBall()
        val carrier = placed.player.copy(heldBallId = ball.id)
        board = board.copy(
            players = board.players + (carrier.id to carrier),
            balls = board.balls + (ball.id to ball),
        )
        return PlacedPlayer(carrier, this)
    }

    /**
     * Deploys up to [maxPlayers] remaining reserves (default: all). Training difficulty passes an
     * explicit limit for both trainer and opponent extras after scenario-required pieces.
     *
     * When a lesson thrower registered [registerBallCarrierThrowScatter], Hold-ball extras are
     * placed **first** into the thin non-ruler set — otherwise non-holders eat the open pitch and
     * holders starve (`No scatter square` after throw-disk filter).
     */
    fun scatterRemainingReserves(
        team: TeamSide,
        maxPlayers: Int = Int.MAX_VALUE,
        avoid: Collection<BoardSquare> = emptyList(),
        playerFilter: (Player) -> Boolean = { true },
        squareFilter: (Player) -> (BoardSquare) -> Boolean = { { true } },
        isolateFromTeam: TeamSide? = null,
    ): TrainingBoardBuilder {
        val avoidCoords = avoid.map { it.coordinate }.toSet()
        val batch = reserves(team).filter(playerFilter).shuffled().take(maxPlayers.coerceAtLeast(0))
        // Holders first only when the throw-disk guard is active; otherwise keep non-holders first
        // so ball-capable extras do not crowd lesson lanes before non-holders take leftover cells.
        val ordered = batch.partition { canHoldBall(it) }.let { (holders, nonHolders) ->
            if (ballCarrierThrowFrom != null) holders + nonHolders else nonHolders + holders
        }
        for (player in ordered) {
            val playable = squareFilter(player)
            val candidates = squareCandidates("scatter ${player.id}")
                .filter("emptyPlayable") { emptyPlayable(it) }
                // Lesson / lane avoid — exact coords from markScatterAvoid* / opposition lane marks.
                .filter("notAvoided") { it.coordinate !in avoidCoords }
                .filter("playerSquareFilter") { playable(it) }
                // isolateTrainerScatter: extras must not start adjacent to on-pitch teammates
                // (Fan Out / Lock Them Down / Clean Sweep adjacency would leak the claim).
                .filter("isolateFromTeam") { isolateFromTeam == null || !hasAdjacentTeammate(it, isolateFromTeam) }
            placePlayerOnSquare(player.copy(status = PlayerStatus.STANDING), candidates.random())
        }
        return this
    }

    /** Start a staged candidate set (see [SquareCandidates]). Prefer over nested [allOf]. */
    fun squareCandidates(label: String, seed: Collection<BoardSquare> = board.allSquares()): SquareCandidates =
        SquareCandidates(label, seed)

    fun allOf(vararg filters: (BoardSquare) -> Boolean): (BoardSquare) -> Boolean =
        { square -> filters.all { it(square) } }

    fun emptyPlayable(square: BoardSquare): Boolean =
        !square.isBlocked() && square.playerId == null

    fun emptyWithoutLooseBall(square: BoardSquare): Boolean =
        emptyPlayable(square) && square.looseBallId == null

    fun notOnOwnEndZone(team: TeamSide): (BoardSquare) -> Boolean =
        { square -> !square.isEndZone(team) }

    fun notEndZone(endZone: TeamSide): (BoardSquare) -> Boolean =
        { square -> !square.isEndZone(endZone) }

    fun withinRowsOf(
        endZone: TeamSide,
        distance: IntRange,
        excludeEndZone: Boolean = false,
    ): (BoardSquare) -> Boolean {
        val row = endZoneRow(endZone)
        return { square ->
            emptyPlayable(square) && !(excludeEndZone && square.isEndZone(endZone)) && abs(square.coordinate.y - row) in distance
        }
    }

    fun withinRowsOfForPlayer(
        endZone: TeamSide,
        minRows: Int,
        excludeEndZone: Boolean = false,
    ): (Player) -> (BoardSquare) -> Boolean = { player ->
        withinRowsOf(endZone, minRows..fixedMove(player), excludeEndZone)
    }

    fun withinMoveOf(
        endZone: TeamSide,
        excludeEndZone: Boolean = false,
    ): (Player) -> (BoardSquare) -> Boolean = { player ->
        withinRowsOf(endZone, 1..fixedMove(player), excludeEndZone)
    }

    /**
     * Square is farther from [endZone] than the player's fixed Move — they cannot Run into that End Zone
     * this action. Claim drills use this so the taught line is not competing with a Touchdown / Play to the Stands.
     */
    fun beyondMoveOf(endZone: TeamSide): (Player) -> (BoardSquare) -> Boolean = { player ->
        beyondMoveOfRows(player, endZone)
    }

    fun withinManhattanStepsOf(
        origin: Coordinate,
        steps: Int,
    ): (BoardSquare) -> Boolean = { square ->
        emptyWithoutLooseBall(square) && abs(square.coordinate.y - origin.y) + abs(square.coordinate.x - origin.x) <= steps
    }

    fun inEndZone(endZone: TeamSide): (BoardSquare) -> Boolean = { square ->
        square.isEndZone(endZone) && emptyPlayable(square)
    }

    fun emptyAdjacentSquares(
        square: BoardSquare,
        excluding: Collection<BoardSquare> = emptyList(),
        filter: (BoardSquare) -> Boolean = { true },
    ): Set<BoardSquare> {
        val excludeCoords = excluding.map { it.coordinate }.toSet()
        return squareCandidates("emptyAdjacent beside ${square.coordinate}", adjacentSquares(square))
            .filter("emptyPlayable", ::emptyPlayable)
            .filter("notExcluded") { it.coordinate !in excludeCoords }
            .filter("callerFilter", filter)
            .requireNonEmpty()
            .toSet()
    }

    fun adjacentSquares(square: BoardSquare): Set<BoardSquare> =
        neighbors(square, board).toSet()

    fun nearOwnEndZone(
        team: TeamSide,
        maxRows: Int,
        excludeEndZone: Boolean = true,
    ): (BoardSquare) -> Boolean {
        val row = endZoneRow(team)
        return { square ->
            !(excludeEndZone && square.isEndZone(team)) && abs(square.coordinate.y - row) <= maxRows
        }
    }

    /** Wide channel — keeps a backfield carrier's throw footprint off central scatter lanes. */
    fun nearSideline(minDistFromCenter: Int = 3): (BoardSquare) -> Boolean = { square ->
        abs(square.coordinate.x - 6) >= minDistFromCenter
    }

    fun endZoneRow(team: TeamSide): Int =
        board.allSquares().first { it.isEndZone(team) }.coordinate.y

    fun fixedMove(player: Player): Int =
        (player.profile.stats.move as MoveValue.Fixed).squares

    fun isFixedMove(player: Player): Boolean =
        player.profile.stats.move is MoveValue.Fixed

    fun canHoldBall(player: Player): Boolean =
        player.profile.stats.throwStat.canHoldBall()

    fun runPaths(player: Player, pathFilter: (Path) -> Boolean = { true }): List<Path> =
        engineRunPaths(player, board, fixedMove(player)).filter(pathFilter)

    /** Pathfind from a candidate square before the player is deployed (placement filters). */
    fun runPathsFromSquare(player: Player, start: BoardSquare, pathFilter: (Path) -> Boolean = { true }): List<Path> {
        val standing = player.copy(status = PlayerStatus.STANDING)
        val probeBoard = board.withSquareUpdates(
            mapOf(start.coordinate to start.withOccupant(standing.id)),
            players = board.players + (standing.id to standing),
        )
        return engineRunPaths(standing, probeBoard, fixedMove(player)).filter(pathFilter)
    }

    fun runPathEndsInEndZone(endZone: TeamSide): (Path) -> Boolean =
        { path -> board.squareAt(path.squares.last()).isEndZone(endZone) }

    fun squaresAlongRunPaths(paths: List<Path>): List<BoardSquare> =
        paths.flatMap { path -> path.squares.drop(1).map { board.squareAt(it) } }.distinct()

    fun manhattanDistance(a: BoardSquare, b: BoardSquare): Int =
        abs(a.coordinate.x - b.coordinate.x) + abs(a.coordinate.y - b.coordinate.y)

    private fun hasAdjacentTeammate(square: BoardSquare, team: TeamSide): Boolean =
        adjacentSquares(square).any { adj ->
            adj.playerId?.let { board.players[it] }?.let { it.team == team && it.status == PlayerStatus.STANDING } == true
        }

    private fun placePlayerWithBall(
        player: Player,
        squareFilter: (BoardSquare) -> Boolean,
    ): PlacedPlayer {
        val square = randomSquare("placePlayerWithBall/${player.id}", squareFilter)
        return placePlayerWithBallOnSquare(player, square)
    }

    private fun placePlayerWithBall(
        player: Player,
        candidateSquares: Collection<BoardSquare>,
    ): PlacedPlayer {
        val playable = squareCandidates("placePlayerWithBall/${player.id}", candidateSquares)
            .filter("emptyPlayable", ::emptyPlayable)
            .random()
        return placePlayerWithBallOnSquare(player, playable)
    }

    private fun placePlayerWithBallOnSquare(player: Player, square: BoardSquare): PlacedPlayer {
        val ball = nextBall()
        val standing = player.copy(status = PlayerStatus.STANDING, heldBallId = ball.id)
        val coord = square.coordinate
        board = board.withSquareUpdates(
            mapOf(coord to square.withOccupant(standing.id)),
            players = board.players + (standing.id to standing),
            balls = board.balls + (ball.id to ball),
            reserves = board.reserves + (standing.team to board.reserves.getValue(standing.team).let { dugout ->
                dugout.copy(playerIds = dugout.playerIds - standing.id)
            }),
        )
        return PlacedPlayer(standing, this)
    }

    private fun placePlayer(player: Player, candidateSquares: Collection<BoardSquare>): PlacedPlayer {
        val playable = squareCandidates("placePlayer/${player.id}", candidateSquares)
            .filter("emptyPlayable", ::emptyPlayable)
            .random()
        return placePlayer(player, playable)
    }

    private fun placePlayer(player: Player, square: BoardSquare): PlacedPlayer {
        val standing = player.copy(status = PlayerStatus.STANDING)
        placePlayerOnSquare(standing, square)
        return PlacedPlayer(standing, this)
    }

    private fun placePlayer(
        player: Player,
        squareFilter: (BoardSquare) -> Boolean,
    ): PlacedPlayer {
        val square = randomSquare("placePlayer/${player.id}", squareFilter)
        return placePlayer(player, square)
    }

    private fun beyondMoveOfRows(player: Player, endZone: TeamSide): (BoardSquare) -> Boolean {
        val row = endZoneRow(endZone)
        val move = fixedMove(player)
        return { square ->
            emptyPlayable(square) && !square.isEndZone(endZone) && abs(square.coordinate.y - row) > move
        }
    }

    private fun randomSquare(label: String, filter: (BoardSquare) -> Boolean): BoardSquare =
        squareCandidates(label).filter("matches", filter).random()

    private fun placePlayerOnSquare(player: Player, square: BoardSquare) {
        require(emptyPlayable(square)) { "Square ${square.coordinate} is not empty playable" }
        val coord = square.coordinate
        board = board.withSquareUpdates(
            mapOf(coord to square.withOccupant(player.id)),
            players = board.players + (player.id to player),
            reserves = board.reserves + (player.team to board.reserves.getValue(player.team).let { dugout ->
                dugout.copy(playerIds = dugout.playerIds - player.id)
            }),
        )
    }

    private fun nextBall(): Ball {
        val id = BallId("ball-${board.balls.size}")
        return Ball(id)
    }
}

/** A player deployed from reserves by [TrainingBoardBuilder]. */
class PlacedPlayer internal constructor(
    val player: Player,
    private val builder: TrainingBoardBuilder,
) {
    val team: TeamSide get() = player.team
    val square: BoardSquare get() = builder.board.findSquare(player.id)
    val coordinate: Coordinate get() = square.coordinate

    fun adjacentSquares(): Set<BoardSquare> = builder.adjacentSquares(square)
}

private fun BoardSquare.withOccupant(playerId: com.mhaefele.gridiron.shared.PlayerId): BoardSquare = when (this) {
    is FieldSquare       -> copy(playerId = playerId)
    is EndZoneSquare     -> copy(playerId = playerId)
    is TrapdoorSquare    -> copy(playerId = playerId)
    is BlockedSquare     -> error("Cannot place player on blocked square $coordinate")
    is OutOfBoundsSquare -> error("Cannot place player on out-of-bounds square $coordinate")
}

private fun BoardSquare.withLooseBall(ballId: BallId): BoardSquare = when (this) {
    is FieldSquare       -> copy(looseBallId = ballId)
    is EndZoneSquare     -> copy(looseBallId = ballId)
    is TrapdoorSquare    -> copy(looseBallId = ballId)
    is BlockedSquare     -> error("Cannot place ball on blocked square $coordinate")
    is OutOfBoundsSquare -> error("Cannot place ball on out-of-bounds square $coordinate")
}
