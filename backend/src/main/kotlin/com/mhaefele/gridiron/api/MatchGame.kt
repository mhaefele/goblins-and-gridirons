package com.mhaefele.gridiron.api

import com.mhaefele.gridiron.api.ai.training.TrainingScenarioTeamPicker
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.stream.MatchEventHub
import com.mhaefele.gridiron.game.MatchSetup
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.game.action.PlayerActionExecutor
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.LegalMovesPhaseTimer
import com.mhaefele.gridiron.game.opponent.ComputerCoachRegistry
import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.game.turn.TurnEngine
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import com.mhaefele.gridiron.spring.InMemoryDomainEventRepository
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistence boundary for state-changing match workflows: own a [MatchMutator] session, resolve
 * actions via [PlayerActionExecutor], turn the crank via [TurnEngine], then [persist] (events +
 * lastEventId + state + publish). Also the API façade for legal-move menus on real matches
 * ([legalActions] / [legalActionsForViewer]) — training / lookahead keep calling
 * [LegalMovesGenerator] directly (those forks mutate state without advancing [MatchState.lastEventId]).
 *
 * Per-match write lock serializes load→mutate→save so concurrent writers (human HTTP + computer
 * coach) cannot overwrite each other. SSE publish runs after the lock is released. Legal-move
 * reads do not take the lock (same as snapshot/roster): enumerate from an immutable loaded state.
 * Full menus are cached by `(matchId, lastEventId)` and dropped on every save/evict —
 * [lastEventId] stays in the key so unlocked concurrent readers of different snapshots cannot poison each other.
 * Memory stays bounded by live matches (and brief concurrent stale keys), not a wall-clock TTL.
 *
 * Regular games: [createMatch] → [startMatch]. Scenario games: [createScenarioMatch] only (already
 * opened on the first decision — do not call [startMatch]). When [MatchState.trainingEpisode] is
 * set, [submitActionOutcome] grades drill metadata onto the persisted state.
 */
@Component
class MatchGame(
    private val repository: InMemoryMatchRepository,
    private val eventRepository: InMemoryDomainEventRepository,
    private val dice: DiceRoller,
    private val playerActionExecutor: PlayerActionExecutor,
    private val legalMovesGenerator: LegalMovesGenerator,
    private val turnEngine: TurnEngine,
    private val eventHub: MatchEventHub,
    @Lazy private val computerCoachRegistry: ComputerCoachRegistry,
    @Lazy private val trainingScenarios: TrainingScenarios,
    @Value("\${gridiron.match.delete-grace-seconds:30}") private val deleteGraceSeconds: Long,
) {
    private val writeLocks = ConcurrentHashMap<MatchId, ReentrantLock>()
    private val evictionTasks = ConcurrentHashMap<MatchId, ScheduledFuture<*>>()
    private val legalMenuCache = ConcurrentHashMap<LegalMenuKey, List<LegalAction>>()
    private val evictionScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "match-eviction").apply { isDaemon = true }
        }

    /**
     * @param coachA when non-null, used instead of coin toss.
     * @param announceStart false when the caller will [logMatchStarted] after further setup (scenario open).
     */
    fun createMatch(
        homeTeamId: TeamCatalogId,
        awayTeamId: TeamCatalogId,
        pitchDefinitionId: PitchDefinitionId = PitchDefinitionId("default"),
        homeControlledBy: CoachControl = CoachControl.HUMAN,
        awayControlledBy: CoachControl = CoachControl.HUMAN,
        coachA: TeamSide? = null,
        announceStart: Boolean = true,
    ): MatchState {
        val state = MatchSetup.setupMatch(
            id = MatchId(UUID.randomUUID().toString()),
            coachA = coachA ?: coinToss(),
            homeTeam = TeamCatalog.team(homeTeamId),
            awayTeam = TeamCatalog.team(awayTeamId),
            pitchDefinitionId = pitchDefinitionId,
            pitch = PitchCatalog.definition(pitchDefinitionId),
            cards = CardState.deal(dice.shuffle(ChallengeCardCatalog.allCards())),
            homeControlledBy = homeControlledBy,
            awayControlledBy = awayControlledBy,
        )
        repository.save(state)
        invalidateLegalMenuCache(state.id)
        maybeScheduleTrainerTtl(state)
        if (announceStart) logMatchStarted(state)
        return state
    }

    /**
     * [createMatch] then spawn in-process computer coach(es) when [computerOpponent] is set and a seat
     * is [CoachControl.COMPUTER]. No-op spawn when both seats are non-COMPUTER.
     */
    fun createPreparedMatch(
        homeTeamId: TeamCatalogId,
        awayTeamId: TeamCatalogId,
        pitchDefinitionId: PitchDefinitionId = PitchDefinitionId("default"),
        homeControlledBy: CoachControl = CoachControl.HUMAN,
        awayControlledBy: CoachControl = CoachControl.HUMAN,
        coachA: TeamSide? = null,
        computerOpponent: ComputerOpponentKind? = ComputerOpponentKind.LOOKAHEAD,
        onnxProfile: String = OnnxPolicySession.DEFAULT_PROFILE.id,
    ): MatchState {
        val state = createMatch(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            pitchDefinitionId = pitchDefinitionId,
            homeControlledBy = homeControlledBy,
            awayControlledBy = awayControlledBy,
            coachA = coachA,
        )
        if (computerOpponent != null) computerCoachRegistry.spawnForMatch(state.id, computerOpponent, onnxProfile)
        return state
    }

    /** [createPreparedMatch] then [startMatch] — regular games opened on the first decision. */
    fun createAndStartMatch(
        homeTeamId: TeamCatalogId,
        awayTeamId: TeamCatalogId,
        pitchDefinitionId: PitchDefinitionId = PitchDefinitionId("default"),
        homeControlledBy: CoachControl = CoachControl.HUMAN,
        awayControlledBy: CoachControl = CoachControl.HUMAN,
        coachA: TeamSide? = null,
        computerOpponent: ComputerOpponentKind? = ComputerOpponentKind.LOOKAHEAD,
        onnxProfile: String = OnnxPolicySession.DEFAULT_PROFILE.id,
    ): ActionOutcome {
        val state = createPreparedMatch(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            pitchDefinitionId = pitchDefinitionId,
            homeControlledBy = homeControlledBy,
            awayControlledBy = awayControlledBy,
            coachA = coachA,
            computerOpponent = computerOpponent,
            onnxProfile = onnxProfile,
        )
        return startMatch(state.id)
    }

    /**
     * Create a curriculum scenario match already opened on the first trainer decision.
     * Does not use [startMatch] — board placement, crank, and optional held-check prime happen here.
     */
    fun createScenarioMatch(
        scenario: TrainingScenarioEnum,
        difficulty: Int,
        episodeMode: TrainingEpisodeMode,
        awayControlledBy: CoachControl,
        trainerSide: TeamSide = TeamSide.HOME,
        maxPlacementAttempts: Int = TrainingScenarios.MAX_PLACEMENT_ATTEMPTS,
    ): MatchState {
        val filter = trainingScenarios.implementation(scenario).trainerTeamFilter
        return createScenarioMatch(
            homeTeamId = TrainingScenarioTeamPicker.pickTrainerTeamId(filter),
            awayTeamId = TeamCatalog.allTeams().random().id,
            homeControlledBy = CoachControl.TRAINER,
            awayControlledBy = awayControlledBy,
            trainerSide = trainerSide,
            scenario = scenario,
            difficulty = difficulty,
            episodeMode = episodeMode,
            maxPlacementAttempts = maxPlacementAttempts,
        )
    }

    fun createScenarioMatch(
        homeTeamId: TeamCatalogId,
        awayTeamId: TeamCatalogId,
        pitchDefinitionId: PitchDefinitionId = PitchDefinitionId("default"),
        homeControlledBy: CoachControl,
        awayControlledBy: CoachControl,
        trainerSide: TeamSide,
        scenario: TrainingScenarioEnum,
        difficulty: Int = 0,
        episodeMode: TrainingEpisodeMode = TrainingEpisodeMode.TRAIN,
        maxPlacementAttempts: Int = TrainingScenarios.MAX_PLACEMENT_ATTEMPTS,
    ): MatchState {
        val blank = createMatch(
            homeTeamId = homeTeamId,
            awayTeamId = awayTeamId,
            pitchDefinitionId = pitchDefinitionId,
            homeControlledBy = homeControlledBy,
            awayControlledBy = awayControlledBy,
            coachA = trainerSide,
            announceStart = false,
        )
        val opened = trainingScenarios.openScenario(blank, trainerSide, scenario, difficulty, episodeMode, maxPlacementAttempts)
        repository.save(opened)
        invalidateLegalMenuCache(opened.id)
        logMatchStarted(opened)
        return opened
    }

    private fun logMatchStarted(state: MatchState) {
        val episode = state.trainingEpisode
        if (episode == null) {
            log.info(
                "Match started id={} home={} ({}) away={} ({})",
                state.id.value.take(8),
                state.config.homeTeamId.value,
                state.config.homeControlledBy,
                state.config.awayTeamId.value,
                state.config.awayControlledBy,
            )
        } else {
            log.info(
                "Match started id={} home={} ({}) away={} ({}) scenario={} (d{})",
                state.id.value.take(8),
                state.config.homeTeamId.value,
                state.config.homeControlledBy,
                state.config.awayTeamId.value,
                state.config.awayControlledBy,
                episode.scenario,
                episode.difficulty,
            )
        }
    }

    /**
     * Toss **loser** is Coach A (sets up first, acts first). Toss winner chooses pitch
     * (client [pitchDefinitionId] is the product stand-in). D6 1–3 HOME wins → AWAY is Coach A;
     * 4–6 AWAY wins → HOME is Coach A.
     */
    private fun coinToss(): TeamSide =
        if (dice.d6() <= 3) TeamSide.AWAY else TeamSide.HOME

    /** Turn the crank until player input is required (or the match ends). Regular games only. */
    fun startMatch(matchId: MatchId): ActionOutcome =
        mutateAndPublish(matchId) { session ->
            turnEngine.advance(session)
        }

    /** Acting coach's legal menu for GUI / API — empty menu is an invariant break ([LegalMovesGenerator.forActingCoach]). */
    fun legalActions(matchId: MatchId): List<LegalAction> =
        legalActions(repository.findById(matchId))

    /**
     * Acting coach's legal menu for an already-loaded persisted snapshot.
     * Cached under `(matchId, lastEventId)` until the next save/evict — not for lookahead forks.
     */
    fun legalActions(
        state: MatchState,
        timer: LegalMovesPhaseTimer = LegalMovesPhaseTimer.NOOP,
    ): List<LegalAction> =
        legalMenuCache.computeIfAbsent(LegalMenuKey(state.id, state.lastEventId)) { legalMovesGenerator.forActingCoach(state, timer) }

    /**
     * Legal menu as seen by [viewerSeat]: empty when the match is over or that seat is not acting.
     * Soft-empty belongs here (external viewer), not on [LegalMovesGenerator].
     */
    fun legalActionsForViewer(state: MatchState, viewerSeat: TeamSide): List<LegalAction> {
        if (state.isGameEnded()) return emptyList()
        if (viewerSeat != state.actingSeat()) return emptyList()
        return legalActions(state)
    }

    /** Resolve [legalActionId] from the cached full menu for this persisted snapshot. */
    fun legalAction(state: MatchState, legalActionId: LegalActionId): LegalAction {
        val matches = legalActions(state).filter { it.id == legalActionId }
        require(matches.isNotEmpty()) { "No legal action with id $legalActionId" }
        require(matches.size == 1) { "Multiple legal actions with id $legalActionId" }
        return matches.single()
    }

    fun submitActionOutcome(matchId: MatchId, legalActionId: LegalActionId): ActionOutcome =
        mutateAndPublish(matchId) { session ->
            val before = session.toMatchState()
            val chosen = legalAction(before, legalActionId)
            playerActionExecutor.takeAction(session, chosen)
            turnEngine.advance(session)
            applyTrainingDrillGrade(session, before, chosen)
        }

    /** Caller already holds the legal row (bot / dump) — skip menu lookup. */
    fun submitActionOutcome(matchId: MatchId, legalAction: LegalAction): ActionOutcome =
        mutateAndPublish(matchId) { session ->
            val before = session.toMatchState()
            playerActionExecutor.takeAction(session, legalAction)
            turnEngine.advance(session)
            applyTrainingDrillGrade(session, before, legalAction)
        }

    /**
     * Load → mutate → append events → save under the per-match lock; publish after unlock so
     * listeners (e.g. computer coach) never re-enter while holding the write lock.
     */
    private fun mutateAndPublish(matchId: MatchId, mutate: (MatchMutator) -> Unit): ActionOutcome {
        val outcome = writeLock(matchId).withLock {
            val session = MatchMutator(repository.findById(matchId))
            mutate(session)
            persist(session)
        }
        eventHub.publish(matchId, outcome.events)
        return outcome
    }

    private fun applyTrainingDrillGrade(session: MatchMutator, before: MatchState, action: LegalAction) {
        if (session.trainingEpisode == null) return
        session.replaceTrainingEpisode(trainingScenarios.updatedTrainingEpisode(before, session.toMatchState(), action))
    }

    private fun writeLock(matchId: MatchId): ReentrantLock =
        writeLocks.computeIfAbsent(matchId) { ReentrantLock() }

    /** Append events, stamp lastEventId on the session, save state — caller publishes. */
    private fun persist(session: MatchMutator): ActionOutcome {
        val matchEnded = session.events.filterIsInstance<DomainEvent.MatchEnded>().singleOrNull()
        val storedEvents = eventRepository.append(session.matchId, session.events)
        session.setLastEventId(storedEvents.lastOrNull()?.id?.domainId ?: session.lastEventId)
        val state = session.toMatchState()
        repository.save(state)
        invalidateLegalMenuCache(state.id)
        if (matchEnded != null) {
            log.info(
                "Match complete matchId={} home={} ({}) away={} ({}) score={}-{} endKind={} winner={}",
                state.id.value,
                state.config.homeTeamId.value,
                TeamCatalog.team(state.config.homeTeamId).name,
                state.config.awayTeamId.value,
                TeamCatalog.team(state.config.awayTeamId).name,
                state.progress.homeScore.points,
                state.progress.awayScore.points,
                matchEnded.endKind,
                matchEnded.winner,
            )
            scheduleEviction(state.id, POST_END_EVICT_MINUTES, TimeUnit.MINUTES)
        }
        return ActionOutcome(state, storedEvents)
    }

    /** Non-human matches: TTL starts when the match is first persisted. */
    private fun maybeScheduleTrainerTtl(state: MatchState) {
        val hasHuman = state.config.homeControlledBy == CoachControl.HUMAN || state.config.awayControlledBy == CoachControl.HUMAN
        if (!hasHuman) scheduleEviction(state.id, TRAINER_MATCH_TTL_MINUTES, TimeUnit.MINUTES)
    }

    /** Replace any pending eviction for [matchId] — GETs do not refresh TTL. */
    private fun scheduleEviction(matchId: MatchId, delay: Long, unit: TimeUnit) {
        evictionTasks.compute(matchId) { _, existing ->
            existing?.cancel(false)
            evictionScheduler.schedule(
                {
                    evictionTasks.remove(matchId)
                    evict(matchId)
                },
                delay,
                unit,
            )
        }
    }

    /** Stop coaches now; wipe state after a grace period so in-flight turns can finish. Idempotent. */
    fun deleteMatch(matchId: MatchId) {
        computerCoachRegistry.stopForMatch(matchId)
        scheduleEviction(matchId, deleteGraceSeconds, TimeUnit.SECONDS)
    }

    private fun evict(matchId: MatchId) {
        evictionTasks.remove(matchId)?.cancel(false)
        computerCoachRegistry.stopForMatch(matchId)
        writeLock(matchId).withLock {
            repository.delete(matchId)
            eventRepository.delete(matchId)
        }
        writeLocks.remove(matchId)
        invalidateLegalMenuCache(matchId)
        eventHub.drop(matchId)
    }

    private fun invalidateLegalMenuCache(matchId: MatchId) {
        legalMenuCache.keys.removeIf { it.matchId == matchId }
    }

    @PreDestroy
    fun shutdown() { evictionScheduler.shutdownNow() }

    companion object {
        private val log = LoggerFactory.getLogger(MatchGame::class.java)
        private const val TRAINER_MATCH_TTL_MINUTES = 30L
        private const val POST_END_EVICT_MINUTES = 1L
    }
}

private data class LegalMenuKey(val matchId: MatchId, val lastEventId: Int)

/** Result of resolving one player action — new state plus ordered stored replay/audit events. */
data class ActionOutcome(
    val newState: MatchState,
    val events: List<StoredDomainEvent> = emptyList(),
)
