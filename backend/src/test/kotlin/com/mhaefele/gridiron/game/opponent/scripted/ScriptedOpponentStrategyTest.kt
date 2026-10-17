package com.mhaefele.gridiron.game.opponent.scripted

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.distance
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ScriptedOpponentStrategyTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator


    private val strategy = ScriptedOpponentStrategy()

    @Test
    fun `on third action ignores opponents in reserves when blocking closest to ball`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2))
        scenario.addPlayer(Coordinate(6, 6), team = TeamSide.AWAY)
        scenario.addReservesPlayer(team = TeamSide.AWAY)
        scenario.addLooseBall(Coordinate(8, 6))
        val state = matchState(
            board = scenario.board,
            activeCoach = TeamSide.HOME,
            actionsRemaining = 1,
        )

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Block::class.java)
        assertThat((chosen as LegalAction.Block).destination).isEqualTo(Coordinate(6, 6))
    }

    @Test
    fun `on third action with no path onto ball blocks closest opponent to ball`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2))
        scenario.addPlayer(Coordinate(6, 6), team = TeamSide.AWAY)
        scenario.addPlayer(Coordinate(3, 6), team = TeamSide.AWAY)
        scenario.addLooseBall(Coordinate(8, 6))
        val state = matchState(
            board = scenario.board,
            activeCoach = TeamSide.HOME,
            actionsRemaining = 1,
        )

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Block::class.java)
        assertThat((chosen as LegalAction.Block).destination).isEqualTo(Coordinate(6, 6))
    }

    @Test
    fun `block on loose ball only applies on third action`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(3, 6), profile = TestProfiles.withMove(2))
        scenario.addLooseBall(Coordinate(8, 6))
        val state = matchState(
            board = scenario.board,
            activeCoach = TeamSide.HOME,
            actionsRemaining = 2,
        )

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state)) as LegalAction.PathMovement
        val ballSquare = scenario.board.squareAt(Coordinate(8, 6))
        val endpoint = scenario.board.squareAt(chosen.path.squares.last())

        assertThat(chosen).isNotInstanceOf(LegalAction.Block::class.java)
        assertThat(distance(endpoint, ballSquare, scenario.board)).isLessThan(
            distance(scenario.board.findSquare(chosen.playerId), ballSquare, scenario.board),
        )
    }

    @Test
    fun `prefers run that picks up loose ball`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6))
        scenario.addLooseBall(Coordinate(5, 7))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Run::class.java)
        assertThat((chosen as LegalAction.Run).path.squares.last()).isEqualTo(Coordinate(5, 7))
    }

    @Test
    fun `when ball is loose picks closest landing among run mark and sidestep`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(3, 6), profile = TestProfiles.withMove(1))
        scenario.addLooseBall(Coordinate(8, 6))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state)) as LegalAction.Run
        val ballSquare = scenario.board.squareAt(Coordinate(8, 6))
        val endpoint = scenario.board.squareAt(chosen.path.squares.last())

        assertThat(distance(endpoint, ballSquare, scenario.board)).isEqualTo(4)
    }

    @Test
    fun `prefers throwable pickup over bounce when both can reach loose ball`() {
        val scenario = GameScenario.board()
        val lineman = scenario.addPlayer(Coordinate(4, 6), profile = TestProfiles.wraith())
        val runner = scenario.addPlayer(Coordinate(4, 5))
        scenario.addLooseBall(Coordinate(5, 6))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state)) as LegalAction.Run

        assertThat(chosen.path.squares.last()).isEqualTo(Coordinate(5, 6))
        assertThat(chosen.playerId).isEqualTo(runner.player.id)
        assertThat(chosen.playerId).isNotEqualTo(lineman.player.id)
    }

    @Test
    fun `prefers bounce onto ball when no throwable player can pick up`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(4, 6), profile = TestProfiles.wraith())
        scenario.addLooseBall(Coordinate(5, 6))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state)) as LegalAction.PathMovement

        assertThat(chosen.path.squares.last()).isEqualTo(Coordinate(5, 6))
    }

    @Test
    fun `when run onto ball is blocked prefers bounce onto ball`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(3, 6))
        scenario.addPlayer(Coordinate(6, 6), team = TeamSide.AWAY)
        scenario.addLooseBall(Coordinate(5, 6))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state)) as LegalAction.Mark

        assertThat(chosen.path.squares.last()).isEqualTo(Coordinate(5, 6))
    }

    @Test
    fun `fallback prefers stand up over run`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), status = PlayerStatus.PRONE)
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val legal = legalMovesGenerator.forActingCoach(state)

        assertThat(strategy.choose(state, legal)).isInstanceOf(LegalAction.StandUp::class.java)
    }

    @Test
    fun `stands up closest teammate to loose ball when that player is prone`() {
        val scenario = GameScenario.board()
        val prone = scenario.addPlayer(Coordinate(6, 6), status = PlayerStatus.PRONE)
        scenario.addPlayer(Coordinate(3, 6), profile = TestProfiles.withMove(2))
        scenario.addLooseBall(Coordinate(8, 6))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.StandUp::class.java)
        assertThat((chosen as LegalAction.StandUp).playerId).isEqualTo(prone.player.id)
    }

    @Test
    fun `does not stand up farther prone when a standing teammate is closer to the ball`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(3, 6), status = PlayerStatus.PRONE)
        scenario.addPlayer(Coordinate(6, 6), profile = TestProfiles.withMove(2))
        scenario.addLooseBall(Coordinate(8, 6))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.PathMovement::class.java)
        assertThat(chosen).isNotInstanceOf(LegalAction.StandUp::class.java)
    }

    @Test
    fun `stands up closest teammate to opponent carrier when that player is prone`() {
        val scenario = GameScenario.board()
        val prone = scenario.addPlayer(Coordinate(5, 7), status = PlayerStatus.PRONE)
        scenario.addPlayer(Coordinate(2, 6), profile = TestProfiles.withMove(2))
        scenario.holdingBall(scenario.addPlayer(Coordinate(5, 6), team = TeamSide.AWAY))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.StandUp::class.java)
        assertThat((chosen as LegalAction.StandUp).playerId).isEqualTo(prone.player.id)
    }

    @Test
    fun `carrier throws to open teammate closer to end zone than best run`() {
        val scenario = GameScenario.board()
        scenario.holdingBall(scenario.addPlayer(Coordinate(5, 4)))
        scenario.addPlayer(Coordinate(5, 9))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Throw::class.java)
        assertThat((chosen as LegalAction.Throw).target.destination).isEqualTo(Coordinate(5, 9))
    }

    @Test
    fun `carrier runs when run reaches further than open teammate`() {
        val scenario = GameScenario.board()
        val carrier = scenario.holdingBall(scenario.addPlayer(Coordinate(5, 6)))
        scenario.addPlayer(Coordinate(5, 7))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Run::class.java)
        assertThat((chosen as LegalAction.Run).playerId).isEqualTo(carrier.player.id)
    }

    @Test
    fun `carrier does not throw backward when forward run is available`() {
        val scenario = GameScenario.board()
        val carrier = scenario.holdingBall(scenario.addPlayer(Coordinate(5, 8)))
        scenario.addPlayer(Coordinate(5, 6))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.choose(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Run::class.java)
        assertThat(chosen).isNotInstanceOf(LegalAction.Throw::class.java)
        assertThat((chosen as LegalAction.Run).path.squares.last().y).isGreaterThan(8)
    }

    @Test
    fun `when ball carrier has no moves teammate can still act`() {
        val scenario = GameScenario.board()
        val carrier = scenario.holdingBall(scenario.addPlayer(Coordinate(5, 6)))
        val teammate = scenario.addPlayer(Coordinate(4, 6))
        val turn = GameScenario.context(carrier).havingRun(carrier).build()
        val state = matchState(board = scenario.board).copy(currentTurnState = turn)
        val legal = legalMovesGenerator.forActingCoach(state)

        val chosen = strategy.choose(state, legal) as LegalAction.PlayerLegalAction

        assertThat(chosen.playerId).isEqualTo(teammate.player.id)
        assertThat(chosen.playerId).isNotEqualTo(carrier.player.id)
    }
}
