package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.createPlayers
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardDefinition
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Shared game test harness and data generator.
 *
 * Every test should eventually start here — request only as much of a real game as the
 * test needs: empty pitch, both teams deployed, ball on trapdoor, mid-turn after a run, etc.
 * Add new factory methods as match flow and actions land; prefer convention-over-configuration
 * defaults with overrides for what the test actually cares about.
 *
 * [com.mhaefele.gridiron.scenario.LivingGamePlaythrough] is the verbose, println-driven
 * runner on top of this for watching the game evolve during development.
 */
object GameScenario {
    fun emptyBoard(definition: BoardDefinition = PitchCatalog.definition("default")): Board =
        definition.emptyBoard()

    fun board(from: Board = emptyBoard()): BoardScenario = BoardScenario(from)

    /**
     * Empty purpose-built pitch for geometry / ability tests — no default-pitch pillars.
     * [height] must be ≥ 2 (HOME/AWAY end-zone rows). Interior field rows are `2..(height-1)`.
     */
    fun tinyBoard(
        width: Int = 8,
        height: Int = 3,
        blocked: Set<Coordinate> = emptySet(),
        trapdoors: Set<Coordinate> = emptySet(),
    ): BoardScenario =
        board(
            emptyBoard(
                BoardDefinition(
                    playWidth = width,
                    playHeight = height,
                    trapdoors = trapdoors,
                    blockedSquares = blocked,
                ),
            ),
        )

    /**
     * Block both end-zone rows so paths cannot detour north/south — forces a single-row corridor
     * on the interior field (height 3 → only y=2 is walkable).
     */
    fun sealedEndZones(width: Int, height: Int = 3): Set<Coordinate> =
        (1..width).flatMap { x -> listOf(Coordinate(x, 1), Coordinate(x, height)) }.toSet()

    fun context(activeCoach: TeamSide): TurnContextScenario = TurnContextScenario(activeCoach)

    fun context(player: PlacedPlayer): TurnContextScenario = context(player.team)

    fun bothTeamsDeployed(
        from: Board = emptyBoard(),
        playersPerTeam: Int = 6,
        homeTeamId: TeamCatalogId = TeamCatalog.DWARVES_ID,
        awayTeamId: TeamCatalogId = TeamCatalog.ORCS_ID,
    ): Board {
        val home = TeamCatalog.team(homeTeamId)
        val away = TeamCatalog.team(awayTeamId)
        return if (playersPerTeam == 6) {
            deployTeamsForFixture(from, home, away)
        } else {
            deployPartialRoster(from, home, away, playersPerTeam)
        }
    }

    /**
     * Instant end-zone deploy for fixtures — not the production placement path
     * (interactive [com.mhaefele.gridiron.shared.TurnPhase.PIECE_PLACEMENT] / RESERVES actions).
     * Player ids match [com.mhaefele.gridiron.game.createPlayers].
     */
    fun deployTeamsForFixture(
        board: Board,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
    ): Board {
        val mutator = BoardMutator(board)
        createPlayers(homeTeam, awayTeam)
            .groupBy { it.team }
            .forEach { (team, players) ->
                endZoneCoords(board, team)
                    .zip(players)
                    .forEach { (coord, player) ->
                        mutator.playerPlacedOnPitch(player.copy(status = PlayerStatus.STANDING), coord)
                    }
            }
        return mutator.board
    }

    private fun deployPartialRoster(
        board: Board,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        playersPerTeam: Int,
    ): Board {
        val scenario = BoardScenario(board)
        endZoneCoords(board, TeamSide.HOME)
            .take(playersPerTeam)
            .forEachIndexed { index, coord -> scenario.addPlayer(coord, profile = homeTeam.roster[index]) }
        endZoneCoords(board, TeamSide.AWAY)
            .take(playersPerTeam)
            .forEachIndexed { index, coord ->
                scenario.addPlayer(coord, team = TeamSide.AWAY, profile = awayTeam.roster[index])
            }
        return scenario.board
    }

    private fun endZoneCoords(board: Board, team: TeamSide): List<Coordinate> =
        board.allSquares()
            .filter { it.isEndZone(team) }
            .map { it.coordinate }
            .sortedWith(compareBy({ it.y }, { it.x }))
}
