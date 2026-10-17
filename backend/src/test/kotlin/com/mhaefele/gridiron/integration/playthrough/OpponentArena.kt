package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.integration.playthrough.dtos.GameResult
import com.mhaefele.gridiron.game.opponent.scripted.ScriptedOpponentStrategy
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component
import java.util.Random

/**
 * Headless bot-vs-bot match loop (no Spring / HTTP).
 * Each side gets its own [OpponentStrategy]; live dice come from a caller-owned [DiceRoller].
 */
@Component
class OpponentArena(val singleGameArenaRunner: SingleGameArenaRunner) {


    data class SeriesResult(
        val games: Int,
        val lookaheadWins: Int,
        val heuristicWins: Int,
        val draws: Int,
        val details: List<GameResult>,
    ) {
        override fun toString(): String =
            "games vs team ×$games — lookahead=$lookaheadWins heuristic=$heuristicWins draws=$draws"
    }



    fun playSeries(
        games: Int,
        seed: Long = 1L,
    ): SeriesResult {
        require(games >= 2 && games % 2 == 0) { "games must be even (paired mirrors), was $games" }
        val rng = Random(seed)
        val teams = TeamCatalog.allTeams()
        require(teams.size >= 2) { "Need ≥2 teams" }
        val heuristic = ScriptedOpponentStrategy()
        val lookahead = singleGameArenaRunner.getDefaultTestLookaheadStrategy()
        val details = ArrayList<GameResult>(games)
        var lookaheadWins = 0
        var heuristicWins = 0
        var draws = 0
        var gameIndex = 0

        fun record(result: GameResult) {
            details.add(result)
            when (result.winner) {
                null          -> draws++
                TeamSide.HOME -> if (result.homeStrategy == "lookahead") lookaheadWins++ else heuristicWins++
                TeamSide.AWAY -> if (result.awayStrategy == "lookahead") lookaheadWins++ else heuristicWins++
            }
            System.err.printf(
                "game %d/%d done in %.1fs | winner=%s | score %d-%d | actions=%d | " + "legal max=%d@%s avg=%.0fms peak=%.0fms | " + "lookahead searchMoves=%d avgThink=%.0fms | standings L=%d H=%d D=%d | %s(%s) vs %s(%s)%n",
                gameIndex,
                games,
                result.gameMs / 1000.0,
                result.winner?.name ?: "draw",
                result.homeScore,
                result.awayScore,
                result.actionsPlayed,
                result.maxLegalActions,
                result.maxLegalTeamId ?: "?",
                result.avgLegalGenMs,
                result.maxLegalGenMs,
                result.searchMoves,
                result.avgSearchThinkMs,
                lookaheadWins,
                heuristicWins,
                draws,
                result.homeTeamId,
                result.homeStrategy,
                result.awayTeamId,
                result.awayStrategy,
            )
        }

        // Mirror matchups: same roster both sides so wins reflect strategy, not team tier.
        repeat(games / 2) { pair ->
            val team = teams[rng.nextInt(teams.size)]

            gameIndex++
            record(
                singleGameArenaRunner.playGame(team, team,
                                               lookahead, heuristic),
            )
            gameIndex++
            record(
                singleGameArenaRunner.playGame(team, team,
                                               heuristic, lookahead),
            )
        }

        return SeriesResult(games, lookaheadWins, heuristicWins, draws, details)
    }

}
