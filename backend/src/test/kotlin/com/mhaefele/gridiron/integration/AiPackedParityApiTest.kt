package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.mapping.AiObsPacker
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Sanity: packed route is the same tree with [AiPackedObsDto] on [policy.obs]. */
class AiPackedParityApiTest : BaseApiTest() {

    @Autowired
    private lateinit var obsPacker: AiObsPacker

    @Test
    fun `TD_RUN nested step packs equal to step slash packed`() {
        dice.queueD6(List(48) { 6 })
        val created = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.TD_RUN))
        val nested = api.aiStep(created.matchId, forSeat = TeamSide.HOME)
        val packed = api.aiStepPacked(created.matchId, forSeat = TeamSide.HOME)

        assertThat(packed.schemaVersion).isEqualTo(nested.schemaVersion)
        assertThat(packed.harness).isEqualTo(nested.harness)
        assertThat(packed.policy.legalMoves).isEqualTo(nested.policy.legalMoves)
        assertThat(packed.policy.obs.values).containsExactlyElementsOf(
            obsPacker.pack(nested.policy.obs).toList(),
        )
    }
}
