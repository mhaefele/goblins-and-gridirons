package com.mhaefele.gridiron.game.action.pending.throwball

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.run.RunLegalMovesGenerator
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.game.legalmoves.followUpMoves
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.game.legalmoves.hasDoOver
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Legal moves for [ThrowContinuation].
 * Mirrors [ThrowResolver] child delegation into Run.
 */
@Component
class ThrowLegalMovesGenerator(
    @Lazy private val runLegalMovesGenerator: RunLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<ThrowContinuation>() {
    override fun generate(
        state: MatchState,
        pending: ThrowContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingThrow
        val board = state.board
        val turn = state.currentTurnState
        val cards = state.cards
        return when (ongoing.stage) {
            OngoingThrow.ThrowStage.INITIALIZE,
            OngoingThrow.ThrowStage.OFFER_BONUS_CARDS,
            OngoingThrow.ThrowStage.INITIAL_DICE_ROLL,
            OngoingThrow.ThrowStage.QUEUE_POST_ROLL_ABILITIES,
            OngoingThrow.ThrowStage.APPLY_FINAL_RESULT,
            OngoingThrow.ThrowStage.OFFER_ABILITY_FOLLOW_UPS,
            OngoingThrow.ThrowStage.DONE,
                 -> error("Shouldn't be possible.  Ongoing throw: $ongoing")
            OngoingThrow.ThrowStage.HANDLE_BONUS_CARD_OFFER      -> playIfPresent(cards, turn.activeCoach, turn, BonusPlay.`Dead Eye`) + playIfPresent(cards, turn.activeCoach, turn, BonusPlay.`Hail Mary Pass`) + LegalAction.DeclineBonus
            OngoingThrow.ThrowStage.APPLY_POST_ROLL_ABILITIES    -> heldThrowCheck(ongoing, board, turn, cards)
            OngoingThrow.ThrowStage.HANDLE_ABILITY_FOLLOW_UP     -> {
                val ability = checkNotNull(ongoing.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP missing ability" }
                val playerId = ongoing.offeredAbilityPlayerId ?: ongoing.throwerId
                ability.followUpMoves(board.player(playerId), board) + LegalAction.Skip(playerId)
            }
            OngoingThrow.ThrowStage.DELEGATE_ABILITY_FREE_ACTION -> {
                val nested = checkNotNull(ongoing.nestedRun) { "DELEGATE_ABILITY_FREE_ACTION missing nestedRun" }
                runLegalMovesGenerator.generate(state, nested)
            }
        }
    }

    private fun heldThrowCheck(
        ongoingThrow: OngoingThrow,
        board: Board,
        turn: TurnState,
        cards: CardState,
    ): List<LegalAction> {
        val target = ongoingThrow.target
        val thrower = board.player(ongoingThrow.throwerId)
        val mayImprove = DoOverRoll.mayImproveThrow(
            board, ongoingThrow.throwerId, ongoingThrow.naturalRoll!!, target.range, target.obstructed, ongoingThrow.hailMary, ongoingThrow.deadEye,
        )
        val needsEp = thrower.hasAbility(PlayerAbility.`Pinpoint Passer`) && mayImprove
        val epMoves = if (needsEp) {
            listOf(
                LegalAction.ChooseThrowReroll(ongoingThrow.throwerId, target.destination, ongoingThrow.naturalRoll, target.range, target.obstructed, false),
                LegalAction.ChooseThrowReroll(ongoingThrow.throwerId, target.destination, ongoingThrow.naturalRoll, target.range, target.obstructed, true),
            )
        } else {
            emptyList()
        }
        val rtMoves = if (mayImprove && !ongoingThrow.doOverThrowDeclined && hasDoOver(cards, turn.activeCoach, turn)) {
            listOf(
                LegalAction.AdjustThrowByDoOver(
                    ongoingThrow.throwerId, target.destination, ongoingThrow.naturalRoll, target.range, target.obstructed,
                ),
            )
        } else {
            emptyList()
        }
        return when {
            rtMoves.isNotEmpty() && epMoves.isNotEmpty() -> rtMoves + epMoves + LegalAction.DeclineBonus
            rtMoves.isNotEmpty()                         -> rtMoves + LegalAction.DeclineBonus
            else                                         -> epMoves
        }
    }
}
