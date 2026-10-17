package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertCanReach
import com.mhaefele.gridiron.support.assertCanReachVia
import com.mhaefele.gridiron.support.assertCannotPassVia
import com.mhaefele.gridiron.support.assertCannotReach
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class MovePathFinderTest {

    @Test
    fun `run paths never exceed move allowance`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        assertThat(runPaths(runner.player, board.board, 2))
            .isNotEmpty
            .allMatch { it.steps <= 2 }
    }

    @Test
    fun `run paths include start and reachable squares within allowance`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        val paths = runPaths(runner.player, board.board, 1)

        assertThat(paths.map { it.squares.first() }).containsOnly(Coordinate(5, 6))
        assertThat(paths.map { it.squares.last() }).contains(Coordinate(6, 6))
    }

    @Test
    fun `run paths cannot enter blocked squares`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(2, 4)

        val destinations = runPaths(runner.player, board.board, 3)
            .map { it.squares.last() }

        assertThat(destinations).doesNotContain(Coordinate(3, 4))
    }

    @Test
    fun `run paths cannot enter occupied squares`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = runner.team.opposite())

        val destinations = runPaths(runner.player, board.board, 2)
            .map { it.squares.last() }

        assertThat(destinations).doesNotContain(Coordinate(6, 6))
    }

    @Test
    fun `zero run allowance yields no paths`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        assertThat(runPaths(runner.player, board.board, 0)).isEmpty()
    }

    @Test
    fun `run paths require at least one step`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        assertThat(runPaths(runner.player, board.board, 1))
            .isNotEmpty
            .allMatch { it.steps >= 1 }
    }

    @Test
    fun `run paths cannot enter square adjacent to standing opposition`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(4, 6)
        board.addPlayer(6, 6, team = runner.team.opposite())

        assertThat(runPaths(runner.player, board.board, 1).map { it.squares.last() })
            .doesNotContain(Coordinate(5, 6))
    }

    @Test
    fun `run paths may enter square adjacent to prone opposition`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)
        board.addPlayer(6, 6, status = PlayerStatus.PRONE, team = runner.team.opposite())

        assertThat(runPaths(runner.player, board.board, 1).map { it.squares.last() })
            .contains(Coordinate(5, 5))
    }

    @Test
    fun `mark paths never exceed two squares`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 6)
        board.addPlayer(7, 6, team = marker.team.opposite())

        assertThat(markPaths(marker.player, board.board))
            .isNotEmpty
            .allMatch { it.steps <= 2 }
    }

    @Test
    fun `mark paths must end on a square adjacent to standing opposition`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(4, 6)
        board.addPlayer(6, 6, team = marker.team.opposite())

        val destinations = markPaths(marker.player, board.board).map { it.squares.last() }

        assertThat(destinations).contains(Coordinate(5, 6))
        assertThat(destinations).doesNotContain(Coordinate(4, 6))
    }

    @Test
    fun `mark paths may enter squares adjacent to standing opposition`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(4, 5)
        board.addPlayer(6, 6, team = marker.team.opposite())

        assertThat(markPaths(marker.player, board.board).map { it.squares.last() })
            .contains(Coordinate(5, 5))
    }

    @Test
    fun `mark paths must stop on first square adjacent to standing opposition`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(4, 5)
        board.addPlayer(6, 6, team = marker.team.opposite())

        val routes = markPaths(marker.player, board.board).map { it.squares }
        // (5,5) is Marked on entry — cannot walk on from there to another Marked square.
        assertThat(routes.filter { Coordinate(5, 5) in it })
            .isNotEmpty
            .allMatch { it.last() == Coordinate(5, 5) }
    }

    @Test
    fun `mark paths exclude destinations that would leave player open`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 6)

        assertThat(markPaths(marker.player, board.board)).isEmpty()
    }

    @Test
    fun `prone opposition does not satisfy mark ending`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(4, 6)
        board.addPlayer(6, 6, status = PlayerStatus.PRONE, team = marker.team.opposite())

        assertThat(markPaths(marker.player, board.board)).isEmpty()
    }

    @Test
    fun `run paths collapse to one route per destination`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        val run = runPaths(runner.player, board.board, 2)

        assertThat(run.map { it.squares.last() }).doesNotHaveDuplicates()
        assertThat(run).isNotEmpty
    }

    @Test
    fun `run paths prefer fewest diagonals among shortest routes to a destination`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(5, 6)

        // Prefer orthogonal: (5,6)→(6,6)→(7,6) over diagonal (5,6)→(6,5)→(7,6).
        val toEastTwo = runPaths(runner.player, board.board, 2)
            .single { it.squares.last() == Coordinate(7, 6) }

        assertThat(toEastTwo.squares).containsExactly(
            Coordinate(5, 6),
            Coordinate(6, 6),
            Coordinate(7, 6),
        )
    }

    @Test
    fun `run paths end on loose ball square and cannot continue past it`() {
        val board = GameScenario.board()
        val runner = board.addPlayer(
            4,
            6,
            profile = TestProfiles.withMove(2),
        )
        board.addLooseBall(Coordinate(5, 6))

        val ballRoutes = runPaths(runner.player, board.board, 2).map { it.squares }

        assertThat(ballRoutes).contains(listOf(Coordinate(4, 6), Coordinate(5, 6)))
        assertThat(ballRoutes).noneMatch { Coordinate(5, 6) in it.dropLast(1) }
        assertThat(ballRoutes).doesNotContain(
            listOf(Coordinate(4, 6), Coordinate(5, 6), Coordinate(6, 6)),
        )
    }

    @Test
    fun `mark paths end on loose ball square and cannot continue past it`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(4, 6)
        board.addPlayer(7, 6, team = marker.team.opposite())
        board.addLooseBall(Coordinate(6, 6)) // Marked terminal

        val ballRoutes = markPaths(marker.player, board.board).map { it.squares }

        assertThat(ballRoutes).anyMatch { it.last() == Coordinate(6, 6) }
        assertThat(ballRoutes).noneMatch { Coordinate(6, 6) in it.dropLast(1) }
    }

    @Test
    fun `mark paths collapse to one route per destination including loose ball`() {
        val board = GameScenario.board()
        val marker = board.addPlayer(5, 7)
        board.addPlayer(7, 8, team = marker.team.opposite())
        board.addLooseBall(Coordinate(6, 8))

        val mark = markPaths(marker.player, board.board)

        assertThat(mark.map { it.squares.last() }).doesNotHaveDuplicates()
        assertThat(mark.map { it.squares.last() }).contains(Coordinate(6, 8))
    }

    @Test
    fun `sidestep paths are one step from marked to open`() {
        val board = GameScenario.board()
        val player = board.addPlayer(5, 6)
        board.addPlayer(6, 6, team = player.team.opposite())

        val paths = sidestepPaths(player.player, board.board)

        assertThat(paths).isNotEmpty
        assertThat(paths).allMatch { it.steps == 1 }
        assertThat(paths.map { it.squares.last() }).contains(Coordinate(4, 6))
        assertThat(paths.map { it.squares.last() }).doesNotContain(Coordinate(6, 5), Coordinate(6, 7), Coordinate(7, 6))
    }

    companion object {
        @JvmStatic
        fun throughOccupantProfiles(): Stream<Arguments> = Stream.of(
            Arguments.of("plain lineman", TestProfiles.withMove(4), false),
            Arguments.of("ethereal Wraith", TestProfiles.wraith(), true),
            Arguments.of(
                "vault Bounder",
                TeamCatalog.team("gremlin").roster.first { it.name == "Bounder" },
                true,
            ),
        )
    }

    @ParameterizedTest(name = "{0}: through opponent expected={2}")
    @MethodSource("throughOccupantProfiles")
    fun `run through opponent`(
        @Suppress("UNUSED_PARAMETER") label: String,
        profile: PositionProfile,
        canPassThrough: Boolean,
    ) {
        // y=1/3 sealed; y=2: H . O . D
        val board = GameScenario.tinyBoard(width = 5, blocked = GameScenario.sealedEndZones(5))
        val runner = board.addPlayer(1, 2, profile = profile)
        board.addPlayer(3, 2, team = runner.team.opposite())

        val paths = runPaths(runner.player, board.board, 4)
        paths.assertCannotReach(3, 2)
        if (canPassThrough) {
            paths.assertCanReach(5, 2)
            paths.assertCannotReach(4, 2)
        } else {
            paths.assertCannotReach(5, 2)
        }
    }

    @ParameterizedTest(name = "{0}: through teammate expected={2}")
    @MethodSource("throughOccupantProfiles")
    fun `run through teammate`(
        @Suppress("UNUSED_PARAMETER") label: String,
        profile: PositionProfile,
        canPassThrough: Boolean,
    ) {
        val board = GameScenario.tinyBoard(width = 5, blocked = GameScenario.sealedEndZones(5))
        val runner = board.addPlayer(1, 2, profile = profile)
        board.addPlayer(3, 2)

        val paths = runPaths(runner.player, board.board, 4)
        paths.assertCannotReach(3, 2)
        if (canPassThrough) {
            paths.assertCanReach(5, 2)
        } else {
            paths.assertCannotReach(5, 2)
        }
    }

    @Test
    fun `elusive may continue past adjacent — plain cannot`() {
        // height=5 field rows 2..4; wall at x=3 on y=2 and y=4 leaves choke gap at (3,3):
        //   y=4: . . O . .
        //   y=3: H . . . D     path through (3,3) adjacent to wall
        //   y=2: . . O . .
        fun corridor(profile: PositionProfile) =
            GameScenario.tinyBoard(width = 5, height = 5).also { board ->
                board.addPlayer(1, 3, profile = profile)
                board.addPlayer(3, 2, team = TeamSide.AWAY)
                board.addPlayer(3, 4, team = TeamSide.AWAY)
            }

        val plain = corridor(TestProfiles.withMove(6))
        runPaths(plain.board.players.values.single { it.team == TeamSide.HOME }, plain.board, 6)
            .assertCannotReach(5, 3)

        val goblin = TeamCatalog.team("goblin").roster.first { it.name == "Goblin" }
        val elusive = corridor(goblin)
        val paths = runPaths(elusive.board.players.values.single { it.team == TeamSide.HOME }, elusive.board, 6)
        paths.assertCannotReach(3, 3) // adjacent end forbidden for Elusive
        paths.assertCanReach(5, 3)
        paths.assertCanReachVia(Coordinate(5, 3), Coordinate(3, 3))
    }

    @Test
    fun `underfoot adjacency ends opposition run`() {
        val board = GameScenario.tinyBoard(width = 5, blocked = GameScenario.sealedEndZones(5))
        board.addPlayer(
            3,
            2,
            team = TeamSide.AWAY,
            profile = TeamCatalog.team("ogre").roster.first { it.name == "Grubling" },
        )
        val runner = board.addPlayer(1, 2, profile = TestProfiles.withMove(4))

        val paths = runPaths(runner.player, board.board, 4)
        paths.assertCanReach(2, 2) // first adjacent square OK as end
        paths.assertCannotPassVia(Coordinate(2, 2))
    }

    @Test
    fun `ethereal through obstruction — plain cannot`() {
        val wall = setOf(Coordinate(2, 1), Coordinate(2, 2), Coordinate(2, 3))

        val plain = GameScenario.tinyBoard(width = 5, blocked = wall)
        val plainRunner = plain.addPlayer(1, 2, profile = TestProfiles.withMove(4))
        runPaths(plainRunner.player, plain.board, 4).assertCannotReach(3, 2)

        val eth = GameScenario.tinyBoard(width = 5, blocked = wall)
        val wraith = eth.addPlayer(1, 2, profile = TestProfiles.wraith())
        val paths = runPaths(wraith.player, eth.board, 4)
        paths.assertCannotReach(2, 2)
        paths.assertCanReachVia(Coordinate(3, 2), Coordinate(2, 2))
    }
}
