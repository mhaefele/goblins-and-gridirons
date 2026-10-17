package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.api.dto.response.getActionType
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class LegalMovesTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Autowired
    private lateinit var regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator

    @Autowired
    private lateinit var matchGame: MatchGame


    @Test
    fun `open runner gets run and mark when mark paths exist`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(4, 6)
        board.addPlayer(6, 6, team = runner.team.opposite())

        val moves = regularPlayerActionLegalMovesGenerator.geometryMoves(runner.player, board.board)

        assertThat(moves.map { it.actionType }).contains(ActionType.RUN, ActionType.MARK)
        assertThat(moves.map { it.actionType }).doesNotContain(ActionType.BLOCK, ActionType.SIDESTEP)
    }

    @Test
    fun `marked player sidestep paths are one step and end open`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = marker.team.opposite())

        val sidesteps = regularPlayerActionLegalMovesGenerator.geometryMoves(marker.player, board.board)
            .filterIsInstance<LegalAction.PathMovement>()

        assertThat(sidesteps).isNotEmpty
        assertThat(sidesteps).allMatch { it.path.steps == 1 && it.path.squares.size == 2 }
        assertThat(sidesteps.map { it.path.squares.first() }).containsOnly(Coordinate(5, 6))
        assertThat(sidesteps.map { it.path.squares.last() }).contains(Coordinate(4, 6))
    }

    @Test
    fun `marked player gets block and sidestep but not run or mark`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = marker.team.opposite())

        val moves = regularPlayerActionLegalMovesGenerator.geometryMoves(marker.player, board.board)

        assertThat(moves.map { it.actionType }).contains(ActionType.BLOCK, ActionType.SIDESTEP)
        assertThat(moves.map { it.actionType }).doesNotContain(ActionType.RUN, ActionType.MARK)
    }

    @Test
    fun `marked juggernaut gets declare run plus block and sidestep`() {
        val pump = TeamCatalog.team("gremlin").roster.first { it.name == "Rattle Cart" }
        val board = GameScenario.board()
        val wagon = board.addPlayer(5, 6, profile = pump)
        board.addPlayer(6, 6, team = wagon.team.opposite())

        val moves = regularPlayerActionLegalMovesGenerator.geometryMoves(wagon.player, board.board)

        assertThat(moves.map { it.actionType }).contains(ActionType.BLOCK, ActionType.SIDESTEP, ActionType.RUN)
        assertThat(moves.map { it.actionType }).doesNotContain(ActionType.MARK)
        assertThat(moves.filterIsInstance<LegalAction.DeclareRun>())
            .containsExactly(LegalAction.DeclareRun(wagon.player.id, Coordinate(5, 6)))
    }

    @Test
    fun `marked ball carrier gets block but not throw`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(5, 6))
        board.addPlayer(6, 6, team = carrier.team.opposite())
        board.addPlayer(5, 5, team = carrier.team)

        val moves = regularPlayerActionLegalMovesGenerator.geometryMoves(carrier.player, board.board)

        assertThat(moves.map { it.actionType }).contains(ActionType.BLOCK, ActionType.SIDESTEP)
        assertThat(moves.map { it.actionType }).doesNotContain(ActionType.THROW, ActionType.RUN, ActionType.MARK)
    }

    @Test
    fun `open ball carrier gets throw`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(5, 6))
        board.addPlayer(5, 5, team = carrier.team)

        val moves = regularPlayerActionLegalMovesGenerator.geometryMoves(carrier.player, board.board)

        assertThat(moves.map { it.actionType }).contains(ActionType.THROW)
        assertThat(moves.filterIsInstance<LegalAction.Throw>().map { it.target.destination })
            .containsExactly(Coordinate(5, 5))
    }

    @Test
    fun `non-carrier has no throw`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        assertThat(regularPlayerActionLegalMovesGenerator.geometryMoves(runner.player, board.board))
            .noneMatch { it.actionType == ActionType.THROW }
    }

    @Test
    fun `prone player gets stand up only`() {
        val board = GameScenario.board()
        val prone = board.addPlayer(5, 6, status = PlayerStatus.PRONE)

        assertThat(regularPlayerActionLegalMovesGenerator.geometryMoves(prone.player, board.board))
            .containsExactly(LegalAction.StandUp(prone.player.id, Coordinate(5, 6)))
    }

    @Test
    fun `reserves player gets reserves moves only`() {
        val board = GameScenario.board()
        val reserve = board.addReservesPlayer()

        val moves = regularPlayerActionLegalMovesGenerator.geometryMoves(reserve.player, board.board)

        assertThat(moves).isNotEmpty
        assertThat(moves).allMatch { it.actionType == ActionType.RESERVES }
    }

    @Test
    fun `standing player does not get reserves action`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        assertThat(regularPlayerActionLegalMovesGenerator.geometryMoves(runner.player, board.board))
            .noneMatch { it.actionType == ActionType.RESERVES }
    }

    @Test
    fun `reserves player with no legal placements has no legal moves`() {
        val board = GameScenario.board()
        for (x in 1..11) {
            board.addPlayer(x, 1)
        }
        val reserve = board.addReservesPlayer()

        assertThat(regularPlayerActionLegalMovesGenerator.geometryMoves(reserve.player, board.board)).isEmpty()
    }

    @Test
    fun `reserves player lists legal end zone placements`() {
        val board = GameScenario.board()
        val reserve = board.addReservesPlayer()

        val placements = regularPlayerActionLegalMovesGenerator.geometryMoves(reserve.player, board.board)
            .filterIsInstance<LegalAction.Reserves>()
            .map { it.placement }

        assertThat(placements).isNotEmpty
        assertThat(placements).allMatch { coord ->
            board.board.squareAt(coord).isEndZone(reserve.team)
        }
    }

    @Test
    fun `reserves falls back to adjacent opposition squares when no clean end zone square remains`() {
        val board = GameScenario.board()
        // Contested HOME end zone: every vacant EZ square is adjacent to standing AWAY.
        for (x in 1..11) {
            val square = board.board.squareAt(Coordinate(x, 1))
            if (!square.isEndZone(TeamSide.HOME) || square.isBlocked()) continue
            board.addPlayer(x, 2, team = TeamSide.AWAY)
        }
        val reserve = board.addReservesPlayer()

        val placements = regularPlayerActionLegalMovesGenerator.geometryMoves(reserve.player, board.board)
            .filterIsInstance<LegalAction.Reserves>()
            .map { it.placement }

        assertThat(placements).isNotEmpty
        assertThat(placements).allMatch { coord ->
            board.board.squareAt(coord).isEndZone(TeamSide.HOME)
        }
    }

    @Test
    fun `reserves prefers clean end zone squares over adjacent opposition when both exist`() {
        val board = GameScenario.board()
        board.addPlayer(1, 2, team = TeamSide.AWAY) // marks HOME EZ (1,1) only
        val reserve = board.addReservesPlayer()

        val placements = regularPlayerActionLegalMovesGenerator.geometryMoves(reserve.player, board.board)
            .filterIsInstance<LegalAction.Reserves>()
            .map { it.placement }

        assertThat(placements).isNotEmpty
        assertThat(placements).doesNotContain(Coordinate(1, 1))
    }

    @Test
    fun `block only targets adjacent standing opposition being marked`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = marker.team.opposite())
        board.addPlayer(8, 6, team = marker.team.opposite())

        val destinations = regularPlayerActionLegalMovesGenerator.geometryMoves(marker.player, board.board)
            .filterIsInstance<LegalAction.Block>()
            .map { it.destination }

        assertThat(destinations).containsExactly(Coordinate(6, 6))
    }

    @Test
    fun `throw excludes prone teammates`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(5, 6))
        board.addPlayer(6, 6, status = PlayerStatus.PRONE, team = carrier.team)

        assertThat(regularPlayerActionLegalMovesGenerator.geometryMoves(carrier.player, board.board))
            .noneMatch { it.actionType == ActionType.THROW }
    }

    @Test
    fun `throw lists standing teammate coordinates`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(5, 6))
        board.addPlayer(6, 6, team = carrier.team)

        assertThat(
            regularPlayerActionLegalMovesGenerator.geometryMoves(carrier.player, board.board)
                .filterIsInstance<LegalAction.Throw>()
                .map { it.target.destination },
        ).containsExactly(Coordinate(6, 6))
    }

    @Test
    fun `adjacent throw target is hand off`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(5, 6))
        board.addPlayer(6, 6, team = carrier.team)

        val target = regularPlayerActionLegalMovesGenerator.geometryMoves(carrier.player, board.board)
            .filterIsInstance<LegalAction.Throw>()
            .single()
            .target

        assertThat(target.range).isEqualTo(ThrowRange.HAND_OFF)
        assertThat(target.obstructed).isFalse()
    }

    @Test
    fun `non-adjacent throw target is short or long range`() {
        val board = GameScenario.board()
        val carrier = board.holdingBall(board.addPlayer(5, 6))
        board.addPlayer(5, 4, team = carrier.team)

        val target = regularPlayerActionLegalMovesGenerator.geometryMoves(carrier.player, board.board)
            .filterIsInstance<LegalAction.Throw>()
            .single()
            .target

        assertThat(target.range).isEqualTo(ThrowRange.SHORT)
    }

    @Test
    fun `reserves placement rejects square adjacent to standing opposition`() {
        val board = GameScenario.board()
        board.addPlayer(2, 1, team = TeamSide.AWAY)
        val reserve = board.addReservesPlayer()

        val placements = regularPlayerActionLegalMovesGenerator.geometryMoves(reserve.player, board.board)
            .filterIsInstance<LegalAction.Reserves>()
            .map { it.placement }

        assertThat(placements).doesNotContain(Coordinate(1, 1), Coordinate(2, 2), Coordinate(3, 1))
    }

    @Test
    fun `player who already ran has no further run moves`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)
        val turn = GameScenario.context(runner).havingRun(runner).build()
        val state = matchState(board = board.board).copy(currentTurnState = turn)
        val pending = state.currentTurnState.pendingDecision as RegularPlayerAction

        assertThat(
            regularPlayerActionLegalMovesGenerator.generate(state, pending),
        ).noneMatch { it.actionType == ActionType.RUN && it is LegalAction.PlayerLegalAction && it.playerId == runner.id }
    }

    @Test
    fun `turn-ended player has no legal moves`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = marker.team.opposite())
        val turn = GameScenario.context(marker).turnEnded(marker).build()
        val state = matchState(board = board.board).copy(currentTurnState = turn)
        val pending = state.currentTurnState.pendingDecision as RegularPlayerAction

        assertThat(
            regularPlayerActionLegalMovesGenerator.generate(state, pending)
                .filterIsInstance<LegalAction.PlayerLegalAction>().filter { it.playerId == marker.id },
        ).isEmpty()
    }

    @Test
    fun `legal moves for team include only that teams players`() {
        val board = GameScenario.board()
        val home = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = TeamSide.AWAY)
        val state = matchState(board = board.board, activeCoach = TeamSide.HOME)

        val moves = legalMovesGenerator.forActingCoach(state)

        assertThat(moves).isNotEmpty
        assertThat(moves.filterIsInstance<LegalAction.PlayerLegalAction>().map { it.playerId }).containsOnly(home.player.id)
    }

    @Test
    fun `legal moves for acting coach with no pending throws`() {
        val board = GameScenario.board()
        board.addPlayer(5, 6)
        val state = matchState(board = board.board, actionsRemaining = 0, activeCoach = TeamSide.HOME)

        assertThatThrownBy { legalMovesGenerator.forActingCoach(state) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No pending decision")
    }

    @Test
    fun `viewer legal actions are empty for non-acting seat`() {
        val board = GameScenario.board()
        board.addPlayer(5, 6)
        val state = matchState(board = board.board, activeCoach = TeamSide.HOME)

        assertThat(matchGame.legalActionsForViewer(state, TeamSide.AWAY)).isEmpty()
        assertThat(matchGame.legalActionsForViewer(state, TeamSide.HOME)).isNotEmpty()
    }

    @Test
    fun `matchGame legalActions cache hits until submit advances lastEventId`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val created = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(created)

        val state = loadMatch(created)
        val first = matchGame.legalActions(state)
        assertThat(first).isNotEmpty()
        assertThat(matchGame.legalActions(state)).isSameAs(first)

        api.play(created, first.filterIsInstance<LegalAction.Run>().first())
        val after = loadMatch(created)
        assertThat(after.lastEventId).isGreaterThan(state.lastEventId)
        val afterMenu = matchGame.legalActions(after)
        assertThat(afterMenu).isNotSameAs(first)
        val picked = afterMenu.first()
        assertThat(matchGame.legalAction(after, picked.id)).isSameAs(picked)
    }

    @Test
    fun `legal moves for active coach include only active coach roster`() {
        val board = GameScenario.board()
        val home = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = TeamSide.AWAY)
        val state = matchState(board = board.board, activeCoach = TeamSide.HOME)

        val moves = legalMovesGenerator.forActingCoach(state)

        assertThat(moves).isNotEmpty
        assertThat(moves.filterIsInstance<LegalAction.PlayerLegalAction>().map { it.playerId }).containsOnly(home.player.id)
    }

    @Test
    fun `run paths respect fixed move from profile`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6, profile = TestProfiles.withMove(1))

        val paths = regularPlayerActionLegalMovesGenerator.geometryMoves(runner.player, board.board)
            .filterIsInstance<LegalAction.Run>()
            .map { it.path.steps }

        assertThat(paths).isNotEmpty
        assertThat(paths).allMatch { it <= 1 }
        assertThat(paths).contains(1)
    }

    @Test
    fun `random d6 move offers declare run instead of paths`() {
        val board = GameScenario.board()
        val profile = TestProfiles.lineman().copy(
            stats = TestProfiles.lineman().stats.copy(move = MoveValue.RandomD6),
        )
        val runner = board.addPlayer(5, 6, profile = profile)

        val moves = regularPlayerActionLegalMovesGenerator.geometryMoves(runner.player, board.board)

        assertThat(moves).containsExactly(LegalAction.DeclareRun(runner.player.id, Coordinate(5, 6)))
        assertThat(moves.filterIsInstance<LegalAction.Run>()).isEmpty()
    }

    @Test
    fun `getActionType parses leading token and ignores bonus suffix`() {
        assertThat(LegalActionId("RUN_h0_4x6-5x6").getActionType()).isEqualTo(ActionType.RUN)
        assertThat(LegalActionId("RUN_h0_4x6_BONUS_sprint1").getActionType()).isEqualTo(ActionType.RUN)
        assertThat(LegalActionId("PLAYGANGUP_card1").getActionType()).isEqualTo(ActionType.PLAY_GANG_UP)
    }

    @Test
    fun `getActionType rejects unknown token`() {
        assertThatThrownBy { LegalActionId("not_an_action").getActionType() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unknown action type")
    }

}
