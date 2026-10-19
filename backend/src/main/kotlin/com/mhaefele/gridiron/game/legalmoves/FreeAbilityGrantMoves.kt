package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.ability.isBlockTargetLegal
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.action.utils.playerMarksOpponent
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.action.utils.inThrowRulerRange
import com.mhaefele.gridiron.game.action.utils.throwTarget
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide

/** Mid-action free follow-up menus (ability grants parked on Ongoing* stages). Pre-turn Undying uses [standUpMove] directly. */

/** Geometry menu for a parked free-follow-up ability — [PlayerAbility] is the receiver so the when stays on the ability. */
fun PlayerAbility.followUpMoves(player: Player, board: Board): List<LegalAction> =
    when (this) {
        PlayerAbility.`Relentless After Mark`,
        PlayerAbility.`Relentless After Block`,
        PlayerAbility.Charge,
                                             -> freeBlockMoves(player, board)
        PlayerAbility.`Strike and Fade` -> freeStrikeAndFadeMoves(player, board)
        PlayerAbility.`Pass on the Run` -> freeShortThrowMoves(player, board)
        PlayerAbility.`Catch and Go`    -> freeRunMoves(player, board)
        PlayerAbility.Rally,
        PlayerAbility.Undying,
             -> standUpMove(player, board)
        else -> error("No legal moves for $this")
    }

fun freeBlockMoves(player: Player, board: Board): List<LegalAction> {
    require(player.status == PlayerStatus.STANDING) { "Free Block requires standing player, was ${player.status}" }
    val square = board.findSquare(player.id)
    return neighbors(square, board)
        .mapNotNull { neighbor -> neighbor.playerId?.let { board.players.getValue(it) } }
        .filter { target -> playerMarksOpponent(player, target, board) }
        .filter { target -> isBlockTargetLegal(player, target, board) }
        .map { target -> LegalAction.Block(player.id, board.findSquare(target.id).coordinate) }
}

fun freeStrikeAndFadeMoves(player: Player, board: Board): List<LegalAction> {
    require(player.status == PlayerStatus.STANDING)
    // Card: free Run up to 2 spaces; may end adjacent to opposition.
    val paths = runPaths(player, board, moveAllowance = 2, allowEndAdjacent = true)
    return pathMoves(player.id, paths, LegalAction::Run)
}

/** Bonus Slip Away — free Sidestep for the marked teammate. */
fun freeSidestepMoves(player: Player, board: Board): List<LegalAction> {
    require(player.status == PlayerStatus.STANDING)
    return pathMoves(player.id, sidestepPaths(player, board), LegalAction::Sidestep)
}

fun freeMarkMoves(player: Player, board: Board, mustEndAdjacentTo: Coordinate? = null): List<LegalAction> {
    require(player.status == PlayerStatus.STANDING)
    val paths = markPaths(player, board).filter { path ->
        mustEndAdjacentTo == null || path.squares.last().isAdjacentTo(mustEndAdjacentTo)
    }
    return pathMoves(player.id, paths, LegalAction::Mark)
}

fun cutOffReachableOpenAlly(
    board: Board,
    coach: TeamSide,
    runnerAt: Coordinate,
): Player? =
    board.players.values.firstOrNull { ally ->
        ally.team == coach && ally.status == PlayerStatus.STANDING && standingStatus(ally, board) == StandingStatus.OPEN && freeMarkMoves(ally, board, runnerAt).isNotEmpty()
    }

fun mayIntervene(
    board: Board,
    coach: TeamSide,
    runnerId: PlayerId,
): Boolean {
    val runnerAt = board.findSquare(runnerId).coordinate
    return cutOffReachableOpenAlly(board, coach, runnerAt) != null
}

private fun Coordinate.isAdjacentTo(other: Coordinate): Boolean {
    val dx = kotlin.math.abs(x - other.x)
    val dy = kotlin.math.abs(y - other.y)
    return dx <= 1 && dy <= 1 && (dx != 0 || dy != 0)
}

fun freeRunMoves(player: Player, board: Board): List<LegalAction> {
    require(player.status == PlayerStatus.STANDING)
    return when (val move = player.profile.stats.move) {
        is MoveValue.Fixed -> pathMoves(player.id, runPaths(player, board, move.squares), LegalAction::Run)
        MoveValue.RandomD6 -> listOf(LegalAction.DeclareRun(player.id, board.findSquare(player.id).coordinate))
    }
}

fun freeShortThrowMoves(player: Player, board: Board): List<LegalAction> {
    require(player.holdsBall())
    val throwerSquare = board.findSquare(player.id)
    return board.players.values
        .filter { teammate ->
            teammate.team == player.team && teammate.id != player.id && teammate.status == PlayerStatus.STANDING && inThrowRulerRange(throwerSquare, board.findSquare(teammate.id))
        }
        .map { teammate -> throwTarget(player, teammate, board) }
        .filter { it.range == com.mhaefele.gridiron.shared.ThrowRange.SHORT || it.range == com.mhaefele.gridiron.shared.ThrowRange.HAND_OFF }
        .map { LegalAction.Throw(player.id, it) }
}

fun standUpMove(player: Player, board: Board): List<LegalAction> {
    require(player.status == PlayerStatus.PRONE)
    return listOf(LegalAction.StandUp(player.id, board.findSquare(player.id).coordinate))
}

fun adjacentProneAllies(player: Player, board: Board): List<Player> {
    val square = board.findSquare(player.id)
    return neighbors(square, board)
        .mapNotNull { it.playerId?.let { id -> board.players.getValue(id) } }
        .filter { ally -> ally.team == player.team && ally.status == PlayerStatus.PRONE }
}

fun hasMarkableBlockTarget(player: Player, board: Board): Boolean =
    freeBlockMoves(player, board).isNotEmpty()
