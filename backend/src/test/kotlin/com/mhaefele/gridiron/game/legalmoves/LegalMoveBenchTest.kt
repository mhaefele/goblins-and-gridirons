package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.model.MatchConfig
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.system.measureNanoTime

/**
 * Legal-move enumeration timings — excluded from default `mvn test` (slow / noisy).
 *
 * Run manually:
 * `mvn test -Dtest=LegalMoveBenchTest -Dsurefire.useFile=false`
 */
class LegalMoveBenchTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Autowired
    private lateinit var regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator


    @Test
    fun `bench legal moves for every team as active coach`() {
        val awayId = TeamCatalog.ORCS_ID
        val rows = TeamCatalog.allTeams().map { home ->
            val state = deployedState(home.id, awayId)
            repeat(2) { legalMovesGenerator.forActingCoach(state) }
            val times = LongArray(5)
            var count = 0
            lateinit var sample: List<LegalAction>
            repeat(5) { i ->
                times[i] = measureNanoTime {
                    sample = legalMovesGenerator.forActingCoach(state)
                    count = sample.size
                }
            }
            times.sort()
            BenchRow(
                homeId = home.id,
                players = state.board.players.size,
                actions = count,
                p50Ms = times[2] / 1_000_000.0,
                maxMs = times.last() / 1_000_000.0,
                breakdown = breakdown(sample),
            )
        }.sortedByDescending { it.p50Ms }

        println()
        println("=== LegalMoveBench all teams vs ${awayId.value} (sorted by p50) ===")
        for (row in rows) {
            println(
                "BENCH ${row.homeId.value.padEnd(20)} | players=${row.players} actions=${row.actions.toString().padStart(4)} | " + "ms p50=${"%.1f".format(row.p50Ms).padStart(6)} max=${"%.1f".format(row.maxMs).padStart(6)} | " + row.breakdown,)
        }
        val hot = rows.filter { it.p50Ms >= 100.0 || it.actions >= 400 }
        if (hot.isNotEmpty()) {
            println()
            println("HOT (≥100ms p50 or ≥400 actions): ${hot.joinToString { it.homeId.value }}")
        }
    }

    private data class BenchRow(
        val homeId: TeamCatalogId,
        val players: Int,
        val actions: Int,
        val p50Ms: Double,
        val maxMs: Double,
        val breakdown: String,
    )

    private fun breakdown(actions: List<LegalAction>): String {
        val counts = actions.groupingBy { it::class.simpleName ?: "?" }.eachCount()
            .entries.sortedByDescending { it.value }
        return counts.take(5).joinToString(" ") { "${it.key}=${it.value}" }
    }

    private fun deployedState(homeId: TeamCatalogId, awayId: TeamCatalogId) =
        matchState(
            board = GameScenario.deployTeamsForFixture(
                GameScenario.emptyBoard(),
                TeamCatalog.team(homeId),
                TeamCatalog.team(awayId),
            ),
            config = MatchConfig(
                coachA = TeamSide.HOME,
                homeTeamId = homeId,
                awayTeamId = awayId,
                homeEmergencyReserves = TeamCatalog.team(homeId).emergencyReserves,
                awayEmergencyReserves = TeamCatalog.team(awayId).emergencyReserves,
            ),
            actionsRemaining = 3,
        ).withTurnNumber(2) // claims enabled

    private fun ms(nanos: Long): String = "%.1f".format(nanos / 1_000_000.0)
}
