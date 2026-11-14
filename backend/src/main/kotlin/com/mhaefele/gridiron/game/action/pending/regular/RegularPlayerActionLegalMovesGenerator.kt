package com.mhaefele.gridiron.game.action.pending.regular

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.block.BlockLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.foul.FoulLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.mark.MarkLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.reserves.ReservesLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.run.RunLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.sidestep.SidestepLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.standup.StandUpLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.throwball.ThrowLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.throwteammate.ThrowTeammateLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.ActionAttachedBonuses
import com.mhaefele.gridiron.game.legalmoves.PathBonusFlags
import com.mhaefele.gridiron.game.legalmoves.allowedActionTypes
import com.mhaefele.gridiron.game.legalmoves.blockMoves
import com.mhaefele.gridiron.game.legalmoves.foulMoves
import com.mhaefele.gridiron.game.legalmoves.markMoves
import com.mhaefele.gridiron.game.legalmoves.reservesMoves
import com.mhaefele.gridiron.game.legalmoves.runMoves
import com.mhaefele.gridiron.game.legalmoves.sidestepMoves
import com.mhaefele.gridiron.game.legalmoves.standUpMoves
import com.mhaefele.gridiron.game.legalmoves.throwMoves
import com.mhaefele.gridiron.game.legalmoves.throwTeammateSelectMoves
import com.mhaefele.gridiron.game.legalmoves.toPathFlags
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.pending.ActionContinuation
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.pending.FoulContinuation
import com.mhaefele.gridiron.model.pending.MarkContinuation
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.pending.ReservesContinuation
import com.mhaefele.gridiron.model.pending.RunContinuation
import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.model.pending.KnockDownContinuation
import com.mhaefele.gridiron.model.pending.BallGuardContinuation
import com.mhaefele.gridiron.model.pending.SidestepContinuation
import com.mhaefele.gridiron.model.pending.StandUpContinuation
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import com.mhaefele.gridiron.model.pending.ThrowTeammateContinuation
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Legal moves for [RegularPlayerAction].
 * Bare RPA enumerates allowed action types then geometry; with a delegate, follows the matching
 */
@Component
class RegularPlayerActionLegalMovesGenerator(
    private val blockLegalMovesGenerator: BlockLegalMovesGenerator,
    private val foulLegalMovesGenerator: FoulLegalMovesGenerator,
    private val runLegalMovesGenerator: RunLegalMovesGenerator,
    private val markLegalMovesGenerator: MarkLegalMovesGenerator,
    private val sidestepLegalMovesGenerator: SidestepLegalMovesGenerator,
    private val throwLegalMovesGenerator: ThrowLegalMovesGenerator,
    private val throwTeammateLegalMovesGenerator: ThrowTeammateLegalMovesGenerator,
    private val standUpLegalMovesGenerator: StandUpLegalMovesGenerator,
    private val reservesLegalMovesGenerator: ReservesLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<RegularPlayerAction>() {
    override fun generate(state: MatchState, pending: RegularPlayerAction): List<LegalAction> {
        val child = pending.delegate
        if (child != null) return legalMovesForDelegate(state, child)
        return teamMoves(state.currentTurnState.activeCoach, state.board, state.currentTurnState, state.cards)
    }

    /**
     * Board geometry for one player — no turn budget / hand.
     * Lookahead deepen and geometry unit tests.
     */
    fun geometryMoves(
        player: Player,
        board: Board,
        bonuses: PathBonusFlags = PathBonusFlags(),
        hailMary: Boolean = false,
        iceCold: Boolean = false,
    ): List<LegalAction> {
        val types = allowedActionTypes(player, board, iceCold)
        return types.flatMap { type -> geometryFor(type, player, board, bonuses, hailMary) }
    }

    private fun geometryFor(
        actionType: ActionType,
        player: Player,
        board: Board,
        bonuses: PathBonusFlags,
        hailMary: Boolean,
    ): List<LegalAction> =
        when (actionType) {
            ActionType.RUN            -> runMoves(player, board, bonuses)
            ActionType.MARK           -> markMoves(player, board, bonuses)
            ActionType.SIDESTEP       -> sidestepMoves(player, board)
            ActionType.BLOCK          -> blockMoves(player, board, bonuses)
            ActionType.FOUL           -> foulMoves(player, board)
            ActionType.THROW          -> throwMoves(player, board, hailMary)
            ActionType.THROW_TEAMMATE -> throwTeammateSelectMoves(player, board)
            ActionType.STAND_UP       -> standUpMoves(player, board)
            ActionType.RESERVES       -> reservesMoves(player, board)
            else                      -> emptyList()
        }

    private fun teamMoves(
        team: TeamSide,
        board: Board,
        turn: TurnState,
        cards: CardState
    ): List<LegalAction> =
        board.players.values
            .filter { it.team == team }
            .flatMap { player -> playerMoves(player, board, turn, cards, team) }

    private fun playerMoves(
        player: Player,
        board: Board,
        turn: TurnState,
        cards: CardState,
        coach: TeamSide,
    ): List<LegalAction> {
        if (player.id in turn.turnEndedPlayers) return emptyList()
        val usedActions = turn.actionsUsedThisTurn[player.id].orEmpty()
        fun generate(flags: PathBonusFlags, hailMary: Boolean = false, iceCold: Boolean = false): List<LegalAction> =
            geometryMoves(player, board, flags, hailMary, iceCold)
                .filter { it.actionType !in usedActions }

        val base = generate(turn.bonusModifiers.toPathFlags())
        val withRequired = ActionAttachedBonuses.expandFromHand(
            base = base,
            generate = ::generate,
            turnBonuses = turn.bonusModifiers,
            cards = cards,
            coach = coach,
            used = turn.bonusPlaysUsedThisTurn,
        )
        return ActionAttachedBonuses.annotateAvailableBonuses(
            actions = withRequired,
            board = board,
            cards = cards,
            coach = coach,
            used = turn.bonusPlaysUsedThisTurn,
            turnBonuses = turn.bonusModifiers,
        )
    }

    private fun legalMovesForDelegate(state: MatchState, child: ActionContinuation): List<LegalAction> =
        when (child) {
            is ThrowContinuation         -> throwLegalMovesGenerator.generate(state, child)
            is ThrowTeammateContinuation -> throwTeammateLegalMovesGenerator.generate(state, child)
            is BlockContinuation         -> blockLegalMovesGenerator.generate(state, child)
            is RunContinuation           -> runLegalMovesGenerator.generate(state, child)
            is MarkContinuation          -> markLegalMovesGenerator.generate(state, child)
            is SidestepContinuation      -> sidestepLegalMovesGenerator.generate(state, child)
            is StandUpContinuation       -> standUpLegalMovesGenerator.generate(state, child)
            is ReservesContinuation      -> reservesLegalMovesGenerator.generate(state, child)
            is FoulContinuation          -> foulLegalMovesGenerator.generate(state, child)
            is BallGuardContinuation     -> error("BallGuardContinuation nests under KnockDown, not RegularPlayerAction.delegate")
            is KnockDownContinuation     -> error("KnockDownContinuation nests under Block / ThrowTeammate, not RegularPlayerAction.delegate")
            is ArmourCheckContinuation   -> error("ArmourCheckContinuation nests under KnockDown / Block / ThrowTeammate, not RegularPlayerAction.delegate")
        }
}
