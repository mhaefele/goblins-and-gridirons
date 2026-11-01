package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.packed.ExpertStepRecord
import com.mhaefele.gridiron.api.ai.export.ExpertExportRequest
import com.mhaefele.gridiron.api.ai.export.ExpertExportStepRecorder
import com.mhaefele.gridiron.api.ai.export.ExpertGameExporter
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class ExpertExportTest : BaseApiTest() {

    @Autowired
    lateinit var exporter: ExpertGameExporter

    @Autowired
    lateinit var recorder: ExpertExportStepRecorder

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @BeforeEach
    fun passThroughDice() {
        dice.usePassThrough()
    }

    @Test
    fun `parallel heuristic dump writes gzip jsonl with chosen ids in each step's legals`() {
        val out = Files.createTempFile("expert-export-", ".jsonl.gz")
        try {
            val result = exporter.export(
                ExpertExportRequest(
                    games = 2,
                    threads = 2,
                    out = out,
                    homeKind = ComputerOpponentKind.LOOKAHEAD,
                    awayKind = ComputerOpponentKind.LOOKAHEAD,
                    homeTeamId = TeamCatalogId("human"),
                    awayTeamId = TeamCatalogId("human"),
                ),
                recorder,
            )
            assertThat(result.gamesOk).isEqualTo(2)
            assertThat(result.gamesSkipped).isEqualTo(0)
            assertThat(result.gamesFailed).isEqualTo(0)
            // Short scenario openings (TD_RUN / MARKED_RUN) can finish in a handful of steps.
            assertThat(result.steps).isGreaterThan(0)

            val lines = GZIPInputStream(Files.newInputStream(out)).bufferedReader().readLines().filter { it.isNotBlank() }
            assertThat(lines).hasSize(result.steps)
            val first = jsonMapper.readValue(lines.first(), ExpertStepRecord::class.java)
            assertThat(first.schemaVersion).isEqualTo(AiObsLayout.SCHEMA_VERSION)
            assertThat(first.policy.obs.values).hasSize(AiObsLayout.OBSERVATION_VECTOR_LENGTH)
            assertThat(first.softMaskLegal).hasSize(first.policy.legalMoves.size)
            assertThat(first.chosenFactors).hasSize(AiObsLayout.ACTION_FACTOR_COUNT)
            val chosenIndex = first.policy.legalMoves.indexOfFirst { it == first.chosenFactors }
            assertThat(chosenIndex).isGreaterThanOrEqualTo(0)
            assertThat(first.policy.legalMoves[chosenIndex]).isEqualTo(first.chosenFactors)
        } finally {
            Files.deleteIfExists(out)
        }
    }

    @Test
    fun `expert dump schedule covers every scenario at each difficulty evenly`() {
        val passSize = ExpertGameExporter.scenarioPassSize
        val firstPass = (0 until passSize).map { index ->
            ExpertGameExporter.scenarioSlotForGameIndex(index)!!
        }
        assertThat(firstPass).hasSize(TrainingScenarioEnum.entries.size * ExpertGameExporter.DIFFICULTY_LEVELS)
        assertThat(firstPass.map { it.difficulty }.distinct()).containsExactly(0, 1, 2, 3, 4, 5)
        for (difficulty in 0 until ExpertGameExporter.DIFFICULTY_LEVELS) {
            assertThat(firstPass.filter { it.difficulty == difficulty }.map { it.scenario })
                .containsExactlyElementsOf(TrainingScenarioEnum.entries)
        }
        assertThat(ExpertGameExporter.scenarioSlotsPerBlock)
            .isEqualTo(passSize * ExpertGameExporter.SCENARIO_PASSES_PER_BLOCK)
        for (pass in 1 until ExpertGameExporter.SCENARIO_PASSES_PER_BLOCK) {
            val repeat = (0 until passSize).map { offset ->
                ExpertGameExporter.scenarioSlotForGameIndex(pass * passSize + offset)!!
            }
            assertThat(repeat).containsExactlyElementsOf(firstPass)
        }
        assertThat(ExpertGameExporter.scenarioSlotForGameIndex(ExpertGameExporter.scenarioSlotsPerBlock)).isNull()
        assertThat(ExpertGameExporter.blockSize)
            .isEqualTo(ExpertGameExporter.scenarioSlotsPerBlock + ExpertGameExporter.REGULAR_GAMES_PER_BLOCK)
    }

    @Test
    fun `lookahead exports board drill at d0 with strict pass gate`() {
        val out = Files.createTempFile("expert-scenario-", ".jsonl.gz")
        try {
            val result = exporter.export(
                ExpertExportRequest(
                    games = 1,
                    threads = 1,
                    out = out,
                    homeKind = ComputerOpponentKind.LOOKAHEAD,
                    awayKind = ComputerOpponentKind.LOOKAHEAD,
                    homeTeamId = TeamCatalogId("human"),
                    awayTeamId = TeamCatalogId("human"),
                    maxActions = 40,
                ),
                recorder,
            )
            assertThat(result.gamesFailed).isEqualTo(0)
            assertThat(result.gamesOk).isEqualTo(1)
            assertThat(result.steps).isGreaterThan(0)
        } finally {
            Files.deleteIfExists(out)
        }
    }

    @Test
    fun `held-check scenario exports under strict Lookahead pass gate`() {
        // Enum schedule is difficulty-major; HELD_SS_REROLL at d0 is its ordinal. The walk also
        // hits exposure-only DENY_* (lookaheadSolveEval=false) — export keeps those plays without
        // requiring PASS (same as TrainingScenarioLookaheadTest).
        val heldIndex = TrainingScenarioEnum.entries.indexOf(TrainingScenarioEnum.HELD_SS_REROLL)
        val firstExposureDeny = TrainingScenarioEnum.entries.indexOf(TrainingScenarioEnum.DENY_LOCK_THEM_DOWN)
        check(firstExposureDeny in 1 until heldIndex) {
            "expected DENY_LOCK_THEM_DOWN before HELD_SS_REROLL in enum order (deny=$firstExposureDeny held=$heldIndex)"
        }
        assertThat(ExpertGameExporter.scenarioSlotForGameIndex(heldIndex))
            .isEqualTo(ExpertGameExporter.ScenarioSlot(TrainingScenarioEnum.HELD_SS_REROLL, 0))
        assertThat(ExpertGameExporter.scenarioSlotForGameIndex(firstExposureDeny))
            .isEqualTo(ExpertGameExporter.ScenarioSlot(TrainingScenarioEnum.DENY_LOCK_THEM_DOWN, 0))

        val games = heldIndex + 1
        val out = Files.createTempFile("expert-held-", ".jsonl.gz")
        try {
            val result = exporter.export(
                ExpertExportRequest(
                    games = games,
                    threads = 1,
                    out = out,
                    homeKind = ComputerOpponentKind.LOOKAHEAD,
                    awayKind = ComputerOpponentKind.LOOKAHEAD,
                    homeTeamId = TeamCatalogId("human"),
                    awayTeamId = TeamCatalogId("human"),
                    maxActions = 40,
                ),
                recorder,
            )
            assertThat(result.gamesFailed).isEqualTo(0)
            assertThat(result.gamesOk).isEqualTo(games)
            assertThat(result.steps).isGreaterThan(0)
        } finally {
            Files.deleteIfExists(out)
        }
    }
}
