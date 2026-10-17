package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.system.measureTimeMillis

/**
 * Heuristic vs lookahead series — excluded from default `mvn test` (slow).
 *
 * Run manually:
 * `mvn test -Dtest=OpponentArenaTest -Dsurefire.useFile=false`
 *
 * Uses a wide/shallow [LookaheadSearch] (many roots, deepen top beams 2–3 plies) with a
 * per-choice time budget so arena games cannot stall forever.
 */
class HeuristicVsLookaheadTest: BaseApiTest() {
    @Autowired
    lateinit var opponentArena: OpponentArena

    @BeforeEach
    fun setup() {
        dice.usePassThrough()
    }

    @Test
    fun `lookahead vs heuristic over 100 random-team games`() {
        lateinit var series: OpponentArena.SeriesResult
        val ms = measureTimeMillis { series = opponentArena.playSeries(games = 100, seed = 42L) }

        println()
        println("=== OpponentArena 100 games (${ms}ms) ===")
        println(series)
        series.details.forEachIndexed { i, g ->
            println(
                "  #${i + 1} ${g.homeTeamId}(${g.homeStrategy}) ${g.homeScore}-" + "${g.awayScore} ${g.awayTeamId}(${g.awayStrategy}) " + "winner=${g.winner ?: "draw"} turns=${g.turnNumber} " + "game=${g.gameMs}ms actions=${g.actionsPlayed} " + "legalMax=${g.maxLegalActions}@${g.maxLegalTeamId ?: "?"} " + "avgLegal=${"%.0f".format(g.avgLegalGenMs)}ms peakLegal=${"%.0f".format(g.maxLegalGenMs)}ms " + "searchMoves=${g.searchMoves} avgThink=${"%.0f".format(g.avgSearchThinkMs)}ms",)
        }

        val byPeakLegal = series.details.sortedByDescending { it.maxLegalGenMs }.take(10)
        println()
        println("=== Slowest legal-move peaks (top 10) ===")
        byPeakLegal.forEach { g ->
            println(
                "  ${g.homeTeamId} vs ${g.awayTeamId} | peak=${"%.0f".format(g.maxLegalGenMs)}ms " + "avg=${"%.0f".format(g.avgLegalGenMs)}ms maxActions=${g.maxLegalActions}@${g.maxLegalTeamId}",)
        }
        val bySpace = series.details.sortedByDescending { it.maxLegalActions }.take(10)
        println()
        println("=== Largest legal-move spaces (top 10) ===")
        bySpace.forEach { g ->
            println(
                "  ${g.homeTeamId} vs ${g.awayTeamId} | maxActions=${g.maxLegalActions}@${g.maxLegalTeamId} " + "peak=${"%.0f".format(g.maxLegalGenMs)}ms",)
        }

        assertThat(series.games).isEqualTo(100)
        assertThat(series.lookaheadWins + series.heuristicWins + series.draws).isEqualTo(100)
        assertThat(series.details.count { it.actionsPlayed > 10 }).isGreaterThan(80)
    }
}
