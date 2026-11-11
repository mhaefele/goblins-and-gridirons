package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadScorer
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

/**
 * Diagnostic: Lookahead (same think budget as expert export) sometimes picks a Run into the
 * scoring end zone **without** holding the ball. Collect a handful of openings — especially
 * [TrainingScenarioEnum.LOOSE_BALL_BOUNCE] boards — and log board + menu context so we can see
 * why search preferred that path (guard block available? loose ball? EV?).
 *
 * Not a correctness gate on the pick; only that we **observe** some samples in a long enough run.
 */
class LookaheadBallLessEndZoneRunProbeTest : BaseApiTest() {
    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var lookahead: LookaheadOpponentStrategy

    @BeforeEach
    fun randomDice() {
        dice.usePassThrough()
    }

    @Test
    fun `probe lookahead ball-less scoring-EZ runs on bounce openings`() {
        val want = 3
        val maxOpenings = 200
        val samples = mutableListOf<String>()
        var openings = 0
        while (samples.size < want && openings < maxOpenings) {
            openings++
            val difficulty = (openings - 1) % 6
            val note = probeOneOpening(TrainingScenarioEnum.LOOSE_BALL_BOUNCE, difficulty) ?: continue
            samples += note
            log.info("ball-less EZ sample {}/{} (opening {}):\n{}", samples.size, want, openings, note)
        }
        log.info(
            "Collected {} ball-less EZ runs in {} bounce openings (~{}%):\n\n{}",
            samples.size,
            openings,
            if (openings == 0) 0 else 100 * samples.size / openings,
            samples.joinToString("\n\n---\n\n"),
        )
        assertThat(samples)
            .withFailMessage(
                "expected ≥$want ball-less scoring-EZ Lookahead picks on LOOSE_BALL_BOUNCE in $maxOpenings openings; got ${samples.size}",
            )
            .hasSizeGreaterThanOrEqualTo(want)
    }

    /**
     * One scenario opening: Lookahead chooses with [LookaheadOpponentStrategy.EXPERT_EXPORT_THINK_NANOS].
     * Returns a detail dump when the pick is a ball-less Run into the scoring end zone.
     */
    private fun probeOneOpening(scenario: TrainingScenarioEnum, difficulty: Int): String? {
        val created = api.createScenarioMatch(
            scenarioMatchRequest(
                scenario = scenario,
                difficulty = difficulty,
                episodeMode = TrainingEpisodeMode.EVAL,
                awayControlledBy = CoachControl.COMPUTER,
            ),
        )
        matchId = created.matchId
        val state = loadMatch(created.matchId)
        val trainer = state.trainingEpisode!!.trainerSide
        val legals = api.legalActions(created.matchId)
        val chosen = lookahead.chooseAction(
            state,
            legals,
            LookaheadOpponentStrategy.EXPERT_EXPORT_THINK_NANOS,
        )
        val pick = chosen.action
        val run = pick as? LegalAction.Run
        if (run == null || !isBallLessScoringEndZoneRun(state, run, trainer)) {
            api.play(created.matchId, pick) // leave match tidy; not required for assert
            return null
        }
        return formatSample(
            scenario = scenario,
            difficulty = difficulty,
            state = state,
            trainer = trainer,
            legals = legals,
            pick = run,
            search = chosen.search,
        )
    }

    private fun isBallLessScoringEndZoneRun(state: MatchState, action: LegalAction, trainer: TeamSide): Boolean {
        if (action !is LegalAction.Run) return false
        val player = state.board.players[action.playerId] ?: return false
        if (player.team != trainer) return false
        if (player.heldBallId != null) return false
        val dest = action.path.squares.last()
        return state.board.squareAt(dest).isEndZone(trainer.opposite())
    }

    private fun formatSample(
        scenario: TrainingScenarioEnum,
        difficulty: Int,
        state: MatchState,
        trainer: TeamSide,
        legals: List<LegalAction>,
        pick: LegalAction.Run,
        search: LookaheadOpponentStrategy.SearchScores,
    ): String {
        val board = state.board
        val player = board.player(pick.playerId)
        val from = board.findSquare(pick.playerId).coordinate
        val dest = pick.path.squares.last()
        val ballSquare = board.allSquares().firstOrNull { it.looseBallId != null }
        val ballCoord = ballSquare?.coordinate
        val guardCoords = ballSquare?.let { square ->
            neighbors(square, board).mapNotNull { adj ->
                val occupant = adj.playerId?.let { board.players[it] } ?: return@mapNotNull null
                if (occupant.team == trainer) return@mapNotNull null
                adj.coordinate
            }
        }.orEmpty()
        val guardBlocks = legals.filterIsInstance<LegalAction.Block>().filter { block ->
            board.players[block.playerId]?.team == trainer && block.destination in guardCoords
        }
        val ontoBall = legals.filterIsInstance<LegalAction.Run>().filter { run ->
            ballCoord != null && run.path.squares.last() == ballCoord
        }
        val scoringEzRuns = legals.filterIsInstance<LegalAction.Run>().filter { run ->
            val p = board.players[run.playerId] ?: return@filter false
            p.team == trainer && board.squareAt(run.path.squares.last()).isEndZone(trainer.opposite())
        }
        val withBall = scoringEzRuns.count { board.players[it.playerId]?.heldBallId != null }
        val withoutBall = scoringEzRuns.size - withBall
        val baseline = LookaheadScorer.score(state, trainer)
        val points = LookaheadScorer.points(state, trainer)
        val searchLine =
            if (search.usedSearch) {
                "search baseline=${"%.1f".format(search.baseline)} best=${"%.1f".format(search.bestScore)} " +
                    "Δ=${"%.1f".format(search.bestScore - search.baseline)} decisive=${search.decisive}"
            } else {
                "search=skipped (obvious / heuristic path)"
            }
        return buildString {
            appendLine("$scenario d$difficulty trainer=$trainer")
            appendLine(
                "pick: Run ${pick.playerId.value} $from→$dest steps=${pick.path.steps} " +
                    "heldBall=${player.heldBallId} encounter=${pick.ballEncounter}",
            )
            appendLine("scorer: points=$points leafScore=${"%.1f".format(baseline)}")
            appendLine(searchLine)
            appendLine("looseBall=$ballCoord guards=$guardCoords")
            appendLine(
                "menu: guardBlocks=${guardBlocks.size} runsOntoBall=${ontoBall.size} " +
                    "scoringEzRuns=$withoutBall ball-less / $withBall with-ball (of ${scoringEzRuns.size})",
            )
            if (guardBlocks.isNotEmpty()) {
                appendLine(
                    "  guardBlock examples: " +
                        guardBlocks.take(3).joinToString { b ->
                            "${b.playerId.value}@${board.findSquare(b.playerId).coordinate}→${b.destination}"
                        },
                )
            }
            if (ontoBall.isNotEmpty()) {
                appendLine(
                    "  ontoBall examples: " +
                        ontoBall.take(3).joinToString { r ->
                            "${r.playerId.value}→${r.path.squares.last()}/${r.ballEncounter}"
                        },
                )
            }
            appendLine("us on pitch: " + usPitchSummary(state, trainer))
        }.trimEnd()
    }

    private fun usPitchSummary(state: MatchState, trainer: TeamSide): String {
        val board = state.board
        return board.allSquares().mapNotNull { square ->
            val id = square.playerId ?: return@mapNotNull null
            val p = board.players[id] ?: return@mapNotNull null
            if (p.team != trainer) return@mapNotNull null
            "${p.id.value}@${square.coordinate} ball=${p.heldBallId != null}"
        }.joinToString()
    }
}
