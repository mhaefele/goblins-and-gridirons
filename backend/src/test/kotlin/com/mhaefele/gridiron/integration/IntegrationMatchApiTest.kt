package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.ai.export.ExpertExportRequest
import com.mhaefele.gridiron.api.ai.export.ExpertGameExporter
import com.mhaefele.gridiron.api.integration.export.IntegrationExportStepRecorder
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files

class IntegrationMatchApiTest : BaseApiTest() {

    @BeforeEach
    fun passThroughDice() {
        dice.usePassThrough()
    }

    @Test
    fun `integration create returns match with agent seat and legals`() {
        dice.queueD6(4)
        val created = api.integrationCreateMatch()
        assertThat(created.agentSeat).isEqualTo(TeamSide.HOME)
        assertThat(created.state.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(created.state.legalActions).isNotEmpty()
        assertThat(created.state.chosenLegalActionId).isNull()
        assertThat(created.state.snapshot.bonusHand).isNotNull()
    }

    @Test
    fun `integration state returns standard snapshot and LegalAction rows`() {
        dice.queueD6(4)
        val created = api.integrationCreateMatch()
        finishIntegrationPlacement(created.matchId)
        val frame = api.integrationState(created.matchId)

        assertThat(frame.snapshot.gameState.activeCoach).isEqualTo(TeamSide.HOME)
        assertThat(frame.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(frame.legalActions).isNotEmpty()
        assertThat(frame.legalActions.first()).isInstanceOf(LegalAction::class.java)
        assertThat(frame.events).isEmpty()
    }

    @Test
    fun `integration play blocks until agent seat and returns events since pick`() {
        dice.queueD6(4)
        val created = api.integrationCreateMatch()
        finishIntegrationPlacement(created.matchId)
        val before = api.integrationState(created.matchId)
        val beforeEventId = before.snapshot.lastEventId
        val pick = before.legalActions.first { it is LegalAction.Skip || it is LegalAction.Run }

        val result = api.integrationPlay(created.matchId, pick.id)

        assertThat(result.chosenLegalActionId).isEqualTo(pick.id)
        assertThat(result.snapshot.lastEventId).isGreaterThan(beforeEventId)
        assertThat(result.events).isNotEmpty()
        assertThat(result.events.map { it.id.domainId }.minOrNull()).isGreaterThan(beforeEventId)
        assertThat(result.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(result.legalActions).isNotEmpty()
    }

    private fun finishIntegrationPlacement(matchId: MatchId) {
        var frame = api.integrationState(matchId)
        while (frame.legalActions.any { it.actionType == ActionType.RESERVES }) {
            api.integrationPlay(matchId, frame.legalActions.first().id)
            frame = api.integrationState(matchId)
        }
    }
}

class IntegrationExportTest : BaseApiTest() {

    @Autowired
    lateinit var exporter: ExpertGameExporter

    @Autowired
    lateinit var recorder: IntegrationExportStepRecorder

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @BeforeEach
    fun passThroughDice() {
        dice.usePassThrough()
    }

    @Test
    fun `integration export writes jsonl with snapshot legals and events`() {
        val out = Files.createTempFile("integration-export-", ".jsonl")
        try {
            val result = exporter.export(
                ExpertExportRequest(
                    games = 1,
                    threads = 1,
                    out = out,
                    homeTeamId = com.mhaefele.gridiron.model.catalog.TeamCatalogId("human"),
                    awayTeamId = com.mhaefele.gridiron.model.catalog.TeamCatalogId("human"),
                ),
                recorder,
            )
            assertThat(result.gamesOk).isEqualTo(1)
            assertThat(result.steps).isGreaterThan(0)

            val lines = Files.readAllLines(out).filter { it.isNotBlank() }
            assertThat(lines).hasSize(result.steps)
            val first = jsonMapper.readValue(lines.first(), com.mhaefele.gridiron.api.integration.dto.IntegrationMatchStateDto::class.java)
            assertThat(first.snapshot.gameState).isNotNull()
            assertThat(first.legalActions).isNotEmpty()
            assertThat(first.chosenLegalActionId).isNotNull()
            assertThat(first.legalActions.map { it.id.value }).contains(first.chosenLegalActionId!!.value)
            assertThat(first.events).isNotEmpty()
        } finally {
            Files.deleteIfExists(out)
        }
    }
}
