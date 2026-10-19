package com.mhaefele.gridiron.game.action.pending.run

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.mark.MarkLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.throwball.ThrowLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.followUpMoves
import com.mhaefele.gridiron.game.legalmoves.freeMarkMoves
import com.mhaefele.gridiron.game.legalmoves.pathMoves
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.game.legalmoves.runPaths
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.RunContinuation
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Legal moves for [RunContinuation] — path, Cut Off nest, and ability follow-ups.
 * Mirrors [RunResolver] child delegation into Mark / Throw.
 */
@Component
class RunLegalMovesGenerator(
    @Lazy private val markLegalMovesGenerator: MarkLegalMovesGenerator,
    @Lazy private val throwLegalMovesGenerator: ThrowLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<RunContinuation>() {
    override fun generate(
        state: MatchState,
        pending: RunContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingRun
        val board = state.board
        val turn = state.currentTurnState
        val cards = state.cards
        return when (ongoing.stage) {
            OngoingRun.RunStage.AWAITING_PATH                -> {
                val player = board.player(ongoing.playerId)
                // Budget already includes Sprint if committed at HANDLE_SPRINT / fixed-Move attach.
                pathMoves(player.id, runPaths(player, board, ongoing.squaresRemaining), LegalAction::Run) + LegalAction.Skip(player.id)
            }
            OngoingRun.RunStage.HANDLE_SPRINT                -> playIfPresent(cards, turn.activeCoach, turn, BonusPlay.Sprint) + LegalAction.DeclineBonus
            OngoingRun.RunStage.HANDLE_CUT_OFF               -> {
                val coach = checkNotNull(ongoing.respondingCoach) { "HANDLE_CUT_OFF missing respondingCoach" }
                playIfPresent(cards, coach, turn, BonusPlay.`Cut Off`) + LegalAction.DeclineBonus
            }
            OngoingRun.RunStage.CHOOSE_INTERRUPT_MARK        -> {
                val playerId = checkNotNull(ongoing.interruptMarkPlayerId) { "CHOOSE_INTERRUPT_MARK missing player" }
                val runnerAt = board.findSquare(ongoing.playerId).coordinate
                freeMarkMoves(board.player(playerId), board, runnerAt) + LegalAction.Skip(playerId)
            }
            OngoingRun.RunStage.DELEGATE_INTERRUPT_MARK      -> {
                val nested = checkNotNull(ongoing.nestedMark) { "DELEGATE_INTERRUPT_MARK missing nestedMark" }
                markLegalMovesGenerator.generate(state, nested)
            }
            OngoingRun.RunStage.HANDLE_ABILITY_FOLLOW_UP     -> {
                val ability = checkNotNull(ongoing.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP missing ability" }
                val playerId = ongoing.offeredAbilityPlayerId ?: ongoing.playerId
                ability.followUpMoves(board.player(playerId), board) + LegalAction.Skip(playerId)
            }
            OngoingRun.RunStage.DELEGATE_ABILITY_FREE_ACTION -> {
                val nested = checkNotNull(ongoing.nestedThrow) { "DELEGATE_ABILITY_FREE_ACTION missing nestedThrow" }
                throwLegalMovesGenerator.generate(state, nested)
            }
            OngoingRun.RunStage.OFFER_CUT_OFF,
            OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS,
            OngoingRun.RunStage.DONE,
            -> error("RunContinuation legal moves unexpected stage ${ongoing.stage}")
        }
    }
}
