package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScenarioCoachAPinApiTest : BaseApiTest() {

    @Test
    fun `scenario match pins HOME trainer as coach A`() {
        val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.STRIP_AND_SCORE))

        val state = loadMatch(response.matchId)
        assertThat(state.config.homeControlledBy).isEqualTo(CoachControl.HARNESS)
        assertThat(state.config.awayControlledBy).isEqualTo(CoachControl.COMPUTER)
        assertThat(state.trainingEpisode!!.trainerSide).isEqualTo(TeamSide.HOME)
        assertThat(state.config.coachA).isEqualTo(TeamSide.HOME)
        assertThat(state.currentTurnState.activeCoach).isEqualTo(TeamSide.HOME)
    }
}
