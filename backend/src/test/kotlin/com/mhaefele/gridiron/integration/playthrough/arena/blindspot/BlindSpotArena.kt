package com.mhaefele.gridiron.integration.playthrough.arena.blindspot

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.ai.AiSubmitTiming
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadSearch
import com.mhaefele.gridiron.game.opponent.onnx.OnnxOpponentFactory
import com.mhaefele.gridiron.game.opponent.scripted.ScriptedOpponentStrategy
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.springframework.stereotype.Component
import java.util.Random

/**
 * Diagnostic arena: ONNX model plays one seat; an advisor policy advises + plays the other.
 * Default advisor/opponent is fat lookahead; [Config.useHeuristicAdvisorOpponent] swaps both
 * to [ScriptedOpponentStrategy]. On each model/advisor action disagreement, shared-dice forks
 * finish the turn (and the opponent's following turn) and record cases where the advisor path
 * scores better.
 *
 * Test/harness only — lives under playthrough (surefire-excluded) and does not change product
 * paths beyond [LookaheadSearch.apply].
 */
@Component
class BlindSpotArena(
    private val matchGame: MatchGame,
    private val repository: InMemoryMatchRepository,
    private val legalMovesGenerator: LegalMovesGenerator,
    private val forkPlayer: TurnForkPlayer,
    private val lookaheadSearch: LookaheadSearch,
    private val scriptedOpponentStrategy: ScriptedOpponentStrategy,
    private val submitTiming: AiSubmitTiming,
    private val onnxFactory: OnnxOpponentFactory,
) {
    data class Config(
        val games: Int = 4,
        val seed: Long = 42L,
        val onnxProfile: OnnxOpponentProfile = OnnxOpponentProfile.GENERAL,
        /** Advisor / fork lookahead think budget (default 1s — above live AI-vs-AI 100ms). Ignored when [useHeuristicAdvisorOpponent]. */
        val advisorThinkNanos: Long = ADVISOR_THINK_NANOS,
        /** Live opponent seat think budget. Ignored when [useHeuristicAdvisorOpponent]. */
        val opponentThinkNanos: Long = LookaheadOpponentStrategy.HUMAN_THINK_NANOS,
        val maxActions: Int = 400,
        val modelSeat: TeamSide = TeamSide.HOME,
        /** When true, advisor and live opponent are both [ScriptedOpponentStrategy] (no fat LA). */
        val useHeuristicAdvisorOpponent: Boolean = false,
    )

    fun playSeries(config: Config = Config()): BlindSpotSeriesReport {
        require(config.games >= 1) { "games must be ≥ 1" }
        val rng = Random(config.seed)
        val teams = TeamCatalog.allTeams()
        require(teams.size >= 2) { "Need ≥2 teams" }

        val model = onnxFactory.forProfile(config.onnxProfile)
        val advisor: OpponentStrategy
        val opponent: OpponentStrategy
        if (config.useHeuristicAdvisorOpponent) {
            advisor = scriptedOpponentStrategy
            opponent = scriptedOpponentStrategy
        } else {
            advisor = fatLookahead(config.advisorThinkNanos)
            opponent = fatLookahead(config.opponentThinkNanos)
        }

        val findings = ArrayList<BlindSpotFinding>()
        val gameReports = ArrayList<BlindSpotGameReport>(config.games)
        var disagreements = 0

        repeat(config.games) { gameIndex ->
            val homeTeam = teams[rng.nextInt(teams.size)]
            var awayTeam = teams[rng.nextInt(teams.size)]
            while (awayTeam.id == homeTeam.id) awayTeam = teams[rng.nextInt(teams.size)]

            val report = playGame(
                gameIndex = gameIndex,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                model = model,
                advisor = advisor,
                opponent = opponent,
                modelSeat = config.modelSeat,
                maxActions = config.maxActions,
                diceSeedBase = config.seed + gameIndex * 1_000_003L,
            )
            disagreements += report.disagreements
            findings.addAll(report.interesting)
            gameReports.add(report)
            System.err.printf(
                "blind-spot game %d/%d %s vs %s | score %d-%d | disagreements=%d interesting=%d actions=%d%n",
                gameIndex + 1,
                config.games,
                homeTeam.id.value,
                awayTeam.id.value,
                report.homeScore,
                report.awayScore,
                report.disagreements,
                report.interesting.size,
                report.actionsPlayed,
            )
        }

        return BlindSpotSeriesReport(
            games = config.games,
            disagreements = disagreements,
            interesting = findings.size,
            findings = findings,
            gamesDetail = gameReports,
        )
    }

    private fun playGame(
        gameIndex: Int,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        model: OpponentStrategy,
        advisor: OpponentStrategy,
        opponent: OpponentStrategy,
        modelSeat: TeamSide,
        maxActions: Int,
        diceSeedBase: Long,
    ): BlindSpotGameReport {
        var state = matchGame.createMatch(
            homeTeamId = homeTeam.id,
            awayTeamId = awayTeam.id,
            homeControlledBy = CoachControl.COMPUTER,
            awayControlledBy = CoachControl.COMPUTER,
        )
        var lastOutcome = matchGame.startMatch(state.id)
        state = repository.findById(state.id)

        val interesting = ArrayList<BlindSpotFinding>()
        var disagreements = 0
        var actionsPlayed = 0
        var splitInTurn = 0
        var lastTurnNumber = state.progress.turnNumber
        var forkOrdinal = 0

        while (state.progress.result == null && actionsPlayed < maxActions) {
            val inputRequired = lastOutcome.events.map { it.event }.filterIsInstance<DomainEvent.InputRequired>()
            check(inputRequired.isNotEmpty()) { "No InputRequired at action $actionsPlayed" }
            val acting = inputRequired.last().team
            val legal = legalMovesGenerator.forActingCoach(state)
            check(legal.isNotEmpty()) { "Empty legal menu at action $actionsPlayed" }

            if (state.progress.turnNumber != lastTurnNumber) {
                lastTurnNumber = state.progress.turnNumber
                splitInTurn = 0
            }

            val chosen: LegalAction
            if (acting == modelSeat) {
                val modelPick = model.chooseAction(state, legal)
                val advisorPick = advisor.chooseAction(state, legal)
                if (modelPick.id != advisorPick.id) {
                    disagreements++
                    splitInTurn++
                    forkOrdinal++
                    val diceSeed = diceSeedBase xor (forkOrdinal.toLong() shl 17) xor state.progress.turnNumber.toLong()
                    val finding = compareFork(
                        gameIndex = gameIndex,
                        snapshot = state,
                        focal = modelSeat,
                        splitIndexInTurn = splitInTurn,
                        modelAction = modelPick,
                        advisorAction = advisorPick,
                        modelStrategy = model,
                        advisorStrategy = advisor,
                        opponentStrategy = opponent,
                        diceSeed = diceSeed,
                    )
                    if (finding.interesting) {
                        interesting.add(finding)
                        logFinding(finding)
                    }
                }
                chosen = modelPick
            } else {
                chosen = opponent.chooseAction(state, legal)
            }

            lastOutcome = matchGame.submitActionOutcome(state.id, chosen)
            actionsPlayed++
            state = repository.findById(state.id)
        }

        return BlindSpotGameReport(
            gameIndex = gameIndex,
            homeTeamId = homeTeam.id,
            awayTeamId = awayTeam.id,
            modelSeat = modelSeat,
            disagreements = disagreements,
            interesting = interesting,
            homeScore = state.progress.homeScore.points,
            awayScore = state.progress.awayScore.points,
            actionsPlayed = actionsPlayed,
        )
    }

    private fun compareFork(
        gameIndex: Int,
        snapshot: MatchState,
        focal: TeamSide,
        splitIndexInTurn: Int,
        modelAction: LegalAction,
        advisorAction: LegalAction,
        modelStrategy: OpponentStrategy,
        advisorStrategy: OpponentStrategy,
        opponentStrategy: OpponentStrategy,
        diceSeed: Long,
    ): BlindSpotFinding {
        val (modelDice, advisorDice) = SharedDiceFactory.identicalPair(diceSeed)
        val modelPath = forkPlayer.playSplit(
            snapshot = snapshot,
            firstAction = modelAction,
            focal = focal,
            ownStrategy = modelStrategy,
            oppStrategy = opponentStrategy,
            dice = modelDice,
        )
        val advisorPath = forkPlayer.playSplit(
            snapshot = snapshot,
            firstAction = advisorAction,
            focal = focal,
            ownStrategy = advisorStrategy,
            oppStrategy = opponentStrategy,
            dice = advisorDice,
        )

        return BlindSpotFinding(
            gameIndex = gameIndex,
            liveTurnNumber = snapshot.progress.turnNumber,
            focal = focal,
            splitIndexInTurn = splitIndexInTurn,
            activeChallenges = formatActiveChallenges(snapshot.cards),
            modelAction = modelAction.brief(),
            advisorAction = advisorAction.brief(),
            modelOwnTurnActions = modelPath.ownTurnSteps.map { it.annotated() },
            advisorOwnTurnActions = advisorPath.ownTurnSteps.map { it.annotated() },
            modelPointsOwnTurn = pointsFor(modelPath.afterOwnTurn.progress, focal),
            advisorPointsOwnTurn = pointsFor(advisorPath.afterOwnTurn.progress, focal),
            modelDiffOwnTurn = differential(modelPath.afterOwnTurn.progress, focal),
            advisorDiffOwnTurn = differential(advisorPath.afterOwnTurn.progress, focal),
            modelDiffAfterOppTurn = differential(modelPath.afterOppTurn.progress, focal),
            advisorDiffAfterOppTurn = differential(advisorPath.afterOppTurn.progress, focal),
            modelScoringThroughOpp = modelPath.scoringSummaryThroughOpp(),
            advisorScoringThroughOpp = advisorPath.scoringSummaryThroughOpp(),
            diceSeed = diceSeed,
        )
    }

    private fun logFinding(f: BlindSpotFinding) {
        System.err.printf(
            "  INTERESTING g=%d turn=%d split=%d tags=%s | model=%s advisor=%s | " + "ownPts m=%d a=%d ownDiff m=%d a=%d | after-opp diff m=%d a=%d | dice=%d%n" + "    active: %s%n" + "    model turn: %s%n" + "    advisor turn: %s%n" + "    model scored through opp: %s%n" + "    advisor scored through opp: %s%n",
            f.gameIndex,
            f.liveTurnNumber,
            f.splitIndexInTurn,
            f.tags.joinToString(","),
            f.modelAction,
            f.advisorAction,
            f.modelPointsOwnTurn,
            f.advisorPointsOwnTurn,
            f.modelDiffOwnTurn,
            f.advisorDiffOwnTurn,
            f.modelDiffAfterOppTurn,
            f.advisorDiffAfterOppTurn,
            f.diceSeed,
            f.activeChallenges,
            f.modelOwnTurnActions.joinToString(" → "),
            f.advisorOwnTurnActions.joinToString(" → "),
            f.modelScoringThroughOpp,
            f.advisorScoringThroughOpp,
        )
    }

    private fun fatLookahead(thinkBudgetNanos: Long): LookaheadOpponentStrategy =
        LookaheadOpponentStrategy(
            heuristic = scriptedOpponentStrategy,
            search = lookaheadSearch,
            submitTiming = submitTiming,
            telemetry = LookaheadOpponentStrategy.SearchTelemetry(),
            thinkBudgetNanos = thinkBudgetNanos,
        )

    companion object {
        const val ADVISOR_THINK_NANOS = 1_000_000_000L
    }
}
