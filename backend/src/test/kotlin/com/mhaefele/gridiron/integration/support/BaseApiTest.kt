package com.mhaefele.gridiron.integration.support

import com.mhaefele.gridiron.api.ai.training.CreateScenarioMatchRequest
import com.mhaefele.gridiron.api.dto.response.CreateMatchResponse
import com.mhaefele.gridiron.api.dto.response.LegalAction

import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.scenario.formatDomainEvent
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import com.mhaefele.gridiron.spring.InMemoryDomainEventRepository
import com.mhaefele.gridiron.support.MatchBootstrap
import com.mhaefele.gridiron.support.MatchBuilder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.HttpStatusCodeException

/**
 * Sole `@SpringBootTest` parent for the suite — one cached context (RANDOM_PORT + `test` profile).
 *
 * Subclasses must not redeclare `@SpringBootTest` / `@ActiveProfiles` / competing `@Import`s.
 * Pure domain tests (pathfinding, marking, topology, dice math) stay Spring-free.
 *
 * Dice: [ScriptedDiceRoller] with `fallback-to-default-pattern=false` via `application-test.yml`.
 *
 * Feature behavior is exercised over HTTP ([GridironApiClient], SSE, match seeding) —
 * do not `@Autowired` orchestrators (`TurnEngine`, `PlayerActionExecutor`, `RegularPlayerActionResolver`, …)
 * to exercise product rules.
 *
 * Prefer [match] for mid-game fixtures — returns a [MatchApi] so tests never thread [MatchId].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MatchBootstrap::class, ApiTestConfiguration::class)
abstract class BaseApiTest {
    lateinit var matchId: MatchId

    @Autowired
    protected lateinit var dice: ScriptedDiceRoller

    @Autowired
    protected lateinit var api: GridironApiClient

    @Autowired
    protected lateinit var sse: SseTestClient

    @Autowired
    private lateinit var matchBootstrap: MatchBootstrap

    @Autowired
    private lateinit var eventRepository: InMemoryDomainEventRepository

    @BeforeEach
    fun resetDeterministicDice() {
        dice.reset()
    }

    @JvmField
    @RegisterExtension
    final val watcher = object : TestWatcher {
        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            if (::matchId.isInitialized) {
                printDomainEvents(this@BaseApiTest.matchId)
            }
        }
    }

    fun printDomainEvents(id: MatchId) {
        System.out.println(getDomainEventsString(id))
    }

    fun getDomainEventsString(id: MatchId): String {
        return "Events:\n  " +
               eventRepository.findByMatchId(id).joinToString("\n  ") { formatDomainEvent(it.event) }
    }

    /** Build, seed, and wrap a mid-game match — override only what the test cares about. */
    protected fun match(configure: MatchBuilder.() -> Unit = {}): MatchApi {
        val state = MatchBuilder().apply(configure).toMatchState()
        matchId = matchBootstrap.seedMatch(state)
        return MatchApi(api, matchId)
    }

    protected fun seedMatch(state: MatchState): MatchId = matchBootstrap.seedMatch(state)

    protected fun seedMatch(
        board: Board,
        actionsRemaining: Int = 3,
        activeCoach: TeamSide = TeamSide.HOME,
    ): MatchId = matchBootstrap.seedMatch(board, actionsRemaining, activeCoach)

    protected fun loadMatch(matchId: MatchId): MatchState = matchBootstrap.getMatch(matchId)

    protected fun loadMatch(match: CreateMatchResponse): MatchState = loadMatch(match.matchId)

    protected fun loadMatch(match: MatchApi): MatchState = loadMatch(match.matchId)

    protected fun assertBadRequest(block: () -> Unit) {
        assertThatThrownBy(block)
            .isInstanceOf(HttpStatusCodeException::class.java)
            .extracting { (it as HttpStatusCodeException).statusCode }
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    fun placeAllInitialPieces(match: CreateMatchResponse) = placeAllInitialPieces(match.matchId)

    fun placeAllInitialPieces(matchId: MatchId) {
        var legalActions = api.legalActions(matchId)
        while (legalActions.map { it.actionType }.contains(ActionType.RESERVES)) {
            api.play(matchId, legalActions.first())
            legalActions = api.legalActions(matchId)
        }
    }

    /**
     * When Pre-Turn Refresh finds a full tableau, [LegalAction.ReplaceChallenge] pauses until the
     * coach picks a slot. Tests that only care about the rest of Pre-Turn / PLAYER_ACTION call this.
     */
    protected fun resolveForcedChallengeReplaceIfPresent(matchId: MatchId) {
        val replace = api.legalActions(matchId, ActionType.REPLACE_CHALLENGE).firstOrNull()
            ?: return
        api.play(matchId, replace)
    }

    fun playBonusCard(match: MatchApi, bonusPlay: BonusPlay) {
        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(bonusPlay)

        match.play(plays.single())
    }

    /** Full slim scenario create body — every field required by the API (no server defaults). */
    protected fun scenarioMatchRequest(
        scenario: TrainingScenarioEnum,
        difficulty: Int = 0,
        episodeMode: TrainingEpisodeMode = TrainingEpisodeMode.TRAIN,
        awayControlledBy: CoachControl = CoachControl.COMPUTER,
        computerOpponent: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD,
    ) = CreateScenarioMatchRequest(
        scenario = scenario,
        difficulty = difficulty,
        episodeMode = episodeMode,
        awayControlledBy = awayControlledBy,
        computerOpponent = computerOpponent,
    )

    /**
     * Re-run [block] with fresh live dice when gameplay RNG derails a scenario playthrough.
     * Fixture / create tests should not need this — only multi-step paths after random rolls.
     */
    protected fun withRandomDiceRetries(attempts: Int = RANDOM_DICE_RETRIES, block: () -> Unit) {
        var last: Throwable? = null
        repeat(attempts) {
            dice.usePassThrough()
            try {
                block()
                return
            } catch (e: Exception) {
                last = e
            } catch (e: AssertionError) {
                // assertThat failures are Errors — still retry playthrough flakes.
                last = e
            }
        }
        throw AssertionError("failed after $attempts random-dice attempts", last)
    }

    protected companion object {
        /** Default retries for multi-step scenario playthroughs after random rolls. */
        const val RANDOM_DICE_RETRIES = 16

        /** Stricter budget — placement should succeed well before the default cap. */
        const val STRICT_DICE_RETRIES = RANDOM_DICE_RETRIES / 2
    }
}
