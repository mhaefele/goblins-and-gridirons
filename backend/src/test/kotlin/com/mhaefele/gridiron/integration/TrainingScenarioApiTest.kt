package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.training.TrainingScenarioFamily
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired

class TrainingScenarioApiTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Autowired
    private lateinit var trainingScenarios: TrainingScenarios

    @BeforeEach
    fun randomDice() {
        dice.usePassThrough()
    }

    @Test
    fun `list scenarios returns family and difficulty proxy metadata`() {
        val entries = api.listTrainingScenarios()
        assertThat(entries).hasSize(TrainingScenarioEnum.entries.size)
        assertThat(entries.map { it.name }).containsExactlyElementsOf(TrainingScenarioEnum.entries)

        val bounce = entries.single { it.name == TrainingScenarioEnum.LOOSE_BALL_BOUNCE }
        assertThat(bounce.family).isEqualTo(TrainingScenarioFamily.BOARD)
        assertThat(bounce.difficultyProxy).isEqualTo(TrainingScenarioEnum.LOOSE_BALL_RUN)
        assertThat(bounce.gatesProgress).isFalse()

        entries.filter { it.difficultyProxy != null }.forEach { entry ->
            assertThat(entry.gatesProgress)
                .describedAs("${entry.name} has difficultyProxy")
                .isFalse()
        }

        val claim = entries.single { it.name == TrainingScenarioEnum.CLAIM_GRAB_IT }
        assertThat(claim.family).isEqualTo(TrainingScenarioFamily.CLAIM)
        assertThat(claim.difficultyProxy).isNull()
        assertThat(claim.gatesProgress).isTrue()

        assertThat(entries.filter { !it.gatesProgress }.map { it.name }).containsExactlyInAnyOrder(
            TrainingScenarioEnum.LOOSE_BALL_BOUNCE,
            TrainingScenarioEnum.CLAIM_STAR_TURN,
            TrainingScenarioEnum.CLAIM_CLEAN_SWEEP,
            TrainingScenarioEnum.CHALLENGE_REPLACE,
            TrainingScenarioEnum.DENY_LOCK_THEM_DOWN,
            TrainingScenarioEnum.DENY_PICKUP,
        )
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingScenarioEnum::class)
    fun `create scenario match succeeds for each scenario type`(scenario: TrainingScenarioEnum) {
        val response = api.createScenarioMatch(scenarioMatchRequest(scenario))

        assertThat(response.scenario).isEqualTo(scenario)
        assertThat(response.matchId.value).isNotBlank()

        val state = loadMatch(response.matchId)
        assertThat(state.trainingEpisode!!.trainerSide).isEqualTo(TeamSide.HOME)
        assertThat(state.config.coachA).isEqualTo(TeamSide.HOME)

        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        assertThat(step.harness.isGameEnded()).isFalse()
        assertThat(step.harness.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(step.harness.legalRows).isNotEmpty()
    }

    /**
     * Soft-mask / passing flags must reach the AI step harness. Default: soft-mask non-empty and
     * identical to passing. Exceptions only when soft-mask and PASS intentionally diverge
     * (or soft-mask is off). Does not re-assert action *kinds* — that just mirrors scenario code.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingScenarioEnum::class)
    fun `AI step flags soft-mask opening moves only on unambiguous drills`(scenario: TrainingScenarioEnum) {
        val response = api.createScenarioMatch(scenarioMatchRequest(scenario))
        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        val softMask = step.harness.legalRows.filter { it.softMaskLegal }
        val passing = step.harness.legalRows.filter { it.passingLegal }
        when (scenario) {
            TrainingScenarioEnum.MARKED_RUN     -> {
                // Multi-step: opening expected (= passing) matches soft-mask; drill PASSes on TD only.
                assertThat(softMask).isNotEmpty()
                assertThat(passing).isEqualTo(softMask)
            }
            TrainingScenarioEnum.LOOSE_BALL_RUN -> {
                // Opening soft-mask = pickup always + empty-EZ at 1/4; PASS = pickup only.
                assertThat(softMask).isNotEmpty()
                assertThat(passing).isNotEmpty()
                assertThat(passing).isSubsetOf(softMask)
            }
            TrainingScenarioEnum.THROW_TO_ENDZONE,
            TrainingScenarioEnum.THROW_CLOSER_RANGE,
            TrainingScenarioEnum.THROW_OPEN_RECEIVER,
                                                   -> {
                assertThat(softMask).isNotEmpty()
                assertThat(passing).isNotEmpty()
                assertThat(passing).isSubsetOf(softMask)
            }
            TrainingScenarioEnum.LOOSE_BALL_BOUNCE -> {
                assertThat(softMask).isEmpty()
                assertThat(passing).isNotEmpty()
            }
            else                                   -> {
                // Claim drills open on forced replace: soft-mask bait discard, PASS only after the claim.
                if (scenario.name.startsWith("CLAIM_")) {
                    assertThat(softMask).isNotEmpty()
                    assertThat(passing).isEmpty()
                } else {
                    assertThat(softMask).isNotEmpty()
                    assertThat(passing).isEqualTo(softMask)
                }
            }
        }
    }

    @Test
    fun `LOOSE_BALL_RUN soft-masks pickup then EZ continue and PASSes on score`() {
        withRandomDiceRetries {
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.LOOSE_BALL_RUN))
            val opening = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
            val openingSoft = opening.harness.legalRows.filter { it.softMaskLegal }
            val openingPass = opening.harness.legalRows.filter { it.passingLegal }
            assertThat(openingSoft).isNotEmpty()
            assertThat(openingPass).isNotEmpty()
            // PASS = pickup only; soft-mask always includes pickup; empty-EZ only at 1/4 per ask.
            assertThat(openingPass).isSubsetOf(openingSoft)
            assertThat(openingPass).allMatch { it.action is LegalAction.Run && (it.action as LegalAction.Run).ballEncounter == BallEncounter.PICK_UP }
            val emptyEzSoft = openingSoft.filter { row ->
                val run = row.action as? LegalAction.Run ?: return@filter false
                run.ballEncounter != BallEncounter.PICK_UP
            }
            assertThat(emptyEzSoft).allMatch { !it.passingLegal }
            // Shortest pickup leaves the most residual MA for the EZ continue.
            val pickup = openingPass.map { it.action }.filterIsInstance<LegalAction.Run>().minBy { it.path.steps }

            val afterPickup = api.aiSubmit(response.matchId, pickup.id, forSeat = TeamSide.HOME)
            val afterPickupState = loadMatch(response.matchId)
            assertThat(afterPickupState.trainingEpisode!!.drillStatus).isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
            val continueRows = afterPickup.next.harness.legalRows.ifEmpty { api.aiStep(response.matchId, forSeat = TeamSide.HOME).harness.legalRows }
            val continueSoft = continueRows.filter { it.softMaskLegal }
            assertThat(continueSoft).isNotEmpty()
            assertThat(continueRows.filter { it.passingLegal }).isEqualTo(continueSoft)
            val scoringEndZone = afterPickupState.trainingEpisode!!.trainerSide.opposite()
            assertThat(continueSoft).allMatch { row ->
                val run = row.action as? LegalAction.Run ?: return@allMatch false
                afterPickupState.board.squareAt(run.path.squares.last()).isEndZone(scoringEndZone)
            }

            api.aiSubmit(response.matchId, continueSoft.first().action.id, forSeat = TeamSide.HOME)
            assertThat(loadMatch(response.matchId).trainingEpisode!!.drillStatus).isEqualTo(TrainingDrillStatus.PASS)
        }
    }

    @Test
    fun `LOOSE_BALL_RUN empty-EZ soft-mask distractor does not PASS`() {
        withRandomDiceRetries {
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.LOOSE_BALL_RUN))
            val opening = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
            // Empty-EZ soft-mask is 1/4 — retry until a soft-masked distractor appears.
            val distractor = opening.harness.legalRows.first { row ->
                !row.passingLegal && row.softMaskLegal && row.action is LegalAction.Run && (row.action as LegalAction.Run).ballEncounter != BallEncounter.PICK_UP
            }
            api.aiSubmit(response.matchId, distractor.action.id, forSeat = TeamSide.HOME)
            val status = loadMatch(response.matchId).trainingEpisode!!.drillStatus
            assertThat(status).isNotEqualTo(TrainingDrillStatus.PASS)
        }
    }

    @Test
    fun `one-shot drill PASSes when expected move comes after a distractor action`() {
        withRandomDiceRetries {
            // Two trainers + assisted/unassisted targets: burn a non-PASS Block, then the assisted one.
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.ASSISTED_BLOCK))
            val opening = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
            val distractor = opening.harness.legalRows.first { !it.passingLegal && it.action is LegalAction.Block }
            api.aiSubmit(response.matchId, distractor.action.id, forSeat = TeamSide.HOME)

            val afterDistractor = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
            val passing = afterDistractor.harness.legalRows.filter { it.passingLegal }
            assertThat(passing).isNotEmpty()
            // One-shot follow-up soft-masks the frozen opening PASS ids when still legal.
            assertThat(afterDistractor.harness.legalRows.filter { it.softMaskLegal }).isEqualTo(passing)
            api.aiSubmit(response.matchId, passing.first().action.id, forSeat = TeamSide.HOME)
            assertThat(loadMatch(response.matchId).trainingEpisode!!.drillStatus).isEqualTo(TrainingDrillStatus.PASS)
        }
    }

    @Test
    fun `TD_RUN packs the next step after scoring without trainer-carrier errors`() {
        withRandomDiceRetries {
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.TD_RUN))
            val opening = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
            val passing = opening.harness.legalRows.first { it.passingLegal }
            val submitted = api.aiSubmit(response.matchId, passing.action.id, forSeat = TeamSide.HOME)
            assertThat(loadMatch(response.matchId).trainingEpisode!!.drillStatus).isEqualTo(TrainingDrillStatus.PASS)
            // Packing after PASS must not re-run opening predicates (no ball carrier left).
            assertThat(submitted.next.harness.legalRows).noneMatch { it.passingLegal || it.softMaskLegal }
        }
    }

    @Test
    fun `TD_RUN throw then former-carrier EZ run does not PASS`() {
        withRandomDiceRetries {
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.TD_RUN, difficulty = 1))
            val opening = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
            val throwAway = opening.harness.legalRows.firstOrNull { it.action is LegalAction.Throw } ?: error("TD_RUN d1: need a Throw legal to exercise throw-then-run")
            api.aiSubmit(response.matchId, throwAway.action.id, forSeat = TeamSide.HOME)

            val afterThrow = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
            assertThat(afterThrow.harness.legalRows).noneMatch { it.passingLegal || it.softMaskLegal }
            val board = loadMatch(response.matchId).board
            val ezRun = afterThrow.harness.legalRows.firstOrNull { row ->
                val run = row.action as? LegalAction.Run ?: return@firstOrNull false
                board.squareAt(run.path.squares.last()).isEndZone(TeamSide.AWAY)
            } ?: error("TD_RUN d1: need a ball-less EZ Run still legal after the throw")
            api.aiSubmit(response.matchId, ezRun.action.id, forSeat = TeamSide.HOME)
            assertThat(loadMatch(response.matchId).trainingEpisode!!.drillStatus)
                .isEqualTo(TrainingDrillStatus.STILL_IN_PROGRESS)
        }
    }

    @ParameterizedTest(name = "difficulty={0}")
    @ValueSource(ints = [0, 3, 5])
    fun `difficulty scatters that many extra reserves on both sides`(difficulty: Int) {
        withRandomDiceRetries {
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.TD_RUN, difficulty = difficulty))
            val board = loadMatch(response.matchId).board
            val homeOnPitch = board.players.values.count { it.team == TeamSide.HOME && it.status == PlayerStatus.STANDING }
            val awayOnPitch = board.players.values.count { it.team == TeamSide.AWAY && it.status == PlayerStatus.STANDING }
            // TD_RUN places 1 home carrier; then scatters up to `difficulty` extras each side (roster-capped).
            check(homeOnPitch == 1 + difficulty) { "home on pitch $homeOnPitch != ${1 + difficulty}" }
            check(awayOnPitch == difficulty) { "away on pitch $awayOnPitch != $difficulty" }
        }
    }

    @Test
    fun `THROW_TO_ENDZONE thrower has no legal run into the scoring end zone`() {
        repeat(8) {
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.THROW_TO_ENDZONE))
            val state = loadMatch(response.matchId)
            val thrower = state.board.players.values.single { it.team == TeamSide.HOME && it.heldBallId != null }
            val scoringEndZone = TeamSide.AWAY
            val tdRuns = legalMovesGenerator.forActingCoach(state).filterIsInstance<LegalAction.Run>().filter { run ->
                run.playerId == thrower.id && state.board.squareAt(run.path.squares.last()).isEndZone(scoringEndZone)
            }
            assertThat(tdRuns).isEmpty()
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
        value = TrainingScenarioEnum::class,
        names = [
            "ASSISTED_BLOCK",
            "CLAIM_SEND_THEM_OFF_FOUL",
            "HELD_SS_REROLL",
            "HELD_PP_REROLL",
            "HELD_DO_OVER",
        ],
    )
    fun `new drills always expose a passing move at every difficulty`(scenario: TrainingScenarioEnum) {
        for (difficulty in 0..5) {
            repeat(4) {
                val response = api.createScenarioMatch(scenarioMatchRequest(scenario, difficulty = difficulty))
                var state = loadMatch(response.matchId)
                // Claim foul drill opens on forced replace (soft bait, no PASS); claim after discard.
                if (scenario == TrainingScenarioEnum.CLAIM_SEND_THEM_OFF_FOUL) {
                    val opening = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
                    api.play(response.matchId, opening.harness.legalRows.first { it.softMaskLegal }.action.id)
                    state = loadMatch(response.matchId)
                }
                val legals = legalMovesGenerator.forActingCoach(state)
                assertThat(legals.any { trainingScenarios.isPassingScenarioMove(it, state) })
                    .withFailMessage("$scenario d$difficulty: no passing legal among ${legals.map { it::class.simpleName }}")
                    .isTrue()
                // Enumerate every row the way training does — must not 500 on wrong-type focus tags.
                assertThat(api.aiStep(response.matchId, forSeat = TeamSide.HOME).harness.legalRows).isNotEmpty()
            }
        }
    }

    @Test
    fun `held check placement opens window with placement dice overlay`() {
        val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.HELD_PP_REROLL))
        val legals = legalMovesGenerator.forActingCoach(loadMatch(response.matchId))
        assertThat(legals.filterIsInstance<LegalAction.ChooseThrowReroll>()).isNotEmpty()
    }
}
