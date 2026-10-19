package com.mhaefele.gridiron.game.action.pending.block

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.armourcheck.ArmourCheckLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.knockdown.KnockDownLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.run.RunLegalMovesGenerator
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.game.dice.dto.distinctFaceOrders
import com.mhaefele.gridiron.game.legalmoves.followUpMoves
import com.mhaefele.gridiron.game.legalmoves.freeShortThrowMovesForQuickRelease
import com.mhaefele.gridiron.game.legalmoves.markPaths
import com.mhaefele.gridiron.game.legalmoves.playIfPresent
import com.mhaefele.gridiron.game.legalmoves.hasDoOver
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.shared.PlayerId
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Legal moves for [BlockContinuation].
 * Mirrors [BlockResolver] child delegation into KnockDown / ArmourCheck / Run / nested Block.
 */
@Component
class BlockLegalMovesGenerator(
    @Lazy private val knockDownLegalMovesGenerator: KnockDownLegalMovesGenerator,
    @Lazy private val armourCheckLegalMovesGenerator: ArmourCheckLegalMovesGenerator,
    @Lazy private val runLegalMovesGenerator: RunLegalMovesGenerator,
    @Lazy private val blockLegalMovesGenerator: BlockLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<BlockContinuation>() {
    override fun generate(
        state: MatchState,
        pending: BlockContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingBlock
        val board = state.board
        val turn = state.currentTurnState
        val cards = state.cards
        return when (ongoing.stage) {
            OngoingBlock.BlockStage.HANDLE_QUICK_RELEASE -> {
                val target = board.player(ongoing.targetId)
                freeShortThrowMovesForQuickRelease(target, board) + LegalAction.ContinueBlock(ongoing.blockerId, ongoing.destination)
            }
            OngoingBlock.BlockStage.HANDLE_BRACE,
            OngoingBlock.BlockStage.HANDLE_UNDERDOG,
                                                                    -> midActionBonus(ongoing, turn, cards) + LegalAction.DeclineBonus
            OngoingBlock.BlockStage.HANDLE_POST_POOL      -> heldBlockDice(ongoing, board, turn, cards)
            OngoingBlock.BlockStage.HANDLE_ENFORCER_ORDER -> enforcerBlockOrderMoves(ongoing.blockerId, ongoing)
            OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET,
            OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_BLOCKER -> {
                val nested = checkNotNull(ongoing.nestedKnockDown) { "knock-down delegate missing nestedKnockDown" }
                knockDownLegalMovesGenerator.generate(state, nested)
            }
            OngoingBlock.BlockStage.DELEGATE_ARMOUR_CHECK       -> {
                val nested = checkNotNull(ongoing.nestedArmourCheck) { "DELEGATE_ARMOUR_CHECK missing nestedArmourCheck" }
                armourCheckLegalMovesGenerator.generate(state, nested)
            }
            OngoingBlock.BlockStage.HANDLE_SHOVE_FOLLOWUP       -> {
                val player = board.player(ongoing.blockerId)
                val vacated = checkNotNull(ongoing.shoveVacatedSquare) { "HANDLE_SHOVE_FOLLOWUP missing shoveVacatedSquare" }
                val marks = markPaths(player, board)
                    .filter { it.squares.last() == vacated }
                    .map { LegalAction.Mark(player.id, it) }
                marks + LegalAction.Skip(player.id)
            }
            OngoingBlock.BlockStage.HANDLE_ABILITY_FOLLOW_UP    -> {
                val ability = checkNotNull(ongoing.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP missing ability" }
                val playerId = ongoing.offeredAbilityPlayerId ?: ongoing.blockerId
                ability.followUpMoves(board.player(playerId), board) + LegalAction.Skip(playerId)
            }
            OngoingBlock.BlockStage.DELEGATE_FREE_ACTION        -> {
                when {
                    ongoing.nestedRun != null   -> runLegalMovesGenerator.generate(state, ongoing.nestedRun)
                    ongoing.nestedBlock != null -> blockLegalMovesGenerator.generate(state, ongoing.nestedBlock)
                    else                        -> error("DELEGATE_FREE_ACTION missing nested child")
                }
            }
            else                                                -> error("Shouldn't be possible. Ongoing block: $ongoing")
        }
    }

    private fun midActionBonus(
        ongoing: OngoingBlock,
        turn: TurnState,
        cards: CardState,
    ): List<LegalAction> =
        when (ongoing.stage) {
            OngoingBlock.BlockStage.HANDLE_BRACE    -> playIfPresent(cards, ongoing.targetTeam, turn, BonusPlay.Brace)
            OngoingBlock.BlockStage.HANDLE_UNDERDOG -> playIfPresent(cards, turn.activeCoach, turn, BonusPlay.Underdog)
            else                                    -> error("BlockContinuation mid-action bonus stage unexpected: ${ongoing.stage}")
        }

    private fun heldBlockDice(
        ongoing: OngoingBlock,
        board: Board,
        turn: TurnState,
        cards: CardState,
    ): List<LegalAction> {
        val blocker = board.player(ongoing.blockerId)
        val hasOs = blocker.hasAbility(PlayerAbility.`Second Swing`) && !ongoing.secondSwingUsed
        val faces = ongoing.faces!!
        val assisted = ongoing.assisted
        val osMoves = if (hasOs) {
            listOf(
                LegalAction.ChooseBlockReroll(ongoing.blockerId, ongoing.destination, faces, assisted, false),
                LegalAction.ChooseBlockReroll(ongoing.blockerId, ongoing.destination, faces, assisted, true),
            )
        } else {
            emptyList()
        }
        val rt = if (!ongoing.doOverBlockDeclined && DoOverRoll.mayImproveBlock(board, ongoing.blockerId, faces) && hasDoOver(cards, turn.activeCoach, turn)) {
            listOf(LegalAction.AdjustBlockByDoOver(ongoing.blockerId, ongoing.destination, faces, assisted))
        } else {
            emptyList()
        }
        return when {
            rt.isNotEmpty() && osMoves.isNotEmpty() -> rt + osMoves + LegalAction.DeclineBonus
            rt.isNotEmpty()                         -> rt + LegalAction.DeclineBonus
            else                                    -> osMoves
        }
    }

    private fun enforcerBlockOrderMoves(
        playerId: PlayerId,
        ongoing: OngoingBlock,
    ): List<LegalAction.ChooseBlockFaceOrder> =
        distinctFaceOrders(ongoing.faces!!).map { order ->
            LegalAction.ChooseBlockFaceOrder(playerId, ongoing.destination, order)
        }
}
