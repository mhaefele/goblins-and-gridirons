package com.mhaefele.gridiron.game.action.pending.mark

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.block.BlockLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.sidestep.SidestepLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.followUpMoves
import com.mhaefele.gridiron.game.legalmoves.freeBlockMoves
import com.mhaefele.gridiron.game.legalmoves.freeSidestepMoves
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.MarkContinuation
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Legal moves for [MarkContinuation].
 * Mirrors [MarkResolver] child delegation into Sidestep / Block.
 */
@Component
class MarkLegalMovesGenerator(
    @Lazy private val sidestepLegalMovesGenerator: SidestepLegalMovesGenerator,
    @Lazy private val blockLegalMovesGenerator: BlockLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<MarkContinuation>() {
    override fun generate(
        state: MatchState,
        pending: MarkContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingMark
        val board = state.board
        val turn = state.currentTurnState
        val cards = state.cards
        return when (ongoing.stage) {
            OngoingMark.MarkStage.HANDLE_SLIP_AWAY              -> {
                val coach = checkNotNull(ongoing.respondingCoach) { "HANDLE_SLIP_AWAY missing respondingCoach" }
                playIfPresent(cards, coach, turn, BonusPlay.`Slip Away`) + LegalAction.DeclineBonus
            }
            OngoingMark.MarkStage.CHOOSE_INTERRUPT_SIDESTEP      -> {
                val playerId = checkNotNull(ongoing.interruptSidestepPlayerId) { "CHOOSE_INTERRUPT_SIDESTEP missing player" }
                freeSidestepMoves(board.player(playerId), board) + LegalAction.Skip(playerId)
            }
            OngoingMark.MarkStage.DELEGATE_INTERRUPT_SIDESTEP    -> {
                val nested = checkNotNull(ongoing.nestedSidestep) { "DELEGATE_INTERRUPT_SIDESTEP missing nestedSidestep" }
                sidestepLegalMovesGenerator.generate(state, nested)
            }
            OngoingMark.MarkStage.HANDLE_ABILITY_FOLLOW_UP       -> {
                val ability = checkNotNull(ongoing.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP missing ability" }
                val playerId = ongoing.offeredAbilityPlayerId ?: ongoing.playerId
                ability.followUpMoves(board.player(playerId), board) + LegalAction.Skip(playerId)
            }
            OngoingMark.MarkStage.DELEGATE_ABILITY_FREE_ACTION   -> {
                val nested = checkNotNull(ongoing.nestedBlock) { "DELEGATE_ABILITY_FREE_ACTION missing nestedBlock" }
                blockLegalMovesGenerator.generate(state, nested)
            }
            OngoingMark.MarkStage.HANDLE_MARK_BONUS_CARDS        -> {
                val coach = checkNotNull(ongoing.respondingCoach) { "HANDLE_MARK_BONUS_CARDS missing respondingCoach" }
                playIfPresent(cards, coach, turn, BonusPlay.Momentum) + playIfPresent(cards, coach, turn, BonusPlay.`All-In Tackle`) + LegalAction.DeclineBonus
            }
            OngoingMark.MarkStage.CHOOSE_MARK_BONUS_FREE_BLOCK   -> { freeBlockMoves(board.player(ongoing.playerId), board) + LegalAction.Skip(ongoing.playerId) }
            OngoingMark.MarkStage.DELEGATE_MARK_BONUS_FREE_BLOCK -> {
                val nested = checkNotNull(ongoing.nestedBlock) { "DELEGATE_MARK_BONUS_FREE_BLOCK missing nestedBlock" }
                blockLegalMovesGenerator.generate(state, nested)
            }
            else                                                 -> error("MarkContinuation legal moves unexpected stage ${ongoing.stage}")
        }
    }
}
