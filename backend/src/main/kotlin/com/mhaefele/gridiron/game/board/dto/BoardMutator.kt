package com.mhaefele.gridiron.game.board.dto

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.model.board.Ball
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.BlockedSquare
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.EndZoneSquare
import com.mhaefele.gridiron.model.board.FieldSquare
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.board.TrapdoorSquare
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility

/**
 * Mutable working state for board changes. [board] is replaced after each mutation; [events]
 * records exactly what changed (immutable [Board] snapshots underneath).
 *
 * **Invariant:** each public mutation method performs one board action and appends exactly one
 * [DomainEvent] that names a beat a player would recognize on the pitch.
 *
 * Lookups ([getPlayer], [square], [playerCoordinate]) always read the current [board]. [Player]
 * values are immutable snapshots — safe within one mutation after a single read, but never
 * cache across [board] assignments or between mutation method calls.
 *
 * The event list can be shared with a parent [com.mhaefele.gridiron.game.turn.MatchMutator] so
 * board beats and turn beats land in one ordered log. Prefer mutating via this accumulator —
 * avoid `board.copy(...)` at call sites; pass the current [Board] in and let mutation methods own
 * registry updates.
 */
class BoardMutator(
    initial: Board,
    private val _events: MutableList<DomainEvent> = mutableListOf(),
    /**
     * Stamps [DomainEvent.turnPhase] / [DomainEvent.turnStep] before the event is appended.
     * Board code must not depend on turn clock / [com.mhaefele.gridiron.model.TurnState];
     * [com.mhaefele.gridiron.game.turn.MatchMutator] wires this so board and turn beats share
     * one ordered log with the same stamp. Standalone board tests leave the default no-op.
     */
    private val stampTurnClock: (DomainEvent) -> Unit = {},
) {
    var board: Board = initial
        private set

    val events: List<DomainEvent> get() = _events

    fun record(event: DomainEvent) {
        stampTurnClock(event)
        _events.add(event)
    }

    /** Current [Player] on [board] — re-call after mutations; do not cache across [board] updates. */
    fun getPlayer(playerId: PlayerId): Player = board.players.getValue(playerId)

    fun getPlayerAt(coord: Coordinate): Player =
        square(coord).playerId?.let { getPlayer(it) } ?: throw IllegalArgumentException("No player at $coord")

    fun square(coord: Coordinate): BoardSquare = board.squareAt(coord)

    fun playerCoordinate(playerId: PlayerId): Coordinate = board.findSquare(playerId).coordinate

    fun playerCoordinate(player: Player): Coordinate = playerCoordinate(player.id)

    private fun updatePlayer(playerId: PlayerId, transform: (Player) -> Player) { board = board.copy(players = board.players +(playerId to transform(getPlayer(playerId)))) }

    fun actionStarted(playerId: PlayerId, actionType: ActionType) {
        record(DomainEvent.ActionStarted(playerId, playerAtOnPitch(playerId), actionType))
    }

    fun actionComplete(playerId: PlayerId, actionType: ActionType) {
        record(DomainEvent.ActionComplete(playerId, playerAtOnPitch(playerId), actionType))
    }

    private fun playerAtOnPitch(playerId: PlayerId): Coordinate? =
        when (getPlayer(playerId).status) {
            PlayerStatus.RESERVES                     -> null
            PlayerStatus.STANDING, PlayerStatus.PRONE -> playerCoordinate(playerId)
        }

    fun playerMoved(
        playerId: PlayerId,
        from: Coordinate,
        to: Coordinate,
        ability: PlayerAbility? = null,
        bonusPlay: BonusPlay? = null,
    ) {
        val player = getPlayer(playerId)
        require(player.status in setOf(PlayerStatus.STANDING, PlayerStatus.PRONE)) { "Only on-pitch players can move, was ${player.status} for $playerId" }
        require(playerCoordinate(playerId) == from) { "Player $playerId is not on $from" }
        val destination = square(to)
        require(destination.playerId == null) { "Square $to is occupied" }
        require(!destination.isBlocked()) { "Square $to is blocked" }

        board = board.withSquareUpdates(mapOf(from to square(from).withOccupant(null), to to destination.withOccupant(playerId)))
        record(DomainEvent.PlayerMoved(playerId, from, to, ability = ability, bonusPlay = bonusPlay))
    }

    /** One orthogonal step — used when walking a [com.mhaefele.gridiron.shared.Path] square by square. */
    fun playerMovedOneStep(playerId: PlayerId, from: Coordinate, to: Coordinate) =
        playerMoved(playerId, from, to)

    fun playerKnockedDown(playerId: PlayerId) {
        val player = getPlayer(playerId)
        require(player.status == PlayerStatus.STANDING) { "Only standing players can be knocked down, was ${player.status} for $playerId" }
        val at = playerCoordinate(playerId)
        updatePlayer(playerId) { it.copy(status = PlayerStatus.PRONE) }
        record(DomainEvent.PlayerStatusChanged(playerId, at, PlayerStatus.STANDING, PlayerStatus.PRONE))
    }

    fun playerStoodUp(playerId: PlayerId) {
        val player = getPlayer(playerId)
        require(player.status == PlayerStatus.PRONE) { "Only prone players can stand up, was ${player.status} for $playerId" }
        val at = playerCoordinate(playerId)
        updatePlayer(playerId) { it.copy(status = PlayerStatus.STANDING) }
        record(DomainEvent.PlayerStatusChanged(playerId, at, PlayerStatus.PRONE, PlayerStatus.STANDING))
    }

    /** Removes an on-pitch player to their team's dugout after injury. */
    fun playerInjured(playerId: PlayerId) =
        removePlayerToReserves(playerId) { id, from -> DomainEvent.PlayerInjured(id, from) }

    /** Removes scorer to dugout after a touchdown. */
    fun playerRemovedAfterTouchdown(playerId: PlayerId) =
        removePlayerToReserves(playerId) { id, from -> DomainEvent.PlayerRemovedAfterTouchdown(id, from) }

    private fun removePlayerToReserves(
        playerId: PlayerId,
        event: (PlayerId, Coordinate) -> DomainEvent,
    ) {
        val player = getPlayer(playerId)
        require(player.status in setOf(PlayerStatus.STANDING, PlayerStatus.PRONE)) { "Only on-pitch players can be removed, was ${player.status} for $playerId" }
        // Dugout tokens never hold the ball — callers must drop / remove it first.
        // Otherwise beginPlayerAction → heldBallsByTeam → findSquare throws "not on the pitch".
        require(player.heldBallId == null) { "Cannot send ball carrier $playerId to dugout; drop or remove the ball first" }
        val coord = playerCoordinate(playerId)
        val dugout = board.reserves.getValue(player.team)
        board = board.withSquareUpdates(
            mapOf(coord to square(coord).withOccupant(null)),
            players = board.players + (playerId to player.copy(status = PlayerStatus.RESERVES)),
            reserves = board.reserves + (player.team to dugout.copy(playerIds = dugout.playerIds + playerId)),
        )
        record(event(playerId, coord))
    }

    fun playerDeployedFromReserves(playerId: PlayerId, placement: Coordinate) {
        val player = getPlayer(playerId)
        require(player.status == PlayerStatus.RESERVES) { "Only reserves players can deploy, was ${player.status} for $playerId" }
        val dugout = board.reserves.getValue(player.team)
        require(playerId in dugout.playerIds) { "Player $playerId is not in ${player.team} dugout" }

        val target = square(placement)
        require(target.isEndZone(player.team)) { "Reserves must deploy to own end zone, got $placement for ${player.team}" }
        require(target.playerId == null && !target.isBlocked()) { "Reserves placement square $placement is not empty" }

        board = board.withSquareUpdates(
            mapOf(placement to target.withOccupant(playerId)),
            players = board.players + (playerId to player.copy(status = PlayerStatus.STANDING)),
            reserves = board.reserves + (player.team to dugout.copy(playerIds = dugout.playerIds - playerId)),
        )
        record(DomainEvent.PlayerDeployedFromReserves(playerId, placement))
    }

    /** Places a new standing player on an empty pitch square (match setup). */
    fun playerPlacedOnPitch(player: Player, coord: Coordinate) {
        require(player.status == PlayerStatus.STANDING) { "Setup placement requires STANDING status, was ${player.status}" }
        require(player.id !in board.players) { "Player ${player.id} already on board" }
        val target = square(coord)
        require(target.playerId == null && !target.isBlocked()) { "Square $coord is not empty" }

        board = board.withSquareAt(coord, target.withOccupant(player.id), players = board.players + (player.id to player))
        record(DomainEvent.PlayerPlacedOnPitch(player.id, coord))
    }

    /** Player picks up a loose ball on [at] while standing there. */
    fun ballPickedUpByPlayer(
        playerId: PlayerId,
        at: Coordinate,
        ability: PlayerAbility? = null,
    ) {
        val looseBallId = square(at).looseBallId
        val heldBy = getPlayer(playerId)
        require(heldBy.heldBallId == null) { "Player $playerId already holds a ball" }
        require(looseBallId != null) { "Ball is not loose at $at" }
        require(playerCoordinate(playerId) == at) { "Player $playerId is not on $at" }
        board = board.withSquareUpdates(
            mapOf(at to square(at).withLooseBall(null)),
            players = board.players + (playerId to heldBy.copy(heldBallId = looseBallId)),
        )
        record(DomainEvent.BallPickedUp(playerId, at, ability = ability))
    }

    /** Carrier drops the ball loose on their square. */
    fun ballDroppedByPlayer(playerId: PlayerId) {
        val carrier = getPlayer(playerId)
        val ballId = carrier.heldBallId ?: error("Player $playerId is not holding a ball")
        val at = playerCoordinate(playerId)
        board = board.withSquareUpdates(
            mapOf(at to square(at).withLooseBall(ballId)),
            players = board.players + (playerId to carrier.copy(heldBallId = null)),
        )
        record(DomainEvent.BallDropped(playerId, at))
    }

    /** Successful throw or hand-off — ball moves from thrower to catcher in one beat. */
    fun ballThrown(fromPlayerId: PlayerId, toPlayerId: PlayerId) {
        val thrower = getPlayer(fromPlayerId)
        val catcher = getPlayer(toPlayerId)
        val ballId = thrower.heldBallId
        require(catcher.heldBallId == null) { "Player $toPlayerId already holds a ball" }
        val from = playerCoordinate(fromPlayerId)
        val to = playerCoordinate(toPlayerId)
        board = board.copy(
            players = board.players + (fromPlayerId to thrower.copy(heldBallId = null)) +(toPlayerId to catcher.copy(heldBallId = ballId)),)
        record(DomainEvent.BallThrown(from, to))
    }

    /** Failed throw — ball lands loose at [to], then bounces from there. */
    fun ballThrowMissed(holderId: PlayerId, from: Coordinate, to: Coordinate) {
        val holder = getPlayer(holderId)
        val ballId = holder.heldBallId ?: error("Player $holderId is not holding a ball")
        val landing = square(to)
        require(!landing.isBlocked()) { "Square $to is blocked" }
        board = board.withSquareUpdates(
            mapOf(to to landing.withLooseBall(ballId)),
            players = board.players + (holderId to holder.copy(heldBallId = null)),
        )
        record(DomainEvent.BallThrowMissed(from, to))
    }

    /** Move the loose ball on [from] onto [to]. */
    fun ballBounced(from: Coordinate, to: Coordinate) {
        val fromSquare = square(from)
        val ballId = fromSquare.looseBallId ?: error("No loose ball at $from")
        require(ballId in board.balls) { "Unknown ball $ballId" }
        val toSquare = square(to)
        require(!toSquare.isBlocked()) { "Square $to is blocked" }
        require(toSquare.looseBallId == null) { "Square $to already has a loose ball" }
        board = board.withSquareUpdates(mapOf(from to fromSquare.withLooseBall(null), to to toSquare.withLooseBall(ballId)))
        record(DomainEvent.BallBounced(from, to))
    }

    /**
     * Bounce deflected by another loose ball on [toward] — ball stays at [from] for the next roll.
     * Same [DomainEvent.BallBounced] beat for replay; board state unchanged.
     */
    fun ballBounceDeflected(from: Coordinate, toward: Coordinate) {
        record(DomainEvent.BallBounced(from, toward))
    }

    /** Removes [ballId] from play — clears any hold or loose placement. */
    fun ballRemovedFromPlay(ballId: BallId) {
        require(ballId in board.balls) { "Unknown ball $ballId" }
        val holderId = board.players.values.firstOrNull { it.heldBallId == ballId }?.id
        val players = if (holderId != null) {
            board.players + (holderId to board.players.getValue(holderId).copy(heldBallId = null))
        } else {
            board.players
        }
        val squareUpdates = board.allSquares()
            .filter { it.looseBallId == ballId }
            .associate { square -> square.coordinate to square.withLooseBall(null) }
        board = board.withSquareUpdates(
            updates = squareUpdates,
            players = players,
            balls = board.balls - ballId,
        )
        val looseAt = squareUpdates.keys.singleOrNull()
        record(
            when {
                holderId != null -> DomainEvent.BallRemovedFromPlay(playerId = holderId)
                looseAt != null  -> DomainEvent.BallRemovedFromPlay(at = looseAt)
                else             -> error("Ball $ballId is not held or loose on the pitch")
            },
        )
    }

    /**
     * New ball enters play loose on [coord] — kickoff setup or trapdoor emergence.
     * Assigns the next [BallId], registers it, and places it in one beat.
     */
    fun ballSpawnedLooseAt(coord: Coordinate): BallId {
        val ballId = board.nextBallId()
        board = board.withSquareUpdates(
            mapOf(coord to square(coord).withLooseBall(ballId)),
            balls = board.balls + (ballId to Ball(ballId)),
        )
        record(DomainEvent.BallPlacedLoose(coord))
        return ballId
    }

}

private fun Board.nextBallId(): BallId {
    var index = balls.size
    while (BallId("ball-$index") in balls) {
        index++
    }
    return BallId("ball-$index")
}

private fun BoardSquare.withOccupant(playerId: PlayerId?): BoardSquare = when (this) {
    is FieldSquare       -> copy(playerId = playerId)
    is EndZoneSquare     -> copy(playerId = playerId)
    is BlockedSquare     -> copy(playerId = playerId)
    is TrapdoorSquare    -> copy(playerId = playerId)
    is OutOfBoundsSquare -> error("Cannot place player on out-of-bounds square $coordinate")
}

private fun BoardSquare.withLooseBall(ballId: BallId?): BoardSquare = when (this) {
    is FieldSquare       -> copy(looseBallId = ballId)
    is EndZoneSquare     -> copy(looseBallId = ballId)
    is BlockedSquare     -> copy(looseBallId = ballId)
    is TrapdoorSquare    -> copy(looseBallId = ballId)
    is OutOfBoundsSquare -> error("Cannot place ball on out-of-bounds square $coordinate")
}
