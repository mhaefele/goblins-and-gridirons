package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.dto.AiTrainingOutcome
import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingEpisodeConfig
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LooseBallRunFirstStepEndTest {
    private val looseBallRun = LooseBallRunTrainingScenario()

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `d0-d1 PASSes on opening pickup`(difficulty: Int) {
        val episode = episode(difficulty)
        val before = matchState(trainingEpisode = episode)
        val actor = homePlayerId(before)
        val pickup = LegalAction.Run(
            actor,
            Path(listOf(Coordinate(5, 8), Coordinate(5, 9))),
            ballEncounter = BallEncounter.PICK_UP,
        )
        assertThat(looseBallRun.drillStatus(before, before, pickup)).isEqualTo(TrainingDrillStatus.PASS)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `d0-d1 miss stays in progress then THEM at early end`(difficulty: Int) {
        val episode = episode(difficulty)
        val before = matchState(trainingEpisode = episode)
        val actor = homePlayerId(before)
        val junk = LegalAction.Run(
            actor,
            Path(listOf(Coordinate(5, 8), Coordinate(5, 10))),
        )
        assertThat(looseBallRun.drillStatus(before, before, junk)).isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)

        val afterFirst = before.copy(
            currentTurnState = before.currentTurnState.copy(
                actionsThisTurn = listOf(TurnAction(actor, ActionType.RUN)),
            ),
            trainingEpisode = episode.copy(drillStatus = TrainingDrillStatus.STILL_IN_PROGRESS),
        )
        assertThat(TrainingEpisodeEnd.trainingEpisodeEnded(afterFirst)).isTrue()
        assertThat(TrainingEpisodeEnd.trainingOutcome(afterFirst)).isEqualTo(AiTrainingOutcome.THEM)
    }

    @Test
    fun `d2 still requires Run TD — pickup does not PASS or early-end`() {
        val episode = episode(2)
        val before = matchState(trainingEpisode = episode)
        val actor = homePlayerId(before)
        val pickup = LegalAction.Run(
            actor,
            Path(listOf(Coordinate(5, 8), Coordinate(5, 9))),
            ballEncounter = BallEncounter.PICK_UP,
        )
        assertThat(looseBallRun.drillStatus(before, before, pickup)).isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)

        val afterFirst = before.copy(
            currentTurnState = before.currentTurnState.copy(
                actionsThisTurn = listOf(TurnAction(actor, ActionType.RUN)),
            ),
        )
        assertThat(TrainingEpisodeEnd.trainingEpisodeEnded(afterFirst)).isFalse()
        assertThat(TrainingEpisodeEnd.trainingOutcome(afterFirst)).isNull()
    }

    @Test
    fun `d0 pickup PASS ends episode even if actionsThisTurn is still empty`() {
        // Grade can stick before the turn-log row is visible to harnessEpisodeOver.
        val episode = episode(0).copy(drillStatus = TrainingDrillStatus.PASS)
        val afterPickup = matchState(trainingEpisode = episode)
        assertThat(afterPickup.currentTurnState.actionsThisTurn).isEmpty()
        assertThat(TrainingEpisodeEnd.trainingEpisodeEnded(afterPickup)).isTrue()
        assertThat(TrainingEpisodeEnd.trainingOutcome(afterPickup)).isEqualTo(AiTrainingOutcome.US)
    }

    private fun homePlayerId(state: com.mhaefele.gridiron.model.MatchState): PlayerId =
        state.board.players.values.first { it.team == TeamSide.HOME }.id

    private fun episode(difficulty: Int) = TrainingEpisodeConfig(
        scenario = TrainingScenarioEnum.LOOSE_BALL_RUN,
        difficulty = difficulty,
        trainerSide = TeamSide.HOME,
        openingTurnNumber = 1,
    )
}
