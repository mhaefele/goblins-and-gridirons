package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.BoardScenario
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClaimFactsFromActionTest {

    @Test
    fun `Push Forward distance is 0 when Run only picks up on the last square`() {
        val scenario = GameScenario.board()
        val runner = scenario.holdingBall(scenario.addPlayer(5, 10, profile = TestProfiles.withMove(6)))
        val ballId = runner.player.heldBallId!!
        val events = listOf(
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 6), Coordinate(5, 7)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 7), Coordinate(5, 8)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 8), Coordinate(5, 9)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 9), Coordinate(5, 10)),
            DomainEvent.BallPickedUp(runner.player.id, Coordinate(5, 10)),
        )

        val facts = runFacts(scenario, runner, events)

        assertThat(facts.pushForwardDistance).isZero()
        assertThat(facts.ballsGained).containsExactly(ballId)
    }

    @Test
    fun `Push Forward distance counts squares on a Continue-run resume`() {
        val scenario = GameScenario.board()
        val runner = scenario.holdingBall(scenario.addPlayer(5, 8, profile = TestProfiles.withMove(6)))
        val ballId = runner.player.heldBallId!!
        val events = listOf(
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 3), Coordinate(5, 4)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 4), Coordinate(5, 5)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 5), Coordinate(5, 6)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 6), Coordinate(5, 7)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 7), Coordinate(5, 8)),
        )

        // Continue-run opens a new action bracket with the ball already held at the resume square.
        val facts = runFacts(
            scenario = scenario,
            runner = runner,
            events = events,
            ballsHeldAtActionStart = mapOf(TeamSide.HOME to mapOf(ballId to Coordinate(5, 3))),
        )

        assertThat(facts.pushForwardDistance).isEqualTo(5)
    }

    @Test
    fun `Push Forward distance counts squares after a mid-Run pickup`() {
        val scenario = GameScenario.board()
        val runner = scenario.holdingBall(scenario.addPlayer(5, 11, profile = TestProfiles.withMove(6)))
        val events = listOf(
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 6), Coordinate(5, 7)),
            DomainEvent.BallPickedUp(runner.player.id, Coordinate(5, 7)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 7), Coordinate(5, 8)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 8), Coordinate(5, 9)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 9), Coordinate(5, 10)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 10), Coordinate(5, 11)),
        )

        val facts = runFacts(scenario, runner, events)

        assertThat(facts.pushForwardDistance).isEqualTo(4)
    }

    @Test
    fun `Push Forward distance counts Pass on the Run after mid-Run pickup`() {
        val scenario = GameScenario.board()
        val runner = scenario.addPlayer(5, 9, profile = TestProfiles.withMove(6))
        val catcher = scenario.holdingBall(scenario.addPlayer(5, 12, profile = TestProfiles.lineman()))
        val events = listOf(
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 6), Coordinate(5, 7)),
            DomainEvent.BallPickedUp(runner.player.id, Coordinate(5, 7)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 7), Coordinate(5, 8)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 8), Coordinate(5, 9)),
            DomainEvent.BallThrown(from = Coordinate(5, 9), to = Coordinate(5, 12)),
        )

        val facts = runFacts(scenario, runner, events)

        assertThat(facts.pushForwardDistance).isEqualTo(5)
        assertThat(facts.ballsGained).containsExactly(catcher.player.heldBallId!!)
    }

    @Test
    fun `Push Forward distance uses ball coordinate snapshot when held at action start`() {
        val scenario = GameScenario.board()
        val runner = scenario.holdingBall(scenario.addPlayer(5, 10, profile = TestProfiles.withMove(6)))
        val ballId = runner.player.heldBallId!!
        val events = listOf(
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 6), Coordinate(5, 7)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 7), Coordinate(5, 8)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 8), Coordinate(5, 9)),
            DomainEvent.PlayerMoved(runner.player.id, Coordinate(5, 9), Coordinate(5, 10)),
        )

        val facts = runFacts(
            scenario = scenario,
            runner = runner,
            events = events,
            ballsHeldAtActionStart = mapOf(TeamSide.HOME to mapOf(ballId to Coordinate(5, 6))),
        )

        assertThat(facts.pushForwardDistance).isEqualTo(4)
    }

    @Test
    fun `Push Forward distance uses ball snapshot at throw release`() {
        val scenario = GameScenario.board()
        val thrower = scenario.addPlayer(5, 6, profile = TestProfiles.lineman())
        val catcher = scenario.holdingBall(scenario.addPlayer(5, 12, profile = TestProfiles.lineman()))
        val ballId = catcher.player.heldBallId!!
        val events = listOf(
            DomainEvent.BallThrown(from = Coordinate(5, 6), to = Coordinate(5, 12)),
        )

        val facts = ClaimFactsFromAction.build(
            completed = OpenPlayerAction(thrower.player.id, ActionType.THROW, OpenPlayerAction.Origin.BUDGETED),
            team = TeamSide.HOME,
            board = scenario.board,
            events = events,
            ballsHeldAtActionStart = mapOf(TeamSide.HOME to mapOf(ballId to Coordinate(5, 6))),
        )

        assertThat(facts.pushForwardDistance).isEqualTo(6)
    }

    @Test
    fun `Push Forward distance counts a successful throw teammate with the ball`() {
        val scenario = GameScenario.board()
        val thrower = scenario.addPlayer(5, 7)
        val carrier = scenario.holdingBall(scenario.addPlayer(5, 10))
        val ballId = carrier.player.heldBallId!!
        val events = listOf(
            DomainEvent.PlayerMoved(carrier.player.id, Coordinate(5, 6), Coordinate(5, 10)),
        )

        val facts = throwTeammateFacts(scenario, thrower, events, ballId, ballStartAt = Coordinate(5, 6))

        assertThat(facts.pushForwardDistance).isEqualTo(4)
    }

    @Test
    fun `Push Forward distance is below claim threshold when throw teammate lands short`() {
        val scenario = GameScenario.board()
        val thrower = scenario.addPlayer(5, 7)
        val carrier = scenario.holdingBall(scenario.addPlayer(5, 9))
        val ballId = carrier.player.heldBallId!!
        val events = listOf(
            DomainEvent.PlayerMoved(carrier.player.id, Coordinate(5, 6), Coordinate(5, 9)),
        )

        val facts = throwTeammateFacts(scenario, thrower, events, ballId, ballStartAt = Coordinate(5, 6))

        assertThat(facts.pushForwardDistance).isEqualTo(3)
        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.PUSH_FORWARD, facts, TurnClaimStats(), scenario.board))
            .isFalse()
    }

    @Test
    fun `Push Forward distance is null when throw teammate had no ball`() {
        val scenario = GameScenario.board()
        val thrower = scenario.addPlayer(5, 7)
        val thrown = scenario.addPlayer(5, 10)
        val events = listOf(
            DomainEvent.PlayerMoved(thrown.player.id, Coordinate(5, 6), Coordinate(5, 10)),
        )

        val facts = throwTeammateFacts(scenario, thrower, events, ballId = null)

        assertThat(facts.pushForwardDistance).isNull()
    }

    @Test
    fun `Push Forward distance counts throw teammate fail when bounce is caught by another teammate`() {
        val scenario = GameScenario.board()
        val thrower = scenario.addPlayer(5, 7)
        scenario.addPlayer(5, 10, status = PlayerStatus.PRONE)
        val receiver = scenario.holdingBall(scenario.addPlayer(5, 12))
        val ballId = receiver.player.heldBallId!!
        val carrierId = scenario.board.players.values.first { it.id != thrower.player.id && it.id != receiver.player.id }.id
        val events = listOf(
            DomainEvent.PlayerMoved(carrierId, Coordinate(5, 6), Coordinate(5, 10)),
            DomainEvent.BallDropped(carrierId, Coordinate(5, 10)),
            DomainEvent.BallBounced(Coordinate(5, 10), Coordinate(5, 12)),
            DomainEvent.BallPickedUp(receiver.player.id, Coordinate(5, 12)),
        )

        val facts = throwTeammateFacts(scenario, thrower, events, ballId, ballStartAt = Coordinate(5, 6))

        assertThat(facts.pushForwardDistance).isEqualTo(6)
    }

    @Test
    fun `Push Forward distance is null when throw teammate fail leaves the ball loose`() {
        val scenario = GameScenario.board()
        val thrower = scenario.addPlayer(5, 7)
        scenario.addPlayer(5, 10, status = PlayerStatus.PRONE)
        scenario.addLooseBall(Coordinate(5, 11))
        val carrierId = scenario.board.players.values.single { it.id != thrower.player.id }.id
        val ballId = scenario.board.balls.keys.single()
        val events = listOf(
            DomainEvent.PlayerMoved(carrierId, Coordinate(5, 6), Coordinate(5, 10)),
            DomainEvent.BallDropped(carrierId, Coordinate(5, 10)),
            DomainEvent.BallBounced(Coordinate(5, 10), Coordinate(5, 11)),
        )

        val facts = throwTeammateFacts(scenario, thrower, events, ballId, ballStartAt = Coordinate(5, 6))

        assertThat(facts.pushForwardDistance).isNull()
    }

    @Test
    fun `Push Forward distance counts throw teammate fumble when bounce is caught by another teammate`() {
        val scenario = GameScenario.board()
        val thrower = scenario.addPlayer(5, 7)
        val carrier = scenario.addReservesPlayer()
        val receiver = scenario.holdingBall(scenario.addPlayer(5, 11))
        val ballId = receiver.player.heldBallId!!
        val events = listOf(
            DomainEvent.BallThrowMissed(Coordinate(5, 6), Coordinate(5, 7)),
            DomainEvent.BallBounced(Coordinate(5, 7), Coordinate(5, 11)),
            DomainEvent.BallPickedUp(receiver.player.id, Coordinate(5, 11)),
            DomainEvent.PlayerInjured(carrier.player.id, Coordinate(5, 6)),
        )

        val facts = throwTeammateFacts(scenario, thrower, events, ballId, ballStartAt = Coordinate(5, 6))

        assertThat(facts.pushForwardDistance).isEqualTo(5)
    }

    private fun runFacts(
        scenario: BoardScenario,
        runner: PlacedPlayer,
        events: List<DomainEvent>,
        ballsHeldAtActionStart: Map<TeamSide, Map<BallId, Coordinate>> = emptyMap(),
    ): ActionClaimFacts =
        ClaimFactsFromAction.build(
            completed = OpenPlayerAction(runner.player.id, ActionType.RUN, OpenPlayerAction.Origin.BUDGETED),
            team = TeamSide.HOME,
            board = scenario.board,
            events = events,
            ballsHeldAtActionStart = ballsHeldAtActionStart,
        )

    private fun throwTeammateFacts(
        scenario: BoardScenario,
        thrower: PlacedPlayer,
        events: List<DomainEvent>,
        ballId: BallId?,
        ballStartAt: Coordinate? = null,
    ): ActionClaimFacts =
        ClaimFactsFromAction.build(
            completed = OpenPlayerAction(
                thrower.player.id,
                ActionType.THROW_TEAMMATE,
                OpenPlayerAction.Origin.BUDGETED
            ),
            team = TeamSide.HOME,
            board = scenario.board,
            events = events,
            ballsHeldAtActionStart = ballId?.let { id ->
                ballStartAt?.let { start -> mapOf(TeamSide.HOME to mapOf(id to start)) }
            }.orEmpty(),
        )
}
