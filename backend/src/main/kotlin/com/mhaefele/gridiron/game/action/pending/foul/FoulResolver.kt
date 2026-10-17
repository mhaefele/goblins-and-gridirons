package com.mhaefele.gridiron.game.action.pending.foul

import com.mhaefele.gridiron.game.action.Bounce
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.ability.violentAutoInjureCheck
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.foulDiceRolled
import com.mhaefele.gridiron.game.action.utils.distance
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.dice.dto.FoulResult
import com.mhaefele.gridiron.game.dice.dto.foulResultFor
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.FoulContinuation
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/**
 * Foul Action (digest § Foul): Open player vs adjacent Prone opposition; one Block Die → Foul chart.
 * Violent: roll a D6 along with the foul die — on 6 the target is Injured and the foul die is not resolved.
 *
 * One-shot open action: [beginFoul] applies the chart then parks [FoulContinuation] at DONE
 * for TurnEngine COMPLETE (same shape as Block/Run at DONE).
 */
@Component
class FoulResolver(
    private val dice: DiceRoller,
    private val bounce: Bounce,
) : PendingDecisionResolver<FoulContinuation>() {
    fun beginFoul(session: MatchMutator, action: LegalAction.Foul): FoulContinuation {
        applyFoul(session, action)
        return FoulContinuation(OngoingFoul(action.playerId))
    }

    override fun mutate(session: MatchMutator, pending: FoulContinuation, command: LegalAction): FoulContinuation =
        error("FoulContinuation is finished at DONE — TurnEngine COMPLETE should clear it, not takeAction")

    private fun applyFoul(
        session: MatchMutator,
        action: LegalAction.Foul,
    ) {
        val board = session.board
        val fouler = board.getPlayer(action.playerId)
        require(fouler.status == PlayerStatus.STANDING) { "Foul requires a standing player, was ${fouler.status}" }
        require(standingStatus(fouler, board.board) == StandingStatus.OPEN) { "Foul requires an Open player, was ${standingStatus(fouler, board.board)}" }
        val target = board.getPlayerAt(action.destination)
        require(target.team != fouler.team) { "Cannot foul a team-mate" }
        require(target.status == PlayerStatus.PRONE) { "Foul target must be Prone, was ${target.status}" }
        val foulerAt = board.playerCoordinate(action.playerId)
        val targetAt = board.playerCoordinate(target.id)
        require(distance(board.board.squareAt(foulerAt), board.board.squareAt(targetAt), board.board) == 1) { "Foul target must be adjacent" }

        if (violentAutoInjureCheck(fouler)) {
            val violentRoll = dice.d6()
            board.record(
                DomainEvent.DiceRolled(
                    purpose = DiceRollPurpose.VIOLENT,
                    d6 = violentRoll,
                ),
            )
            if (violentRoll == 6) {
                board.playerInjured(target.id)
                return
            }
        }

        val face = dice.blockDice(1).single()
        val result = foulResultFor(face)
        board.record(foulDiceRolled(face, result))

        when (result) {
            FoulResult.SPOTTED   -> eject(board, action.playerId)
            FoulResult.TAKE_THAT -> {
                board.playerInjured(target.id)
                eject(board, action.playerId)
            }
            FoulResult.SLIPPED   -> session.endPlayerTurn(action.playerId)
            FoulResult.GOT_THEM  -> board.playerInjured(target.id)
        }
    }

    /** Spotted send-off — same dugout transition as injury. */
    private fun eject(board: BoardMutator, playerId: PlayerId) {
        dropBallThen(board, playerId) { board.playerInjured(playerId) }
    }

    private inline fun dropBallThen(
        board: BoardMutator,
        playerId: PlayerId,
        then: () -> Unit,
    ) {
        val at = board.playerCoordinate(playerId)
        if (board.getPlayer(playerId).holdsBall()) {
            board.ballDroppedByPlayer(playerId)
            bounce.bounce(board, at, cannotPickUp = playerId)
        }
        then()
    }
}
