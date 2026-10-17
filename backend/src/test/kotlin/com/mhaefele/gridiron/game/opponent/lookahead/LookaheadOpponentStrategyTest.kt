package com.mhaefele.gridiron.game.opponent.lookahead

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.DiceRollerContext
import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class LookaheadOpponentStrategyTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Autowired
    private lateinit var strategy: LookaheadOpponentStrategy

    @Autowired
    private lateinit var search: LookaheadSearch

    @Autowired
    private lateinit var diceRollerContext: DiceRollerContext

    @Test
    fun `search scores run onto loose ball above run away from ball`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2))
        scenario.addLooseBall(Coordinate(5, 7))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val legal = legalMovesGenerator.forActingCoach(state)

        val ontoBall = legal.filterIsInstance<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 7) }
        val away = legal.filterIsInstance<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 5) }

        val ranked = search.evaluate(state, listOf(ontoBall, away))

        assertThat(ranked.single { it.action == ontoBall }.score)
            .isGreaterThan(ranked.single { it.action == away }.score)
    }

    @Test
    fun `prefers touchdown run over aimless teammate run`() {
        val scenario = GameScenario.board()
        val carrier = scenario.holdingBall(scenario.addPlayer(Coordinate(5, 14), profile = TestProfiles.withMove(2)))
        val distractor = scenario.addPlayer(Coordinate(3, 6), profile = TestProfiles.withMove(3))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)

        val chosen = strategy.chooseAction(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Run::class.java)
        val run = chosen as LegalAction.Run
        assertThat(run.playerId).isEqualTo(carrier.player.id)
        assertThat(run.playerId).isNotEqualTo(distractor.player.id)
        val end = state.board.squareAt(run.path.squares.last())
        assertThat(end.isEndZone(TeamSide.AWAY)).isTrue()
    }

    @Test
    fun `prefers claim-annotated ball pickup when Grab It is active`() {
        val grabIt = ChallengeCardCatalog.card(1)
        val rest = ChallengeCardCatalog.allCards().filter { it.id != grabIt.id }
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2))
        scenario.addPlayer(Coordinate(8, 8), profile = TestProfiles.withMove(2))
        scenario.addLooseBall(Coordinate(5, 7))
        val state = matchState(
            board = scenario.board,
            activeCoach = TeamSide.HOME,
            cards = CardState(activeSlots = listOf(grabIt, null, null), deck = rest),
        ).withTurnNumber(2)

        val chosen = strategy.chooseAction(state, legalMovesGenerator.forActingCoach(state))

        assertThat(chosen).isInstanceOf(LegalAction.Run::class.java)
        assertThat((chosen as LegalAction.Run).path.squares.last()).isEqualTo(Coordinate(5, 7))
        assertThat(chosen.potentialChallengeClaims).isNotEmpty()
    }

    @Test
    fun `scripted dice dry-run settles a block without touching live rng`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2))
        scenario.addPlayer(Coordinate(6, 6), team = TeamSide.AWAY)
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val block = legalMovesGenerator.forActingCoach(state).filterIsInstance<LegalAction.Block>().single()

        val overlay = ScriptedDiceRoller(fallbackToDefaultPattern = true).also {
            it.queueBlockDice(BlockDieFace.SMASH)
            it.queueD6(6)
        }
        val next = diceRollerContext.withRoller(overlay) { search.applyAndSettle(state, block) }

        assertThat(next.board.players.values.any { it.team == TeamSide.AWAY }).isTrue()
        assertThat(next.id).isEqualTo(state.id)
    }

    @Test
    fun `declare run uses queued scripted move roll`() {
        val scenario = GameScenario.board()
        val base = TestProfiles.lineman()
        val profile = base.copy(stats = base.stats.copy(move = MoveValue.RandomD6))
        scenario.addPlayer(Coordinate(5, 6), profile = profile)
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val declare = legalMovesGenerator.forActingCoach(state).filterIsInstance<LegalAction.DeclareRun>().single()

        val overlay = ScriptedDiceRoller(fallbackToDefaultPattern = false).also { it.queueD6(3) }
        val next = diceRollerContext.withRoller(overlay) { search.applyAndSettle(state, declare, maxAutoSteps = 0) }

        // AWAITING_PATH after a 3 on the move die — three 1-square Run legs from (5,6).
        val runs = legalMovesGenerator.forSettle(next).filterIsInstance<LegalAction.Run>()
        assertThat(runs).isNotEmpty
        assertThat(runs.map { it.path.steps }.toSet()).contains(1)
        assertThat(runs.any { it.path.squares.last() == Coordinate(5, 7) || it.path.squares.last() == Coordinate(6, 6) || it.path.squares.last() == Coordinate(4, 6) || it.path.squares.last() == Coordinate(5, 5) }).isTrue()
    }

    @Test
    fun `applyAndSettle after dead eye does not invent DeclineBonus for Catch and Go`() {
        val scenario = GameScenario.board()
        scenario.holdingBall(scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2)))
        val catcher = scenario.addPlayer(
            Coordinate(5, 4),
            profile = TeamCatalog.team("human").roster.first { it.name == "Catcher" },
        )
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val throwAction = legalMovesGenerator.forActingCoach(state).filterIsInstance<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(5, 4) }

        val overlay = ScriptedDiceRoller(fallbackToDefaultPattern = false).also { it.queueD6(6) }
        val next = diceRollerContext.withRoller(overlay) { search.applyAndSettle(state, throwAction) }

        assertThat(next.board.player(catcher.player.id).holdsBall()).isTrue()
        assertThat(next.id).isEqualTo(state.id)
        // Settled past Catch and Go without leaving a throw-continue re-roll prompt.
        assertThat(legalMovesGenerator.forSettle(next).filterIsInstance<LegalAction.ChooseThrowReroll>()).isEmpty()
    }

    @Test
    fun `fastFollowUp only returns actions from the current legal list`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2))
        scenario.addPlayer(Coordinate(3, 6), profile = TestProfiles.withMove(2)) // keeps RPA menu if blocker turn-ends
        scenario.addPlayer(Coordinate(6, 6), team = TeamSide.AWAY)
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val block = legalMovesGenerator.forActingCoach(state).filterIsInstance<LegalAction.Block>().single()

        val overlay = ScriptedDiceRoller(fallbackToDefaultPattern = true).also { it.queueBlockDice(BlockDieFace.MISS) }
        val mid = diceRollerContext.withRoller(overlay) { search.applyAndSettle(state, block, maxAutoSteps = 0) }
        val front = mid.currentTurnState.pendingDecision ?: return
        val legals = legalMovesGenerator.forSettle(mid)
        val pick = search.fastFollowUp(mid, front, legals) ?: return
        assertThat(legals.map { it.id }).contains(pick.id)
    }

    @Test
    fun `applyAndSettle stops at RegularPlayerAction without starting another action`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(2))
        scenario.addLooseBall(Coordinate(5, 7))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val run = legalMovesGenerator.forActingCoach(state).filterIsInstance<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 7) }

        val next = search.applyAndSettle(state, run)
        assertThat(next.board.players.values.single { it.team == TeamSide.HOME }.holdsBall()).isTrue()
        assertThat(next.currentTurnState.actionsRemaining).isEqualTo(2)
        // Settle must not auto-start another player action (stops at the next budgeted pick).
        assertThat(next.currentTurnState.currentPlayerAction).isNull()
    }

    @Test
    fun `think budget is longer when facing a human seat`() {
        val vsHuman = matchState(
            board = GameScenario.board().board,
            activeCoach = TeamSide.HOME,
        ).let { base ->
            base.copy(
                config = base.config.copy(
                    homeControlledBy = CoachControl.COMPUTER,
                    awayControlledBy = CoachControl.HUMAN,
                ),
            )
        }
        val vsAi = vsHuman.copy(
            config = vsHuman.config.copy(
                awayControlledBy = CoachControl.COMPUTER,
            ),
        )
        assertThat(strategy.thinkBudgetNanosFor(vsHuman))
            .isEqualTo(LookaheadOpponentStrategy.HUMAN_THINK_NANOS)
        assertThat(strategy.thinkBudgetNanosFor(vsAi))
            .isEqualTo(LookaheadOpponentStrategy.AI_VS_AI_THINK_NANOS)
    }
}
