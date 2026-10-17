package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.game.action.utils.canMakeThrowTeammateAction
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.action.utils.standingStatus

/** Action types allowed for an Open or Marked standing player (digest § Actions by status). */
fun allowedActionTypes(standing: StandingStatus): Set<ActionType> = when (standing) {
    StandingStatus.OPEN   -> setOf(ActionType.RUN, ActionType.MARK)
    StandingStatus.MARKED -> setOf(ActionType.BLOCK, ActionType.SIDESTEP)
}

/**
 * Action types a [player] may attempt from current board geometry and status.
 * Throw requires an Open carrier (digest § Actions by status); Marked may not Throw unless Ice Cold
 * ([iceCold] — hand-card expansion only, not turn state).
 * Throw Team-mate requires Open (or Ice Cold), no ball, and an Ogre/Troll/Oakwalker.
 * Foul requires Open and an adjacent Prone opposition player (digest § Foul).
 * Juggernaut may Run whilst Marked (team card).
 */
fun allowedActionTypes(
    player: Player,
    board: Board,
    iceCold: Boolean = false,
): Set<ActionType> = when (player.status) {
    PlayerStatus.PRONE    -> setOf(ActionType.STAND_UP)
    PlayerStatus.RESERVES -> setOf(ActionType.RESERVES)
    PlayerStatus.STANDING -> {
        val standing = standingStatus(player, board)
        val base = allowedActionTypes(standing).let { types ->
            var result = types
            if (standing == StandingStatus.MARKED && player.hasAbility(PlayerAbility.Juggernaut)) {
                result = result + ActionType.RUN
            }
            // Lobber: Block only whilst Open (ranged). Marked Lobbers do not Block.
            if (player.hasAbility(PlayerAbility.Lobber)) {
                result = if (standing == StandingStatus.OPEN) {
                    result + ActionType.BLOCK
                } else {
                    result - ActionType.BLOCK
                }
            }
            result
        }
        val openOrNerves = standing == StandingStatus.OPEN || iceCold
        val mayThrow = player.holdsBall() && openOrNerves && !player.hasAbility(PlayerAbility.`Nimble Beast`)
        val mayThrowTeammate = !player.holdsBall() && openOrNerves && player.canMakeThrowTeammateAction()
        val mayFoul = standing == StandingStatus.OPEN && hasAdjacentProneOpposition(player, board)
        buildSet {
            addAll(base)
            if (mayThrow) add(ActionType.THROW)
            if (mayThrowTeammate) add(ActionType.THROW_TEAMMATE)
            if (mayFoul) add(ActionType.FOUL)
        }
    }
}

/** True when [player] has at least one adjacent Prone opposition (Foul eligibility). */
fun hasAdjacentProneOpposition(player: Player, board: Board): Boolean {
    val square = board.findSquare(player.id)
    return neighbors(square, board).any { neighbor ->
        val occupantId = neighbor.playerId ?: return@any false
        val occupant = board.players.getValue(occupantId)
        occupant.team != player.team && occupant.status == PlayerStatus.PRONE
    }
}
