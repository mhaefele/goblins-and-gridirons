package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.dto.AiLegalKind
import com.mhaefele.gridiron.api.ai.mapping.AiLegalPacker
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedStepDto
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.api.ai.training.claim.scenario.ClaimCleanSweepTrainingScenario
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.LegalAction.PathMovement
import com.mhaefele.gridiron.api.dto.response.LegalAction.PlayerLegalAction
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.SEND_THEM_OFF
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PUSH_FORWARD
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired

/**
 * Claim-drill fixtures and playthroughs.
 * Teams are server-chosen (random). Playthroughs use HUMAN away so ComputerCoach does not race the harness.
 */
class ChallengeClaimTrainingScenarioTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Autowired
    private lateinit var trainingScenarios: TrainingScenarios

    @BeforeEach
    fun randomDice() {
        dice.usePassThrough()
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingScenarioEnum::class, names = ["CLAIM_.*"], mode = EnumSource.Mode.MATCH_ALL)
    fun `claim scenario opens on turn 3 with focus card and expected moves`(scenario: TrainingScenarioEnum) {
        assertClaimScenarioOpensWithExpectedMoves(scenario, difficulty = 0)
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingScenarioEnum::class, names = ["CLAIM_.*"], mode = EnumSource.Mode.MATCH_ALL)
    fun `suggested moves claim the focus card`(scenario: TrainingScenarioEnum) {
        withRandomDiceRetries { playSuggestedMovesUntilDrillPass(scenario, difficulty = 0) }
    }

    @ParameterizedTest(name = "difficulty={0}")
    @ValueSource(ints = [0, 3, 4, 5])
    fun `claim difficulty scatters extras after the lesson pieces`(difficulty: Int) {
        withRandomDiceRetries {
            val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.CLAIM_GRAB_IT, difficulty = difficulty))
            val state = loadMatch(response.matchId)
            val homeOnPitch = onPitchCount(state, TeamSide.HOME)
            val awayOnPitch = onPitchCount(state, TeamSide.AWAY)
            check(homeOnPitch == 1 + difficulty) { "home on pitch $homeOnPitch != ${1 + difficulty}" }
            check(awayOnPitch == difficulty) { "away on pitch $awayOnPitch != $difficulty" }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TrainingScenarioEnum::class, names = ["CLAIM_.*"], mode = EnumSource.Mode.MATCH_ALL)
    fun `claim scenario placement is stable across retries`(scenario: TrainingScenarioEnum) {
        withRandomDiceRetries(attempts = STRICT_DICE_RETRIES) {
            repeat(5) {
                val response = createClaimMatch(scenario)
                assertThat(loadMatch(response.matchId).progress.turnNumber)
                    .isEqualTo(ChallengeClaimTableau.OPENING_TURN)
            }
        }
    }

    @ParameterizedTest(name = "{0} d4")
    @EnumSource(TrainingScenarioEnum::class, names = ["CLAIM_.*"], mode = EnumSource.Mode.MATCH_ALL)
    fun `claim scenario at d4 opens with focus card and expected moves`(scenario: TrainingScenarioEnum) {
        // Fixture create + aiStep — no dice retries. Wrong-type focus leaks must fail here, not get swallowed.
        repeat(D4_STABILITY_REPEATS) {
            assertClaimScenarioOpensWithExpectedMoves(scenario, difficulty = 4)
        }
    }

    @ParameterizedTest(name = "{0} d4")
    @EnumSource(TrainingScenarioEnum::class, names = ["CLAIM_.*"], mode = EnumSource.Mode.MATCH_ALL)
    fun `suggested moves claim the focus card at d4`(scenario: TrainingScenarioEnum) {
        withRandomDiceRetries(attempts = RANDOM_DICE_RETRIES) {
            repeat(D4_STABILITY_REPEATS) { playSuggestedMovesUntilDrillPass(scenario, difficulty = 4) }
        }
    }

    @Test
    fun `Grab It drill annotates pickup and not fillers after keep-set replace`() {
        val response = createClaimMatch(TrainingScenarioEnum.CLAIM_GRAB_IT)
        matchId = response.matchId
        playThroughForcedReplaceToPlayerAction(matchId)
        val state = loadMatch(matchId)
        val legals = legalMovesGenerator.forActingCoach(state)
        val focus = ChallengeClaimCondition.GRAB_IT
        val fillers = state.cards.activeChallengeClaims().filter { it != focus }.toSet()
        assertThat(
            legals.filter {
                it.potentialChallengeClaims.any { claim -> claim.claimCondition == focus }
            },
        ).isNotEmpty()
        assertThat(legals.flatMap { it.potentialChallengeClaims }.map { it.claimCondition })
            .noneMatch { it in fillers }
    }

    @Test
    fun `Push Forward fillers exclude block cards when opening Mark is legal`() {
        withRandomDiceRetries(attempts = RANDOM_DICE_RETRIES) {
            repeat(D4_STABILITY_REPEATS) {
                val response = createClaimMatch(TrainingScenarioEnum.CLAIM_PUSH_FORWARD, difficulty = 4)
                matchId = response.matchId
                playThroughForcedReplaceToPlayerAction(matchId)
                val state = loadMatch(matchId)
                val openingMoves = legalMovesGenerator.forActingCoach(state)
                if (openingMoves.none { it is LegalAction.Mark }) return@repeat
                val fillers = state.cards.activeChallengeClaims().filter { it != PUSH_FORWARD }.toSet()
                assertThat(fillers)
                    .withFailMessage("Push Forward d4: block fillers leak when Mark is legal, fillers=$fillers")
                    .doesNotContain(DROP_THEM, SEND_THEM_OFF)
            }
        }
    }

    @Test
    fun `Clean Sweep opens with two trio keepables and one bait`() {
        val response = createClaimMatch(TrainingScenarioEnum.CLAIM_CLEAN_SWEEP)
        val state = loadMatch(response.matchId)
        val titles = state.cards.activeChallengeClaims()
        assertThat(titles.count { it in ClaimCleanSweepTrainingScenario.TRIO }).isEqualTo(2)
        assertThat(titles.count { it !in ClaimCleanSweepTrainingScenario.TRIO }).isEqualTo(1)
        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        assertThat(step.harness.legalRows.filter { it.softMaskLegal && AiLegalPacker.packKind(it.action) == AiLegalKind.replaceChallenge })
            .isNotEmpty()
        assertThat(step.harness.legalRows.filter { it.passingLegal }).isEmpty()
    }

    @Test
    fun `multi-move Keep Running keeps flagging Runs until the claim`() {
        val response = createClaimMatch(TrainingScenarioEnum.CLAIM_KEEP_RUNNING)
        matchId = response.matchId
        playThroughForcedReplaceToPlayerAction(matchId)
        val first = api.aiStep(matchId, forSeat = TeamSide.HOME)
        assertThat(first.harness.legalRows.filter { it.passingLegal }).isNotEmpty()
        api.play(matchId, first.harness.legalRows.first { it.passingLegal }.action.id)
        repeat(4) {
            val step = api.aiStep(matchId, forSeat = TeamSide.HOME)
            val decline = step.harness.legalRows.firstOrNull { AiLegalPacker.packKind(it.action) == AiLegalKind.declineBonus } ?: return@repeat
            api.play(matchId, decline.action.id)
        }
        val second = api.aiStep(matchId, forSeat = TeamSide.HOME)
        assertThat(loadMatch(matchId).progress.homeChallengeCards).isZero()
        assertThat(second.harness.legalRows.filter { it.passingLegal && AiLegalPacker.packKind(it.action) == AiLegalKind.run })
            .isNotEmpty()
    }

    @Test
    fun `Show Them How soft-masks the focus star on all three budgeted moves`() {
        withRandomDiceRetries {
            val response = createClaimMatch(TrainingScenarioEnum.CLAIM_STAR_TURN)
            matchId = response.matchId
            playThroughForcedReplaceToPlayerAction(matchId)
            val opening = api.aiStep(matchId, forSeat = TeamSide.HOME)
            val openingSoft = opening.harness.legalRows.filter { it.softMaskLegal }
            val openingPass = opening.harness.legalRows.filter { it.passingLegal }
            assertThat(openingSoft)
                .withFailMessage("Show Them How: opening soft-mask flags solvable star moves")
                .isNotEmpty()
            assertThat(openingSoft.map { it.action.id })
                .allMatch { id -> openingPass.any { it.action.id == id } }
            val starOpening = starOpeningChoice(opening, loadMatch(matchId))
            api.play(matchId, starOpening)
            repeat(4) {
                val step = api.aiStep(matchId, forSeat = TeamSide.HOME)
                val decline = step.harness.legalRows.firstOrNull { AiLegalPacker.packKind(it.action) == AiLegalKind.declineBonus } ?: return@repeat
                api.play(matchId, decline.action.id)
            }
            val second = api.aiStep(matchId, forSeat = TeamSide.HOME)
            val secondSoft = second.harness.legalRows.filter { it.softMaskLegal }
            val secondPass = second.harness.legalRows.filter { it.passingLegal }
            assertThat(secondSoft)
                .withFailMessage("Show Them How: follow-up soft-mask keeps flagging solvable star moves")
                .isNotEmpty()
            assertThat(secondSoft.map { it.action.id })
                .allMatch { id -> secondPass.any { it.action.id == id } }
        }
    }

    private fun onPitchCount(state: MatchState, team: TeamSide): Int =
        state.board.players.values.count { it.team == team && it.status != PlayerStatus.RESERVES }

    private fun starOpeningChoice(step: AiNestedStepDto, state: MatchState): LegalActionId {
        val starId = state.trainingEpisode!!.starTurnStarId ?: error("Show Them How: starTurnStarId required")
        val passingIds = step.harness.legalRows.filter { it.passingLegal }.map { it.action.id }.toSet()
        val starLegals = legalMovesGenerator.forActingCoach(state)
            .filterIsInstance<PlayerLegalAction>()
            .filter { it.playerId == starId }
        // Prefer harness PASS rows; fall back to any non-trapdoor star legal if PASS was wiped by scatter.
        val pick = starLegals.firstOrNull { it.id in passingIds }
            ?: starLegals.firstOrNull { action ->
                action !is PathMovement || !state.board.squareAt(action.path.squares.last()).isTrapdoor()
            } ?: error("Show Them How: no usable opening legal for focus star $starId (passing=${passingIds.size}, starLegals=${starLegals.size})")
        return step.harness.legalRows.firstOrNull { it.action.id == pick.id }?.action?.id ?: error("Show Them How: star legal ${pick.id} missing from ai step choices")
    }

    private fun assertClaimScenarioOpensWithExpectedMoves(scenario: TrainingScenarioEnum, difficulty: Int) {
        val response = createClaimMatch(scenario, difficulty = difficulty)
        val state = loadMatch(response.matchId)
        assertThat(state.progress.turnNumber).isEqualTo(ChallengeClaimTableau.OPENING_TURN)
        assertThat(state.trainingEpisode!!.openingTurnNumber).isEqualTo(ChallengeClaimTableau.OPENING_TURN)
        assertThat(state.trainingEpisode!!.difficulty).isEqualTo(difficulty)
        assertThat(state.cards.activeCards()).hasSize(3)
        assertThat(state.currentTurnState.pendingDecision)
            .withFailMessage("$scenario d$difficulty: claim drills open on ReplaceChallenge")
            .isInstanceOf(ReplaceChallenge::class.java)

        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        val softMask = step.harness.legalRows.filter { it.softMaskLegal }
        val passing = step.harness.legalRows.filter { it.passingLegal }
        assertThat(softMask)
            .withFailMessage("$scenario d$difficulty: expected soft-mask bait ReplaceChallenge")
            .isNotEmpty()
        assertThat(softMask).allMatch { AiLegalPacker.packKind(it.action) == AiLegalKind.replaceChallenge }
        assertThat(passing)
            .withFailMessage("$scenario d$difficulty: replace step must not PASS (claim comes after)")
            .isEmpty()
        assertFocusClaimActionTypes(scenario, difficulty, response.matchId)
    }

    /** Typed claim drills: after replace + Pre-Turn drain, focus annotations must be the taught action class. */
    private fun assertFocusClaimActionTypes(scenario: TrainingScenarioEnum, difficulty: Int, matchId: MatchId) {
        val (focus, expected) = when (scenario) {
            TrainingScenarioEnum.CLAIM_SEND_THEM_OFF_FOUL -> SEND_THEM_OFF to LegalAction.Foul::class
            TrainingScenarioEnum.CLAIM_SEND_THEM_OFF      -> SEND_THEM_OFF to LegalAction.Block::class
            TrainingScenarioEnum.CLAIM_DROP_THEM          -> DROP_THEM to LegalAction.Block::class
            else                                          -> return
        }
        playThroughForcedReplaceToPlayerAction(matchId)
        val legals = legalMovesGenerator.forActingCoach(loadMatch(matchId))
        val tagged = legals.filter { action ->
            action.potentialChallengeClaims.any { it.claimCondition == focus }
        }
        assertThat(tagged)
            .withFailMessage("$scenario d$difficulty: no opening legal annotated for $focus after replace")
            .isNotEmpty()
        assertThat(tagged)
            .withFailMessage(
                "$scenario d$difficulty: focus $focus on ${tagged.map { it::class.simpleName }}, expected ${expected.simpleName}",
            )
            .allMatch { expected.isInstance(it) }
    }

    /** Soft-mask bait replace; claim episodes suppress ER so the next decision is PLAYER_ACTION. */
    private fun playThroughForcedReplaceToPlayerAction(matchId: MatchId) {
        val step = api.aiStep(matchId, forSeat = TeamSide.HOME)
        val bait = step.harness.legalRows.firstOrNull { AiLegalPacker.packKind(it.action) == AiLegalKind.replaceChallenge && it.softMaskLegal } ?: error("claim replace: no soft-mask bait\n${getDomainEventsString(matchId)}")
        api.play(matchId, bait.action.id)
        val state = loadMatch(matchId)
        val pending = state.currentTurnState.pendingDecision
        check(state.currentTurnState.phase == TurnPhase.PLAYER_ACTION && pending is RegularPlayerAction && pending.delegate == null) { "claim replace: expected PLAYER_ACTION select after bait discard, was phase=${state.currentTurnState.phase} pending=${pending?.javaClass?.simpleName}\n${getDomainEventsString(matchId)}" }
    }

    private fun playSuggestedMovesUntilDrillPass(scenario: TrainingScenarioEnum, difficulty: Int) {
        val response = createClaimMatch(scenario, difficulty = difficulty, awayControlledBy = CoachControl.HUMAN)
        matchId = response.matchId
        playUntilClaimed(scenario, matchId) { step ->
            val state = loadMatch(matchId)
            val replaceSoft = step.harness.legalRows.filter { AiLegalPacker.packKind(it.action) == AiLegalKind.replaceChallenge && it.softMaskLegal }
            val claim = step.harness.legalRows.firstOrNull { AiLegalPacker.packKind(it.action) == AiLegalKind.claimChallenge }
            val softMask = step.harness.legalRows.filter { it.softMaskLegal }
            val passing = step.harness.legalRows.filter { it.passingLegal }
            // Prefer soft-mask (solvability search on multi-step TRAIN drills), then PASS rows.
            val guided = softMask.ifEmpty { passing }
            val guidedPick = if (scenario == TrainingScenarioEnum.CLAIM_STAR_TURN) {
                // Prefer Mark/Block over wandering Runs so the star stays in range to chain types.
                listOf(
                    AiLegalKind.mark,
                    AiLegalKind.block,
                    AiLegalKind.sidestep,
                    AiLegalKind.foul,
                    AiLegalKind.`throw`,
                    AiLegalKind.run,
                ).asSequence()
                    .map { kind -> guided.filter { AiLegalPacker.packKind(it.action) == kind } }
                    .firstOrNull { it.isNotEmpty() }
                    ?: guided
            } else {
                guided.filter { it.action.potentialChallengeClaims.isNotEmpty() }.ifEmpty {
                    listOf(AiLegalKind.mark, AiLegalKind.block, AiLegalKind.sidestep, AiLegalKind.run)
                        .asSequence()
                        .map { kind -> guided.filter { AiLegalPacker.packKind(it.action) == kind } }
                        .firstOrNull { it.isNotEmpty() }
                        ?: guided
                }
            }
            val starOpening = if (
                scenario == TrainingScenarioEnum.CLAIM_STAR_TURN &&
                guided.isEmpty() &&
                state.currentTurnState.actionsThisTurn.isEmpty() &&
                replaceSoft.isEmpty()
            ) {
                starOpeningChoice(step, state)
            } else {
                null
            }
            val decline =
                step.harness.legalRows.firstOrNull { AiLegalPacker.packKind(it.action) == AiLegalKind.declineBonus || AiLegalPacker.packKind(it.action) == AiLegalKind.skip || AiLegalPacker.packKind(it.action) == AiLegalKind.keepOffer }
            when {
                replaceSoft.isNotEmpty() -> replaceSoft.first().action.id
                claim != null            -> claim.action.id
                guidedPick.isNotEmpty()  -> guidedPick.first().action.id
                starOpening != null      -> starOpening
                decline != null          -> decline.action.id
                else                     -> error(
                    "$scenario d$difficulty: no replace/claim/soft-mask/passing/decline; kinds=${step.harness.legalRows.map { AiLegalPacker.packKind(it.action) }}\n${getDomainEventsString(matchId)}",
                )
            }
        }
    }

    private fun playUntilClaimed(
        scenario: TrainingScenarioEnum,
        matchId: MatchId,
        pickLegalId: (AiNestedStepDto) -> LegalActionId,
    ) {
        val match = MatchApi(api, matchId)
        var steps = 0
        var activeChallengeClaims = emptyList<ChallengeClaimCondition>()
        while (!drillPassed(match)) {
            check(++steps <= 24) { "$scenario: too many suggested steps without claiming\n${getDomainEventsString(matchId)}" }
            val before = loadMatch(match)
            check(before.currentTurnState.activeCoach == TeamSide.HOME) { "$scenario: HOME lost the turn before claiming via suggested moves.\nRemaining Challenges:  $activeChallengeClaims\n${getDomainEventsString(matchId)}." }
            activeChallengeClaims = before.cards.activeChallengeClaims()
            // Pre-Turn (forced replace) has actionsRemaining=0 — do not treat that as 3 actions used.
            if (before.currentTurnState.phase == TurnPhase.PLAYER_ACTION) {
                val actionBudget = trainingScenarios.implementation(scenario).expectedBudgetedActions
                val actionsUsed = before.currentTurnState.actionsThisTurn.count { !it.isFree }
                check(actionsUsed <= actionBudget) { "$scenario: used $actionsUsed/$actionBudget actions without claiming\nRemaining Challenges:  $activeChallengeClaims\n${getDomainEventsString(matchId)}" }
            }

            val step = api.aiStep(matchId, forSeat = TeamSide.HOME)
            api.play(matchId, pickLegalId(step))
        }
        assertThat(loadMatch(match).trainingEpisode!!.drillStatus).isEqualTo(TrainingDrillStatus.PASS)
    }

    private fun createClaimMatch(
        scenario: TrainingScenarioEnum,
        difficulty: Int = 0,
        awayControlledBy: CoachControl = CoachControl.COMPUTER,
    ) = api.createScenarioMatch(
        scenarioMatchRequest(scenario, difficulty = difficulty, awayControlledBy = awayControlledBy),
    )

    private fun drillPassed(match: MatchApi): Boolean =
        loadMatch(match).trainingEpisode!!.drillStatus == TrainingDrillStatus.PASS

    private companion object {
        const val D4_STABILITY_REPEATS = 5
    }
}
