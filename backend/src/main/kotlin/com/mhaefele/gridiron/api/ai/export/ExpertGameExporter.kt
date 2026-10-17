package com.mhaefele.gridiron.api.ai.export

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.ai.training.scenarioCoachTurnWindow
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.game.opponent.onnx.OnnxOpponentFactory
import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.game.opponent.scripted.ScriptedOpponentStrategy
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import com.mhaefele.gridiron.spring.InMemoryDomainEventRepository
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Play bot-vs-bot games on [MatchGame] (no HTTP, no [com.mhaefele.gridiron.game.opponent.ComputerCoachAgent])
 * and write JSONL via an [ExportStepRecorder] (e.g. gzip [ExpertStepRecord] through [ExpertExportStepRecorder]).
 * Uses the Spring [LookaheadOpponentStrategy] / [ScriptedOpponentStrategy] singletons — same beans as
 * the computer coach. Search already allocates a fresh scripted roller per `evaluate()`,
 * so concurrent games do not share dice queues. Both seats are [CoachControl.TRAINER] (external
 * chooser — no in-process agent, Enforcer auto-sorts) so Lookahead never parks on
 * `HANDLE_ENFORCER_ORDER` and uses the 100ms AI-vs-AI think budget.
 *
 * Expert schedule repeats in blocks: [SCENARIO_PASSES_PER_BLOCK] passes of every [TrainingScenarioEnum]
 * at each difficulty 0–5, then [REGULAR_GAMES_PER_BLOCK] regular kickoff games. Each scenario type
 * gets the same drill count per block. Scale `--games` with [exportGamesPreservingLegacyBoardCoverage]
 * when the enum grows so board-drill totals match a pre-claim dump.
 * Scenario games truncate after [scenarioCoachTurnWindow] coach turns (same window as PPO training).
 * Scenario slots retry until Lookahead grades [TrainingDrillStatus.PASS] ([MAX_SCENARIO_PLAY_ATTEMPTS]
 * play attempts per scheduled game); non-PASS lines are never written. Export fails at the end if any
 * slot exhausts retries (strict). Chosen actions are always real Lookahead picks — expert advice.
 * When Lookahead disagrees with a scenario's expected move, fix the scenario (usually wrong), not the label.
 */
@Component
class ExpertGameExporter(
    private val matchGame: MatchGame,
    private val repository: InMemoryMatchRepository,
    private val eventRepository: InMemoryDomainEventRepository,
    private val trainingScenarios: TrainingScenarios,
    private val heuristic: ScriptedOpponentStrategy,
    private val lookahead: LookaheadOpponentStrategy,
    private val onnxFactory: OnnxOpponentFactory,
    private val legalMovesGenerator: LegalMovesGenerator,
    private val jsonMapper: JsonMapper,
) {
    data class Result(val gamesOk: Int, val gamesSkipped: Int, val gamesFailed: Int, val steps: Int)

    fun <T> export(request: ExpertExportRequest, recorder: ExportStepRecorder<T>): Result {
        request.out.parent?.let { Files.createDirectories(it) }
        val pairs = matchupPairs(request)
        if (request.homeTeamId == null && request.awayTeamId == null && request.games % pairs.size != 0) {
            log.info("expert-export games={} is not a multiple of {} directed cross-team pairs; some matchups get an extra game this pass", request.games, pairs.size)
        }
        val done = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val steps = AtomicInteger(0)
        val failures = ConcurrentLinkedQueue<Pair<Int, String>>()
        val writeLock = Any()
        val pool = Executors.newFixedThreadPool(request.threads.coerceAtMost(request.games))
        log.info("expert-export games={} threads={} home={} away={} block={}×({}scenario×{}diff)+{} regular out={}", request.games, request.threads, request.homeKind, request.awayKind, SCENARIO_PASSES_PER_BLOCK, TrainingScenarioEnum.entries.size, DIFFICULTY_LEVELS, REGULAR_GAMES_PER_BLOCK, request.out.toAbsolutePath())
        recorder.openWriter(request).use { out ->
            try {
                val tasks = List(request.games) { index ->
                    pool.submit<Int> {
                        try {
                            val (homeTeam, awayTeam) = pairs[index % pairs.size]
                            val slot = scenarioSlotForGameIndex(index)
                            when (val outcome = playGame(index, homeTeam, awayTeam, request.homeKind, request.awayKind, request.maxActions, slot, recorder)) {
                                PlayGameOutcome.Skipped -> {
                                    skipped.incrementAndGet()
                                    val scenarioTag = slot?.let { " scenario=${it.scenario.name} difficulty=${it.difficulty}" } ?: ""
                                    log.info(
                                        "expert-export game {}/{} skipped {} vs {}{} (trainer roster unsupported)",
                                        done.get() + skipped.get() + failed.get(),
                                        request.games,
                                        homeTeam.id,
                                        awayTeam.id,
                                        scenarioTag,
                                    )
                                    0
                                }
                                is PlayGameOutcome.Ok   -> {
                                    val records = outcome.records
                                    val lines = records.map { jsonMapper.writeValueAsString(it) }
                                    synchronized(writeLock) {
                                        for (line in lines) {
                                            out.write(line)
                                            out.newLine()
                                        }
                                        out.flush()
                                    }
                                    done.incrementAndGet()
                                    val nSteps = steps.addAndGet(records.size)
                                    val scenarioTag = slot?.let { " scenario=${it.scenario.name} difficulty=${it.difficulty}" } ?: " regular"
                                    log.info(
                                        "expert-export game {}/{} ok {} vs {}{} steps={} totalSteps={}",
                                        done.get() + skipped.get() + failed.get(),
                                        request.games,
                                        homeTeam.id,
                                        awayTeam.id,
                                        scenarioTag,
                                        records.size,
                                        nSteps,
                                    )
                                    records.size
                                }
                            }
                        } catch (ex: ScenarioDrillExportException) {
                            failed.incrementAndGet()
                            failures += (index + 1) to ex.summary
                            log.warn("expert-export game {} scenario drill not passed: {}", index + 1, ex.summary)
                            0
                        } catch (ex: Exception) {
                            failed.incrementAndGet()
                            failures += (index + 1) to "${ex.javaClass.simpleName}: ${ex.message}"
                            log.error("expert-export game {} failed", index + 1, ex)
                            0
                        }
                    }
                }
                tasks.forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }
        }
        val result = Result(gamesOk = done.get(), gamesSkipped = skipped.get(), gamesFailed = failed.get(), steps = steps.get())
        log.info(
            "expert-export done ok={} skipped={} failed={} steps={} out={}",
            result.gamesOk,
            result.gamesSkipped,
            result.gamesFailed,
            result.steps,
            request.out.toAbsolutePath(),
        )
        if (failures.isNotEmpty()) {
            log.error("expert-export drill / play failures ({} games):", failures.size)
            for ((gameIndex, summary) in failures.sortedBy { it.first }) {
                log.error("  game {}: {}", gameIndex, summary)
            }
        }
        check(result.gamesFailed == 0) { "expert-export: ${result.gamesFailed} of ${request.games} games failed (wrote ${result.steps} steps to ${request.out}):\n" + failures.sortedBy { it.first }.joinToString("\n") { "  game ${it.first}: ${it.second}" } }
        return result
    }

    private sealed interface PlayGameOutcome<out T> {
        data class Ok<T>(val records: List<T>) : PlayGameOutcome<T>
        data object Skipped : PlayGameOutcome<Nothing>
    }

    private fun <T> playGame(
        gameIndex: Int,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        homeKind: ComputerOpponentKind,
        awayKind: ComputerOpponentKind,
        maxActions: Int,
        slot: ScenarioSlot?,
        recorder: ExportStepRecorder<T>,
    ): PlayGameOutcome<T> =
        if (slot == null) {
            playRegularGame(homeTeam, awayTeam, homeKind, awayKind, maxActions, recorder)
        } else {
            playScenarioGame(gameIndex, homeTeam, awayTeam, homeKind, awayKind, maxActions, slot, recorder)
        }

    private fun <T> playRegularGame(
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        homeKind: ComputerOpponentKind,
        awayKind: ComputerOpponentKind,
        maxActions: Int,
        recorder: ExportStepRecorder<T>,
    ): PlayGameOutcome<T> {
        var state = matchGame.createMatch(
            homeTeamId = homeTeam.id,
            awayTeamId = awayTeam.id,
            homeControlledBy = CoachControl.TRAINER,
            awayControlledBy = CoachControl.TRAINER,
        )
        try {
            state = matchGame.startMatch(state.id).newState
            return PlayGameOutcome.Ok(
                playUntilDone(
                    state = state,
                    homeStrategy = strategy(homeKind),
                    awayStrategy = strategy(awayKind),
                    maxActions = maxActions,
                    truncateAfterTurn = null,
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    slot = null,
                    recorder = recorder,
                ).records,
            )
        } finally {
            repository.delete(state.id)
            eventRepository.delete(state.id)
        }
    }

    private fun <T> playScenarioGame(
        gameIndex: Int,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        homeKind: ComputerOpponentKind,
        awayKind: ComputerOpponentKind,
        maxActions: Int,
        slot: ScenarioSlot,
        recorder: ExportStepRecorder<T>,
    ): PlayGameOutcome<T> {
        val filter = trainingScenarios.implementation(slot.scenario).trainerTeamFilter
        if (!filter(homeTeam)) return PlayGameOutcome.Skipped
        var lastDrillStatus = TrainingDrillStatus.STILL_IN_PROGRESS
        repeat(MAX_SCENARIO_PLAY_ATTEMPTS) { attempt ->
            val state = matchGame.createScenarioMatch(
                homeTeamId = homeTeam.id,
                awayTeamId = awayTeam.id,
                homeControlledBy = CoachControl.TRAINER,
                awayControlledBy = CoachControl.TRAINER,
                trainerSide = TeamSide.HOME,
                scenario = slot.scenario,
                difficulty = slot.difficulty,
                episodeMode = TrainingEpisodeMode.EVAL,
            )
            try {
                val truncateAfterTurn = state.progress.turnNumber + scenarioCoachTurnWindow(slot.difficulty)
                val played = playUntilDone(
                    state = state,
                    homeStrategy = strategy(homeKind),
                    awayStrategy = strategy(awayKind),
                    maxActions = maxActions,
                    truncateAfterTurn = truncateAfterTurn,
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    slot = slot,
                    recorder = recorder,
                )
                lastDrillStatus = played.finalState.trainingEpisode!!.drillStatus
                if (lastDrillStatus == TrainingDrillStatus.PASS) {
                    return PlayGameOutcome.Ok(played.records)
                }
                log.info(
                    "expert-export game {} scenario={} difficulty={} {} vs {} play attempt {}/{} ended drillStatus={} — retry",
                    gameIndex + 1,
                    slot.scenario.name,
                    slot.difficulty,
                    homeTeam.id.value,
                    awayTeam.id.value,
                    attempt + 1,
                    MAX_SCENARIO_PLAY_ATTEMPTS,
                    lastDrillStatus,
                )
            } finally {
                repository.delete(state.id)
                eventRepository.delete(state.id)
            }
        }
        throw ScenarioDrillExportException(
            gameIndex = gameIndex,
            slot = slot,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            attempts = MAX_SCENARIO_PLAY_ATTEMPTS,
            lastDrillStatus = lastDrillStatus,
        )
    }

    private data class PlayUntilDoneResult<T>(val records: List<T>, val finalState: MatchState)

    private fun <T> playUntilDone(
        state: MatchState,
        homeStrategy: OpponentStrategy,
        awayStrategy: OpponentStrategy,
        maxActions: Int,
        truncateAfterTurn: Int?,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        slot: ScenarioSlot?,
        recorder: ExportStepRecorder<T>,
    ): PlayUntilDoneResult<T> {
        var current = state
        val records = ArrayList<T>(64)
        var actions = 0
        while (!current.isGameEnded() && actions < maxActions) {
            // TODO(pending): export loop assumes pending always non-null between submits — same settle
            // contract as LookaheadSearch; should share one "must have coach-facing pending" helper.
            check(current.currentTurnState.pendingDecision != null) { "expected a pending decision after the last submit matchId=${current.id.value}" }
            val pending = checkNotNull(current.currentTurnState.pendingDecision)
            val acting = pending.respondingCoach ?: current.currentTurnState.activeCoach
            val legal = legalMovesGenerator.forActingCoach(current)
            val strategyForSeat = if (acting == TeamSide.HOME) homeStrategy else awayStrategy
            val chosen = strategyForSeat.chooseAction(current, legal)
            val outcome = matchGame.submitActionOutcome(current.id, chosen)
            records.add(recorder.recordStep(current, legal, chosen, acting, outcome.events))
            current = outcome.newState
            actions++
            if (slot != null && current.trainingEpisode?.drillStatus == TrainingDrillStatus.DISTRACTED) break
            if (truncateAfterTurn != null && current.progress.turnNumber >= truncateAfterTurn) break
        }
        if (!current.isGameEnded() && (truncateAfterTurn == null || current.progress.turnNumber < truncateAfterTurn)) {
            log.warn(
                "expert-export truncated after {} actions {} vs {}{}",
                maxActions,
                homeTeam.id,
                awayTeam.id,
                slot?.let { " scenario=${it.scenario.name} difficulty=${it.difficulty}" }.orEmpty(),
            )
        }
        return PlayUntilDoneResult(records, current)
    }

    private fun strategy(kind: ComputerOpponentKind): OpponentStrategy =
        when (kind) {
            ComputerOpponentKind.HEURISTIC -> heuristic
            ComputerOpponentKind.LOOKAHEAD -> lookahead
            ComputerOpponentKind.ONNX      -> onnxFactory.forProfile(OnnxPolicySession.DEFAULT_PROFILE)
        }

    private fun matchupPairs(request: ExpertExportRequest): List<Pair<TeamCard, TeamCard>> {
        val homePinned = request.homeTeamId?.let { TeamCatalog.team(it) }
        val awayPinned = request.awayTeamId?.let { TeamCatalog.team(it) }
        if (homePinned != null || awayPinned != null) {
            val home = homePinned ?: TeamCatalog.team("human")
            val away = awayPinned ?: home
            return listOf(home to away)
        }
        val teams = TeamCatalog.allTeams()
        val pairs = ArrayList<Pair<TeamCard, TeamCard>>(teams.size * (teams.size - 1))
        for (home in teams) {
            for (away in teams) {
                if (away.id == home.id) continue
                pairs.add(home to away)
            }
        }
        return pairs
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExpertGameExporter::class.java)

        /** Difficulties covered evenly in each block (matches scenario API 0–5). */
        const val DIFFICULTY_LEVELS = 6

        /** Board-drill count in [TrainingScenarioEnum] before claim scenarios joined the export enum. */
        const val LEGACY_BOARD_SCENARIO_COUNT = 6

        /** How many times each block repeats the full scenario×difficulty set before regulars. */
        const val SCENARIO_PASSES_PER_BLOCK = 10

        /** Full-game samples after [SCENARIO_PASSES_PER_BLOCK] passes of every scenario×difficulty. */
        const val REGULAR_GAMES_PER_BLOCK = 10

        /** Lookahead play attempts per scheduled scenario slot before strict failure. */
        const val MAX_SCENARIO_PLAY_ATTEMPTS = 16

        val scenarioPassSize: Int
            get() = TrainingScenarioEnum.entries.size * DIFFICULTY_LEVELS

        val scenarioSlotsPerBlock: Int
            get() = scenarioPassSize * SCENARIO_PASSES_PER_BLOCK

        val legacyBlockSize: Int
            get() = LEGACY_BOARD_SCENARIO_COUNT * DIFFICULTY_LEVELS * SCENARIO_PASSES_PER_BLOCK + REGULAR_GAMES_PER_BLOCK

        val blockSize: Int
            get() = scenarioSlotsPerBlock + REGULAR_GAMES_PER_BLOCK

        /** Scale total `--games` so board-drill samples match a pre-claim dump of [legacyGames]. */
        fun exportGamesPreservingLegacyBoardCoverage(legacyGames: Int = 31_000): Int =
            (legacyGames.toLong() * blockSize / legacyBlockSize).toInt()

        /**
         * [SCENARIO_PASSES_PER_BLOCK] repeats of difficulty-major order (all scenarios at d0, then d1, … d5),
         * then [REGULAR_GAMES_PER_BLOCK] regulars. Each scenario at each difficulty appears exactly
         * [SCENARIO_PASSES_PER_BLOCK] times per block.
         */
        fun scenarioSlotForGameIndex(index: Int): ScenarioSlot? {
            val posInBlock = index % blockSize
            if (posInBlock >= scenarioSlotsPerBlock) return null
            val posInPass = posInBlock % scenarioPassSize
            val difficulty = posInPass / TrainingScenarioEnum.entries.size
            val scenario = TrainingScenarioEnum.entries[posInPass % TrainingScenarioEnum.entries.size]
            return ScenarioSlot(scenario, difficulty)
        }
    }

    /** One expert-export scenario game: type + reserve scatter difficulty (both sides). */
    data class ScenarioSlot(val scenario: TrainingScenarioEnum, val difficulty: Int)

    /** Scenario slot exhausted [MAX_SCENARIO_PLAY_ATTEMPTS] without [TrainingDrillStatus.PASS]. */
    class ScenarioDrillExportException(
        gameIndex: Int,
        slot: ScenarioSlot,
        homeTeam: TeamCard,
        awayTeam: TeamCard,
        attempts: Int,
        lastDrillStatus: TrainingDrillStatus,
    ) : RuntimeException(
        "gameIndex=${gameIndex + 1} scenario=${slot.scenario.name} difficulty=${slot.difficulty} home=${homeTeam.id.value} away=${awayTeam.id.value} attempts=$attempts lastDrillStatus=$lastDrillStatus",
    ) { val summary: String = "scenario=${slot.scenario.name} difficulty=${slot.difficulty} home=${homeTeam.id.value} away=${awayTeam.id.value} attempts=$attempts lastDrillStatus=$lastDrillStatus" }
}
