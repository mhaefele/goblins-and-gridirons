package com.mhaefele.gridiron.integration.playthrough.arena.blindspot

import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import kotlin.system.measureTimeMillis

/**
 * Model-vs-advisor blind-spot scans — excluded from default `mvn test` (under playthrough/).
 *
 * Run manually (both tests):
 * `mvn test -Dtest=BlindSpotArenaTest -Dsurefire.useFile=false`
 *
 * Fat lookahead only:
 * `mvn test -Dtest='BlindSpotArenaTest#scan general vs fat lookahead for scoring blind spots' -Dsurefire.useFile=false`
 *
 * Heuristic only:
 * `mvn test -Dtest='BlindSpotArenaTest#scan general vs heuristic for scoring blind spots' -Dsurefire.useFile=false`
 *
 * Paste the printed tag table into `ai/BLINDSPOTS.md` when refreshing the sample run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlindSpotArenaTest : BaseApiTest() {
    @Autowired
    private lateinit var arena: BlindSpotArena

    @Autowired
    private lateinit var onnxPolicy: OnnxPolicySession

    @BeforeAll
    fun requireGeneralOnnx() {
        assumeTrue(onnxPolicy.availableProfiles().any { it == OnnxOpponentProfile.GENERAL }) { "run ai/export_opponent.sh so classpath:ai-opponent/general/ is present" }
    }

    @BeforeEach
    fun randomLiveDice() {
        dice.usePassThrough()
    }

    @Test
    fun `scan general vs fat lookahead for scoring blind spots`() {
        runScan(
            label = "fat lookahead",
            config = BlindSpotArena.Config(games = 30, seed = 42L, onnxProfile = OnnxOpponentProfile.GENERAL,),
        )
    }

    @Test
    fun `scan general vs heuristic for scoring blind spots`() {
        runScan(
            label = "heuristic",
            config = BlindSpotArena.Config(
                games = 30,
                seed = 42L,
                onnxProfile = OnnxOpponentProfile.GENERAL,
                useHeuristicAdvisorOpponent = true,
            ),
        )
    }

    private fun runScan(label: String, config: BlindSpotArena.Config) {
        lateinit var report: BlindSpotSeriesReport
        val ms = measureTimeMillis { report = arena.playSeries(config) }

        println()
        println("=== BlindSpotArena $label (${ms}ms) ===")
        println(
            "games=${report.games} disagreements=${report.disagreements} " + "interesting=${report.interesting}",)
        report.findings.forEach { f ->
            println(
                "  g=${f.gameIndex} turn=${f.liveTurnNumber} split=${f.splitIndexInTurn} " + "tags=${f.tags.joinToString(",")} " + "model=${f.modelAction} advisor=${f.advisorAction} " + "ownPts m=${f.modelPointsOwnTurn} a=${f.advisorPointsOwnTurn} " + "ownDiff m=${f.modelDiffOwnTurn} a=${f.advisorDiffOwnTurn} " + "oppDiff m=${f.modelDiffAfterOppTurn} a=${f.advisorDiffAfterOppTurn}",)
            println("    active: ${f.activeChallenges}")
            println("    model: ${f.modelOwnTurnActions.joinToString(" → ")}")
            println("    advisor: ${f.advisorOwnTurnActions.joinToString(" → ")}")
            println("    model scored through opp: ${f.modelScoringThroughOpp}")
            println("    advisor scored through opp: ${f.advisorScoringThroughOpp}")
        }

        val n = report.findings.size.coerceAtLeast(1)
        val tagCounts = report.findings.flatMap { it.tags }.groupingBy { it }.eachCount()
        val family = listOf("offense-miss", "defense-leak")
        val leaf = tagCounts.keys.filter { it !in family }.sortedByDescending { tagCounts.getValue(it) }
        val offenseOnly = report.findings.count { "offense-miss" in it.tags && "defense-leak" !in it.tags }
        val defenseOnly = report.findings.count { "defense-leak" in it.tags && "offense-miss" !in it.tags }
        val bothFamilies = report.findings.count { "offense-miss" in it.tags && "defense-leak" in it.tags }
        val neitherFamily = report.findings.count { "offense-miss" !in it.tags && "defense-leak" !in it.tags }
        println()
        println("=== Family split (n=$n interesting) ===")
        println("  offense-only\t$offenseOnly\t${"%.1f".format(100.0 * offenseOnly / n)}%")
        println("  defense-only\t$defenseOnly\t${"%.1f".format(100.0 * defenseOnly / n)}%")
        println("  both\t$bothFamilies\t${"%.1f".format(100.0 * bothFamilies / n)}%")
        println("  neither\t$neitherFamily\t${"%.1f".format(100.0 * neitherFamily / n)}%")
        println()
        println("=== Tag rates (n=$n interesting; tags may stack) ===")
        for (tag in family + leaf) {
            val c = tagCounts[tag] ?: 0
            if (c == 0 && tag !in family) continue
            println("  $tag\t$c\t${"%.1f".format(100.0 * c / n)}%")
        }
        val leafMulti = report.findings.count { f -> f.tags.count { it !in family } > 1 }
        println("  multi-leaf findings\t$leafMulti\t${"%.1f".format(100.0 * leafMulti / n)}%")

        assertThat(report.games).isEqualTo(30)
        assertThat(report.gamesDetail).hasSize(30)
        assertThat(report.gamesDetail.sumOf { it.actionsPlayed }).isGreaterThan(0)
    }
}
