package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.EndZoneSquare
import com.mhaefele.gridiron.shared.TeamSide

/** Shared loose-ball-on-TD-path setup; [contested] adds an adjacent guard and keeps the runner off the ball square. */
abstract class AbstractLooseBallTrainingScenario(
    private val contested: Boolean,
) : AbstractTrainingScenario() {

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        val scoringEndZone = trainerSide.opposite()
        val runner = placeLooseBallRunner(setup, trainerSide, scoringEndZone)
        val ballSquare = pickAndPlaceLooseBall(setup, runner, scoringEndZone, minDistanceFromRunner = if (contested) 2 else 1)
        if (contested) placeGuardAdjacentToBall(setup, trainerSide.opposite(), ballSquare)
        scatterDifficultyExtras(
            setup,
            trainerSide,
            difficulty,
            opponentAvoid = looseBallKeepClearSquares(setup, runner, ballSquare),
            trainerAvoid = setOf(ballSquare),
        )
    }

    private fun placeLooseBallRunner(setup: TrainingBoardBuilder, trainerSide: TeamSide, scoringEndZone: TeamSide): PlacedPlayer {
        // At least 2 rows from the EZ so a TD path has a mid-square for the loose ball.
        return setup.placeRandomReserve(
            trainerSide,
            playerFilter = setup::isFixedMove,
            squareFilter = setup.withinRowsOfForPlayer(scoringEndZone, minRows = 2, excludeEndZone = true),
        )
    }

    private fun placeGuardAdjacentToBall(setup: TrainingBoardBuilder, opponentSide: TeamSide, ballSquare: BoardSquare) {
        setup.placeRandomReserve(opponentSide, setup.emptyAdjacentSquares(ballSquare))
    }

    private fun pickAndPlaceLooseBall(setup: TrainingBoardBuilder, runner: PlacedPlayer, scoringEndZone: TeamSide, minDistanceFromRunner: Int): BoardSquare {
        val tdPaths = setup.runPaths(runner.player, setup.runPathEndsInEndZone(scoringEndZone))
        check(tdPaths.isNotEmpty()) { "LOOSE_BALL: no TD run path for runner" }
        val candidates = setup.squaresAlongRunPaths(tdPaths).filter { square ->
            isViableLooseBallSquare(setup, square, runner.square, minDistanceFromRunner)
        }
        check(candidates.isNotEmpty()) { "LOOSE_BALL: no viable loose ball square on TD path" }
        return setup.placeLooseBallAt(candidates.random())
    }

    private fun looseBallKeepClearSquares(setup: TrainingBoardBuilder, runner: PlacedPlayer, ballSquare: BoardSquare): Set<BoardSquare> =
        setup.adjacentSquares(ballSquare) + ballSquare + runner.adjacentSquares()

    private fun isViableLooseBallSquare(setup: TrainingBoardBuilder, square: BoardSquare, runnerSquare: BoardSquare, minDistanceFromRunner: Int): Boolean =
        setup.emptyPlayable(square) && square !is EndZoneSquare && setup.manhattanDistance(runnerSquare, square) >= minDistanceFromRunner
}
