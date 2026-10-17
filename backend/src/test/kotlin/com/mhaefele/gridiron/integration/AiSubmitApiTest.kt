package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AiSubmitApiTest : BaseApiTest() {

    @Test
    fun `same-seat continue returns next step envelope`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(emptyTableau())
            actionsRemaining(3)
        }

        val before = api.aiStep(match)
        val run = claimFreeRun(match)
        val result = api.aiSubmit(match, run.id)

        assertThat(result.chosenLegalActionId).isEqualTo(run.id)
        assertThat(result.afterSubmitRewards).isNull()
        assertThat(result.next.harness.rewards.scoreUs).isEqualTo(before.policy.obs.scoreUs)
        assertThat(result.scoring.touchdownsUs).isEqualTo(0)
        assertThat(result.scoring.challengeCardsUs).isEqualTo(0)
        assertThat(result.next.harness.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(result.next.policy.obs.actionsRemaining).isEqualTo(before.policy.obs.actionsRemaining - 1)
        assertThat(result.next.harness.legalRows).isNotEmpty()
    }

    @Test
    fun `submit that ends the match has terminal next step`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(emptyTableau())
            actionsRemaining(1)
            turnNumber(5)
            scores(6, 2)
            pointsVictoryTurnsRemaining(1)
        }

        val result = api.aiSubmit(match, claimFreeRun(match).id)

        assertThat(result.afterSubmitRewards).isNull()
        assertThat(result.next.harness.isGameEnded()).isTrue()
        assertThat(result.next.harness.resultKind).isEqualTo(MatchResultKind.pointsVictory)
        assertThat(result.next.harness.legalRows).isEmpty()
    }

    @Test
    fun `submit that ends match on next coach turn start has terminal next step`() {
        // AWAY's last budgeted action → HOME TURN_START sudden death (HOME lead ≥ 10).
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(emptyTableau())
            actionsRemaining(1)
            turnNumber(5)
            activeCoach(TeamSide.AWAY)
            scores(10, 0)
        }

        val result = api.aiSubmit(match, claimFreeRun(match).id, forSeat = TeamSide.AWAY)

        assertThat(result.afterSubmitRewards).isNull()
        assertThat(result.next.harness.isGameEnded()).isTrue()
        assertThat(result.next.harness.resultKind).isEqualTo(MatchResultKind.suddenDeath)
        assertThat(result.next.harness.legalRows).isEmpty()
    }

    @Test
    fun `same-seat submit next obs matches GET step packed obs`() {
        dice.queueD6(List(24) { 6 })
        val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.TD_RUN))
        val before = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        val passing = before.harness.legalRows.first { it.passingLegal }
        val submitPacked = api.aiSubmitPacked(response.matchId, passing.legalActionId, forSeat = TeamSide.HOME)
        assertThat(submitPacked.next.policy.obs.values).isNotEmpty()
        val stepPacked = api.aiStepPacked(response.matchId, forSeat = TeamSide.HOME)
        assertThat(submitPacked.next.policy.obs.values).isEqualTo(stepPacked.policy.obs.values)
    }

    @Test
    fun `forSeat waits through a computer turn`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val created = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.HUMAN,
                awayControlledBy = CoachControl.COMPUTER,
                computerOpponent = ComputerOpponentKind.HEURISTIC,
            ),
        )
        dice.usePassThrough()
        placeWhenHuman(created.matchId)
        waitUntilSeat(created.matchId, TeamSide.HOME)
        playUntilLastBudgetedAction(created.matchId)

        val result = api.aiSubmit(created, claimFreeRun(created.matchId).id, forSeat = TeamSide.HOME)

        assertThat(result.afterSubmitRewards).isNotNull
        assertThat(result.afterSubmitRewards!!.scoreUs).isNotNull
        assertThat(result.next).isNotNull
        val next = result.next.harness
        assertThat(next.perspectiveSeat).isEqualTo(TeamSide.HOME)
        if (!next.isGameEnded()) {
            assertThat(next.actingSeat).isEqualTo(TeamSide.HOME)
            assertThat(result.next.harness.legalRows).isNotEmpty()
        } else {
            assertThat(result.next.harness.legalRows).isEmpty()
        }
    }

    @Test
    fun `forSeat waits through the other human seat`() {
        val match = match {
            bothTeamsDeployed()
            addLooseBall(6, 8)
            cards(emptyTableau())
            actionsRemaining(1)
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val awayTurn = executor.submit {
                waitUntilSeat(match.matchId, TeamSide.AWAY)
                while (loadMatch(match).actingSeat() == TeamSide.AWAY && !loadMatch(match).isGameEnded()) {
                    val legal = api.legalActions(match.matchId)
                    if (legal.isEmpty()) {
                        Thread.sleep(20)
                        continue
                    }
                    val run = legal.filterIsInstance<LegalAction.Run>().firstOrNull { it.potentialChallengeClaims.isEmpty() }
                    api.play(match.matchId, run ?: legal.first())
                }
            }
            val result = api.aiSubmit(match, claimFreeRun(match).id, forSeat = TeamSide.HOME)
            awayTurn.get(15, TimeUnit.SECONDS)

            assertThat(result.afterSubmitRewards).isNotNull
            assertThat(result.afterSubmitRewards!!.scoreUs).isNotNull
            assertThat(result.next.harness.actingSeat).isEqualTo(TeamSide.HOME)
            assertThat(result.next.harness.perspectiveSeat).isEqualTo(TeamSide.HOME)
            assertThat(result.next.harness.legalRows).isNotEmpty()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `handoff submit next obs matches GET step packed obs`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val created = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.HUMAN,
                awayControlledBy = CoachControl.COMPUTER,
                computerOpponent = ComputerOpponentKind.HEURISTIC,
            ),
        )
        dice.usePassThrough()
        placeWhenHuman(created.matchId)
        waitUntilSeat(created.matchId, TeamSide.HOME)
        playUntilLastBudgetedAction(created.matchId)

        val submitPacked = api.aiSubmitPacked(created.matchId, claimFreeRun(created.matchId).id, forSeat = TeamSide.HOME)
        assertThat(submitPacked.next.policy.obs.values).isNotEmpty()
        if (!submitPacked.isEpisodeEnded()) {
            val stepPacked = api.aiStepPacked(created.matchId, forSeat = TeamSide.HOME)
            assertThat(submitPacked.next.policy.obs.values).isEqualTo(stepPacked.policy.obs.values)
        }
    }

    @Test
    fun `GET step forSeat waits until that seat must act`() {
        dice.queueD6(2) // HOME wins toss → AWAY is Coach A
        val created = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                homeControlledBy = CoachControl.HUMAN,
                awayControlledBy = CoachControl.COMPUTER,
                computerOpponent = ComputerOpponentKind.HEURISTIC,
            ),
        )
        dice.usePassThrough()

        val step = api.aiStep(created, forSeat = TeamSide.HOME)
        assertThat(step.harness.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(step.harness.perspectiveSeat).isEqualTo(TeamSide.HOME)
        assertThat(step.harness.legalRows).isNotEmpty()
    }

    private fun emptyTableau(): CardState =
        CardState(activeSlots = listOf(null, null, null), deck = emptyList())

    private fun claimFreeRun(match: MatchApi): LegalAction.Run =
        claimFreeRun(match.matchId)

    private fun claimFreeRun(matchId: MatchId): LegalAction.Run {
        val runs = api.legalActions<LegalAction.Run>(matchId).filter { it.potentialChallengeClaims.isEmpty() }
        check(runs.isNotEmpty()) { "no claim-free RUN; legal=${api.legalActions(matchId)}" }
        return runs.first()
    }

    private fun waitUntilSeat(matchId: MatchId, seat: TeamSide) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            val state = loadMatch(matchId)
            if (state.isGameEnded()) return
            if (state.actingSeat() == seat) return
            Thread.sleep(20)
        }
        error("timed out waiting for $seat on ${matchId.value}")
    }

    private fun playUntilLastBudgetedAction(matchId: MatchId) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            val state = loadMatch(matchId)
            check(state.actingSeat() == TeamSide.HOME) { "expected HOME to still be acting" }
            if (state.currentTurnState.phase == TurnPhase.PLAYER_ACTION && state.currentTurnState.actionsRemaining <= 1) return
            val run = api.legalActions<LegalAction.Run>(matchId).firstOrNull { it.potentialChallengeClaims.isEmpty() }
            api.play(matchId, run ?: api.legalActions(matchId).first())
        }
        error("did not reach last HOME budgeted action")
    }

    private fun placeWhenHuman(matchId: MatchId) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (System.nanoTime() < deadline) {
            val state = loadMatch(matchId)
            if (state.currentTurnState.phase != TurnPhase.PIECE_PLACEMENT) return
            if (state.config.controlledBy(state.actingSeat()) == CoachControl.HUMAN) {
                val reserves = api.legalActions(matchId).filter { it.actionType == ActionType.RESERVES }
                check(reserves.isNotEmpty()) { "human setup with no RESERVES" }
                api.play(matchId, reserves.first())
            } else {
                Thread.sleep(20)
            }
        }
        error("piece placement did not finish")
    }
}
