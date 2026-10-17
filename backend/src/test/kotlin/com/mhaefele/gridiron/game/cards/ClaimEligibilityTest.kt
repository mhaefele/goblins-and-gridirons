package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClaimEligibilityTest {
    private val runner = PlayerId("h0")
    private val ball = BallId("b1")

    @Test
    fun `Grab It qualifies when possession is newly gained this turn`() {
        val facts = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.RUN,
            actingPlayerId = runner,
            ballsGained = setOf(ball),
        )
        assertThat(
            ClaimEligibility.meets(
                ChallengeClaimCondition.GRAB_IT,
                facts,
                TurnClaimStats(ballsPossessedAtTurnStart = emptySet()),
                GameScenario.bothTeamsDeployed(),
            ),
        ).isTrue()
        assertThat(
            ClaimEligibility.meets(
                ChallengeClaimCondition.GRAB_IT,
                facts,
                TurnClaimStats(ballsPossessedAtTurnStart = setOf(ball)),
                GameScenario.bothTeamsDeployed(),
            ),
        ).isFalse()
    }

    @Test
    fun `Keep Running qualifies on the third Run of the turn`() {
        val facts = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.RUN,
            actingPlayerId = runner,
        )
        val stats = TurnClaimStats(
            actionsThisTurn = listOf(
                TurnAction(PlayerId("h1"), ActionType.RUN),
                TurnAction(PlayerId("h2"), ActionType.RUN),
                TurnAction(runner, ActionType.RUN),
            ),
        )
        assertThat(
            ClaimEligibility.meets(ChallengeClaimCondition.KEEP_RUNNING, facts, stats, GameScenario.bothTeamsDeployed()),
        ).isTrue()
    }

    @Test
    fun `Connect rejects hand-offs`() {
        val successThrow = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.THROW,
            actingPlayerId = runner,
            throwSucceeded = true,
            throwWasHandOff = false,
        )
        val handOff = successThrow.copy(throwWasHandOff = true)
        val board = GameScenario.bothTeamsDeployed()

        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.CONNECT, successThrow, TurnClaimStats(), board)).isTrue()
        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.CONNECT, handOff, TurnClaimStats(), board)).isFalse()
    }

    @Test
    fun `Drop Them and Send Them Off need block outcomes`() {
        val board = GameScenario.bothTeamsDeployed()
        val knockdown = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.BLOCK,
            actingPlayerId = runner,
            knockDownOccurred = true,
        )
        val injury = knockdown.copy(injuryOccurred = true)

        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.DROP_THEM, knockdown, TurnClaimStats(), board)).isTrue()
        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.SEND_THEM_OFF, injury, TurnClaimStats(), board)).isTrue()
        assertThat(
            ClaimEligibility.meets(
                ChallengeClaimCondition.SEND_THEM_OFF,
                knockdown.copy(injuryOccurred = false),
                TurnClaimStats(),
                board,
            ),
        ).isFalse()
    }

    @Test
    fun `Huddle Up requires adjacency to two teammates after Run Mark or Sidestep`() {
        val clustered = GameScenario.board()
        val center = clustered.addPlayer(5, 8, profile = TestProfiles.lineman())
        clustered.addPlayer(6, 8, profile = TestProfiles.lineman())
        clustered.addPlayer(5, 9, profile = TestProfiles.lineman())
        val facts = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.MARK,
            actingPlayerId = center.player.id,
        )
        assertThat(
            ClaimEligibility.meets(ChallengeClaimCondition.HUDDLE_UP, facts, TurnClaimStats(), clustered.board),
        ).isTrue()

        val alone = GameScenario.board()
        val solo = alone.addPlayer(5, 8, profile = TestProfiles.lineman())
        alone.addPlayer(8, 8, profile = TestProfiles.lineman())
        assertThat(
            ClaimEligibility.meets(
                ChallengeClaimCondition.HUDDLE_UP,
                facts.copy(actingPlayerId = solo.player.id),
                TurnClaimStats(),
                alone.board,
            ),
        ).isFalse()
    }

    @Test
    fun `eligibleCards filters active slots by condition`() {
        val actives = listOf(
            ChallengeCardCatalog.card(1), // Grab It
            ChallengeCardCatalog.card(21), // Drop Them
            ChallengeCardCatalog.card(7), // Play to the Stands
        )
        val facts = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.BLOCK,
            actingPlayerId = runner,
            knockDownOccurred = true,
        )
        val eligible = ClaimEligibility.eligibleCards(
            actives,
            facts,
            TurnClaimStats(),
            GameScenario.bothTeamsDeployed(),
        )
        assertThat(eligible).containsExactly(actives[1])
    }

    @Test
    fun `Thread the Needle needs success with negative modifier`() {
        val board = GameScenario.bothTeamsDeployed()
        val risky = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.THROW,
            actingPlayerId = runner,
            throwSucceeded = true,
            throwModifier = -1,
        )
        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.THREAD_THE_NEEDLE, risky, TurnClaimStats(), board)).isTrue()
        assertThat(
            ClaimEligibility.meets(
                ChallengeClaimCondition.THREAD_THE_NEEDLE,
                risky.copy(throwModifier = 0),
                TurnClaimStats(),
                board,
            ),
        ).isFalse()
    }

    @Test
    fun `Star Turn ignores free actions and needs three distinct types`() {
        val board = GameScenario.bothTeamsDeployed()
        val facts = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.BLOCK,
            actingPlayerId = runner,
            isFreeAction = false,
        )
        val stats = TurnClaimStats(
            actionsThisTurn = listOf(
                TurnAction(runner, ActionType.RUN),
                TurnAction(runner, ActionType.MARK),
                TurnAction(runner, ActionType.BLOCK),
            ),
        )
        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.STAR_TURN, facts, stats, board)).isTrue()
        assertThat(
            ClaimEligibility.meets(ChallengeClaimCondition.STAR_TURN, facts.copy(isFreeAction = true), stats, board),
        ).isFalse()
    }

    @Test
    fun `Hold the Pit qualifies on any action ending on hazard when opposition holds the ball`() {
        val board = GameScenario.board()
        val away = board.addPlayer(5, 6, team = TeamSide.AWAY)
        board.holdingBall(away)
        val markOnHazard = ActionClaimFacts(
            team = TeamSide.HOME,
            actionType = ActionType.MARK,
            actingPlayerId = runner,
            endedOnHazard = true,
        )
        val runOnHazard = markOnHazard.copy(actionType = ActionType.RUN)
        // Old wrong: Run-only — Mark (and any Action) must qualify when on hazard + opp holds ball.
        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.HOLD_THE_PIT, markOnHazard, TurnClaimStats(), board.board)).isTrue()
        assertThat(ClaimEligibility.meets(ChallengeClaimCondition.HOLD_THE_PIT, runOnHazard, TurnClaimStats(), board.board)).isTrue()
        assertThat(
            ClaimEligibility.meets(
                ChallengeClaimCondition.HOLD_THE_PIT,
                markOnHazard.copy(endedOnHazard = false),
                TurnClaimStats(),
                board.board,
            ),
        ).isFalse()
    }
}
