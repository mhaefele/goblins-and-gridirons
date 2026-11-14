package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.game.action.ability.isBlockTargetLegal
import com.mhaefele.gridiron.game.action.utils.ThrowRulerOffsets
import com.mhaefele.gridiron.game.action.utils.blockDiceCountForTarget
import com.mhaefele.gridiron.game.action.utils.canMakeThrowTeammateAction
import com.mhaefele.gridiron.game.action.utils.distance
import com.mhaefele.gridiron.game.action.utils.inThrowRulerRange
import com.mhaefele.gridiron.game.action.utils.isThrowableTeammate
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.action.utils.playerMarksOpponent
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.action.utils.throwTarget
import com.mhaefele.gridiron.game.action.utils.throwTeammateLandingTarget
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.canHoldBall
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange

/**
 * Per-action board geometry — no turn budget, hand cards, or action-type enumeration.
 * Selecting-[com.mhaefele.gridiron.model.pending.RegularPlayerAction] owns the type menu in
 * [com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator].
 */
fun runMoves(player: Player, board: Board, bonuses: PathBonusFlags = PathBonusFlags()): List<LegalAction> =
    when (val move = player.profile.stats.move) {
        is MoveValue.Fixed -> {
            val allowance = move.squares + bonuses.sprintMoveBonus()
            pathMoves(player.id, runPaths(player, board, allowance, bonuses), LegalAction::Run)
        }
        MoveValue.RandomD6 -> listOf(LegalAction.DeclareRun(player.id, board.findSquare(player.id).coordinate))
    }

fun markMoves(player: Player, board: Board, bonuses: PathBonusFlags = PathBonusFlags()): List<LegalAction> =
    pathMoves(player.id, markPaths(player, board, bonuses), LegalAction::Mark)

fun sidestepMoves(player: Player, board: Board): List<LegalAction> =
    pathMoves(player.id, sidestepPaths(player, board), LegalAction::Sidestep)

fun blockMoves(player: Player, board: Board, bonuses: PathBonusFlags = PathBonusFlags()): List<LegalAction> =
    blockTargets(player, board).map { dest ->
        LegalAction.Block(player.id, dest, blockDiceCount = blockDiceCountForTarget(player, dest, board, bonuses.gangUp))
    }

fun foulMoves(player: Player, board: Board): List<LegalAction> =
    foulTargets(player, board).map { LegalAction.Foul(player.id, it) }

fun throwMoves(player: Player, board: Board, hailMary: Boolean = false): List<LegalAction> =
    throwTargets(player, board, hailMary).map { LegalAction.Throw(player.id, it) }

fun standUpMoves(player: Player, board: Board): List<LegalAction> =
    listOf(LegalAction.StandUp(player.id, board.findSquare(player.id).coordinate))

fun reservesMoves(player: Player, board: Board): List<LegalAction> =
    reservesPlacements(player, board).map { LegalAction.Reserves(player.id, it) }

fun pathMoves(
    playerId: PlayerId,
    paths: List<Path>,
    toAction: (PlayerId, Path) -> LegalAction.PathMovement,
): List<LegalAction> = paths.map { toAction(playerId, it) }

fun blockTargets(player: Player, board: Board): List<Coordinate> {
    require(player.status == PlayerStatus.STANDING) { "Block applies only to standing players, was ${player.status} for ${player.id}" }
    if (player.profile.ability == PlayerAbility.Lobber) {
        // Card: Block whilst Open only — return empty when Marked (do not throw during legal-move enum).
        if (standingStatus(player, board) != StandingStatus.OPEN) return emptyList()
        return lobberBlockTargets(player, board)
    }
    if (standingStatus(player, board) != StandingStatus.MARKED) return emptyList()
    return adjacentBlockTargets(player, board)
}

/** Adjacent Prone opposition squares an Open [player] may Foul (digest § Foul). */
fun foulTargets(player: Player, board: Board): List<Coordinate> {
    require(player.status == PlayerStatus.STANDING) { "Foul applies only to standing players, was ${player.status} for ${player.id}" }
    require(standingStatus(player, board) == StandingStatus.OPEN) { "Foul applies only to Open players, was ${standingStatus(player, board)} for ${player.id}" }
    val square = board.findSquare(player.id)
    return neighbors(square, board)
        .mapNotNull { neighbor -> neighbor.playerId?.let { board.players.getValue(it) } }
        .filter { occupant -> occupant.team != player.team && occupant.status == PlayerStatus.PRONE }
        .map { board.findSquare(it.id).coordinate }
}

private fun adjacentBlockTargets(player: Player, board: Board): List<Coordinate> {
    val square = board.findSquare(player.id)
    return neighbors(square, board)
        .mapNotNull { neighbor -> neighbor.playerId?.let { board.players.getValue(it) } }
        .filter { occupant -> playerMarksOpponent(player, occupant, board) }
        .filter { occupant -> isBlockTargetLegal(player, occupant, board) }
        .map { board.findSquare(it.id).coordinate }
}

private fun lobberBlockTargets(player: Player, board: Board): List<Coordinate> =
    board.players.values
        .filter { opponent ->
            opponent.team != player.team && opponent.status == PlayerStatus.STANDING && distance(board.findSquare(player.id), board.findSquare(opponent.id), board) in 1..3
        }
        .filter { opponent -> isBlockTargetLegal(player, opponent, board) }
        .map { board.findSquare(it.id).coordinate }

fun freeShortThrowMovesForQuickRelease(player: Player, board: Board): List<LegalAction> {
    require(player.holdsBall())
    val throwerSquare = board.findSquare(player.id)
    return board.players.values
        .filter { teammate ->
            teammate.team == player.team && teammate.id != player.id && teammate.status == PlayerStatus.STANDING && inThrowRulerRange(throwerSquare, board.findSquare(teammate.id))
        }
        .map { teammate -> throwTarget(player, teammate, board) }
        .filter { it.range == ThrowRange.SHORT || it.range == ThrowRange.HAND_OFF }
        .map { LegalAction.QuickReleaseThrow(player.id, it) }
}

fun throwTargets(player: Player, board: Board, hailMary: Boolean = false): List<ThrowTarget> {
    require(player.holdsBall()) { "Throw requires a ball carrier, ${player.id} holds no ball" }
    val teammates = board.players.values.filter { teammate ->
        teammate.team == player.team && teammate.id != player.id && teammate.status == PlayerStatus.STANDING && teammate.profile.stats.throwStat.canHoldBall()
    }
    return if (hailMary) {
        // Hail Mary: any teammate on the board (range/obstruction ignored for target list).
        teammates.map { teammate -> throwTarget(player, teammate, board, hailMary = true) }
    } else {
        val throwerSquare = board.findSquare(player.id)
        teammates
            .filter { teammate -> inThrowRulerRange(throwerSquare, board.findSquare(teammate.id)) }
            .map { teammate -> throwTarget(player, teammate, board) }
    }
}

/** Step 1: adjacent throwable team-mates for an Ogre/Troll/Oakwalker. */
fun throwTeammateSelectMoves(player: Player, board: Board): List<LegalAction.ThrowTeammateSelect> {
    require(player.canMakeThrowTeammateAction()) { "Throw Team-mate requires canThrowTeammate, was ${player.profile.name}" }
    require(!player.holdsBall()) { "Throw Team-mate requires no ball, ${player.id} holds one" }
    val throwerSquare = board.findSquare(player.id)
    return board.players.values
        .filter { teammate -> isThrowableTeammate(player, teammate) }
        .filter { teammate ->
            distance(throwerSquare, board.findSquare(teammate.id), board) == 1
        }
        .map { teammate ->
            LegalAction.ThrowTeammateSelect(
                playerId = player.id,
                teammateId = teammate.id,
                targetCoordinate = board.findSquare(teammate.id).coordinate,
            )
        }
}

/** Step 2: landing squares within Throw Ruler range, plus cancel. */
fun throwTeammateLandingMoves(
    thrower: Player,
    teammateId: PlayerId,
    board: Board,
): List<LegalAction> {
    val teammate = board.player(teammateId)
    require(isThrowableTeammate(thrower, teammate)) { "$teammateId is not a throwable team-mate for ${thrower.id}" }
    val throwerAt = board.findSquare(thrower.id).coordinate
    val teammateAt = board.findSquare(teammateId).coordinate
    val squareByCoord = board.allSquares().associateBy { it.coordinate }
    val landings = ThrowRulerOffsets.inRange.asSequence()
        .mapNotNull { offset -> squareByCoord[Coordinate(throwerAt.x + offset.dx, throwerAt.y + offset.dy)] }
        .filter { square -> square !is com.mhaefele.gridiron.model.board.OutOfBoundsSquare }
        .filter { square -> !square.isBlocked() }
        .filter { square -> square.coordinate != teammateAt }
        .map { square -> throwTeammateLandingTarget(thrower, square.coordinate, board) }
        .map { target -> LegalAction.ThrowTeammate(thrower.id, teammateId, target) }
        .toList()
    return landings + LegalAction.CancelThrowTeammate(thrower.id)
}

/**
 * Own end-zone vacancies for a Reserves Action (digest § Reserves).
 * Prefer squares that are not adjacent to standing opposition and not on a ball; if none,
 * fall back to any vacant end-zone square (**unless no other option**). Occupied end zone → empty.
 */
fun reservesPlacements(player: Player, board: Board): List<Coordinate> {
    val vacant = board.allSquares()
        .filter { square -> square.isEndZone(player.team) }
        .filter { square -> square.playerId == null && !square.isBlocked() }
    if (vacant.isEmpty()) return emptyList()
    val preferred = vacant.filter { square ->
        square.looseBallId == null && !isAdjacentToStandingOpposition(square, board, player.team)
    }
    return (preferred.ifEmpty { vacant }).map { it.coordinate }
}

private fun isAdjacentToStandingOpposition(
    square: BoardSquare,
    board: Board,
    team: TeamSide,
): Boolean =
    neighbors(square, board)
        .mapNotNull { neighbor -> neighbor.playerId?.let { board.players.getValue(it) } }
        .any { occupant -> occupant.team != team && occupant.status == PlayerStatus.STANDING }
