package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.integration.playthrough.dtos.GameResult
import com.mhaefele.gridiron.integration.playthrough.dtos.GameStatistics
import com.mhaefele.gridiron.integration.playthrough.dtos.TeamSetup
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.model.MatchResult
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.scenario.formatDomainEvents
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryDomainEventRepository
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import com.mhaefele.gridiron.support.bareEvents
import org.springframework.stereotype.Component
import kotlin.system.measureTimeMillis

@Component
class SingleGameArenaRunner(
    private val matchGame: MatchGame,
    private val repository: InMemoryMatchRepository,
    private val eventRepository: InMemoryDomainEventRepository,
    private val lookahead: LookaheadOpponentStrategy,
    private val legalMovesGenerator: LegalMovesGenerator,
) {
    fun getTeamSetup(
        strategy: OpponentStrategy,
        team: TeamCard,
        suffix: String,
    ): TeamSetup {
        val seat = strategy as? FixedBudgetLookahead
        val lookAheadTeam = TeamSetup(strategy = strategy, team = team, label = if (seat != null) "lookahead" else "hueristic" + suffix,
                                      telemetry = seat?.telemetry)
        return lookAheadTeam
    }

    /** Pins the computer-vs-computer limit on this seat, and keeps the counter the game summary prints. */
    fun getDefaultTestLookaheadStrategy(): FixedBudgetLookahead =
        FixedBudgetLookahead(
            lookahead = lookahead,
            thinkBudgetOverride = LookaheadOpponentStrategy.AI_VS_AI_THINK_NANOS,
            telemetry = LookaheadOpponentStrategy.SearchTelemetry(),
        )

    fun playGame(
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        homeStrategy: OpponentStrategy = getDefaultTestLookaheadStrategy(),
        awayStrategy: OpponentStrategy = getDefaultTestLookaheadStrategy(),
        maxActions: Int = 400,
    ): GameResult {
        val homeSetup = getTeamSetup(homeStrategy, homeTeam, "-home")
        val awaySetup = getTeamSetup(awayStrategy, awayTeam, "-away")
        return playGame(homeSetup, awaySetup, maxActions)
    }

    fun playGame(
        homeTeamSetup: TeamSetup,
        awayTeamSetup: TeamSetup,
        maxActions: Int = 400,
    ): GameResult {
        // Optional: only Lookahead strategies expose search telemetry.
        val telemetry = homeTeamSetup.telemetry ?: awayTeamSetup.telemetry
        telemetry?.reset()

        var state = matchGame.createMatch(
            homeTeamId = homeTeamSetup.team.id,
            awayTeamId = awayTeamSetup.team.id,
            homeControlledBy = CoachControl.COMPUTER,
            awayControlledBy = CoachControl.COMPUTER,
        )

        val gameStatistics = GameStatistics()

        try {
            gameStatistics.gameMs = measureTimeMillis {
                var lastOutcome = matchGame.startMatch(state.id)
                state = repository.findById(state.id)
                do {
                    // Find the last input required event (should only be one anyways).
                    // May not be the active coach for interupting special abilites/cards
                    val inputRequiredEvents = lastOutcome.events.map { it.event }.filterIsInstance<DomainEvent.InputRequired>()
                    check(inputRequiredEvents.isNotEmpty()) { "No input required events found.  Events:  $inputRequiredEvents   lastOutcome:  $lastOutcome" }
                    val teamToPlay = inputRequiredEvents.last().team

                    val teamStatistics = gameStatistics.getTeamStatistics(teamToPlay)

                    val legal = teamStatistics.collectLegalMovesStatistics { legalMovesGenerator.forActingCoach(state) }

                    check(legal.isNotEmpty())

                    val strategy = when (teamToPlay) {
                        TeamSide.HOME -> homeTeamSetup.strategy
                        TeamSide.AWAY -> awayTeamSetup.strategy
                    }

                    val chosen = strategy.chooseAction(state, legal)
                    lastOutcome = matchGame.submitActionOutcome(state.id, chosen)

                    teamStatistics.actions++
                    state = repository.findById(state.id)
                } while (state.progress.result == null && gameStatistics.actions < maxActions)
            }
        }catch (e: Exception) {
            val events = formatDomainEvents(eventRepository.findByMatchId(state.id).bareEvents())

            throw RuntimeException("Exception during game play.  ${e.message}\nEvents:  \n  ${events.joinToString("\n  ") }", e)
        }

        val finished = state.progress.result != null
        return GameResult(
            winner = if (finished) winnerOf(state) else null,
            homeTeamSetup,
            awayTeamSetup,
            state.progress,
            searchMoves = telemetry?.searchMoves ?: 0,
            avgSearchThinkMs = telemetry?.averageThinkMs ?: 0.0,
            gameStatistics = gameStatistics
        )
    }

    private fun winnerOf(state: MatchState): TeamSide? =
        when (val result = state.progress.result) {
            null                         -> null
            is MatchResult.SuddenDeath   -> result.winner
            is MatchResult.PointsVictory -> result.winner
        }
}