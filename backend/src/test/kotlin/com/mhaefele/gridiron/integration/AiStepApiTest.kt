package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.dto.AiLegalKind
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.AiTeamRel
import com.mhaefele.gridiron.api.ai.dto.AiTerrain
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.RestTemplate

class AiStepApiTest : BaseApiTest() {

    @Autowired
    private lateinit var restTemplate: RestTemplate

    @Test
    fun `AI step returns schema version acting seat and LegalAction harness rows`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val step = api.aiStep(match)

        assertThat(step.schemaVersion).isEqualTo(AiObsLayout.SCHEMA_VERSION)
        assertThat(step.harness.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(step.harness.perspectiveSeat).isEqualTo(TeamSide.HOME)
        assertThat(step.harness.isGameEnded()).isFalse()
        assertThat(step.policy.obs.cells).hasSize(AiObsLayout.BOARD_SLOT_COUNT)
        assertThat(step.policy.obs.ownBonusPlay).hasSize(AiObsLayout.BONUS_HAND_SLOTS)
        assertThat(step.policy.obs.heldCheck.kind).isNull()
        assertThat(step.policy.obs.heldCheck.blockFaces).hasSize(AiObsLayout.MAX_HELD_BLOCK_FACES)
        assertThat(step.policy.obs.touchdownsUs).isEqualTo(0)
        assertThat(step.policy.obs.touchdownsThem).isEqualTo(0)
        assertThat(step.policy.obs.challengeCardsUs).isEqualTo(0)
        assertThat(step.policy.obs.challengeCardsThem).isEqualTo(0)
        assertThat(step.harness.legalRows).isNotEmpty()

        val run = step.harness.legalRows.first { it.action is LegalAction.Run }
        assertThat(run.action).isInstanceOf(LegalAction.Run::class.java)
        assertThat(run.action.id.value).isNotBlank()
        assertThat(run.action.potentialChallengeClaims).isNotNull()
        assertThat(step.harness.legalRows).noneMatch { it.softMaskLegal }
    }

    @Test
    fun `perspectiveSeat query overrides relative obs without changing acting seat`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val step = api.aiStep(match, perspectiveSeat = TeamSide.AWAY)

        assertThat(step.harness.actingSeat).isEqualTo(TeamSide.HOME)
        assertThat(step.harness.perspectiveSeat).isEqualTo(TeamSide.AWAY)
    }

    @Test
    fun `AWAY perspective flips pitch cells and swaps bench regions`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val homeView = api.aiStep(match, perspectiveSeat = TeamSide.HOME)
        val awayView = api.aiStep(match, perspectiveSeat = TeamSide.AWAY)

        val absoluteIndex = AiObsLayout.squareIndex(3, 2)
        val flippedIndex = AiObsLayout.toPerspectiveIndex(absoluteIndex, TeamSide.AWAY)
        // Same absolute square lands at the flipped AWAY index (occupancy / terrain / ball).
        val homeAbs = homeView.policy.obs.cells[absoluteIndex]
        val awayFlipped = awayView.policy.obs.cells[flippedIndex]
        assertThat(awayFlipped.terrain).isEqualTo(homeAbs.terrain)
        assertThat(awayFlipped.hasBall).isEqualTo(homeAbs.hasBall)
        assertThat(awayFlipped.player).isEqualTo(homeAbs.player)
        assertThat(awayFlipped.distToBall).isEqualTo(homeAbs.distToBall)
        // pitchX/Y are perspective-slot coords — equal at the same index across seats, not across the flip.
        assertThat(homeView.policy.obs.cells[absoluteIndex].pitchX)
            .isEqualTo(awayView.policy.obs.cells[absoluteIndex].pitchX)
        assertThat(homeView.policy.obs.cells[absoluteIndex].pitchY)
            .isEqualTo(awayView.policy.obs.cells[absoluteIndex].pitchY)

        val homeEndNear = homeView.policy.obs.cells[AiObsLayout.squareIndex(6, 1)]
        val awayEndNear = awayView.policy.obs.cells[AiObsLayout.squareIndex(6, 1)]
        val homeEndFar = homeView.policy.obs.cells[AiObsLayout.squareIndex(6, AiObsLayout.PLAY_HEIGHT)]
        val awayEndFar = awayView.policy.obs.cells[AiObsLayout.squareIndex(6, AiObsLayout.PLAY_HEIGHT)]
        assertThat(homeEndNear.terrain).isEqualTo(AiTerrain.END_ZONE_HOME)
        assertThat(awayEndNear.terrain).isEqualTo(AiTerrain.END_ZONE_HOME)
        assertThat(homeEndFar.terrain).isEqualTo(AiTerrain.END_ZONE_AWAY)
        assertThat(awayEndFar.terrain).isEqualTo(AiTerrain.END_ZONE_AWAY)

        val homeBenchAbs = AiObsLayout.homeBenchIndex(0)
        val awayBenchAbs = AiObsLayout.awayBenchIndex(0)
        if (homeView.policy.obs.cells[homeBenchAbs].player != null) {
            assertThat(homeView.policy.obs.cells[homeBenchAbs].player?.teamRel).isEqualTo(AiTeamRel.US)
            assertThat(homeView.policy.obs.cells[awayBenchAbs].player?.teamRel).isEqualTo(AiTeamRel.THEM)
            assertThat(awayView.policy.obs.cells[AiObsLayout.homeBenchIndex(0)].player?.teamRel).isEqualTo(AiTeamRel.US)
            assertThat(awayView.policy.obs.cells[AiObsLayout.awayBenchIndex(0)].player?.teamRel).isEqualTo(AiTeamRel.THEM)
        }
    }

    @Test
    fun `AWAY perspective remaps packed dest while LegalAction coords stay absolute`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val homeStep = api.aiStep(match, perspectiveSeat = TeamSide.HOME)
        val awayStep = api.aiStep(match, perspectiveSeat = TeamSide.AWAY)

        val homeRun = homeStep.harness.legalRows.first { it.action is LegalAction.Run }
        val awayRun = awayStep.harness.legalRows.first { it.action.id == homeRun.action.id }
        val homeAction = homeRun.action as LegalAction.Run
        val awayAction = awayRun.action as LegalAction.Run
        assertThat(awayAction.targetCoordinate).isEqualTo(homeAction.targetCoordinate)
        assertThat(awayAction.playerId).isEqualTo(homeAction.playerId)

        val homePacked = homeStep.policy.legalMoves[homeStep.harness.legalRows.indexOf(homeRun)]
        val awayPacked = awayStep.policy.legalMoves[awayStep.harness.legalRows.indexOf(awayRun)]
        assertThat(awayPacked[0]).isEqualTo(homePacked[0]) // actor
        assertThat(awayPacked[2]).isEqualTo(AiObsLayout.PLAY_WIDTH - 1 - homePacked[2])
        assertThat(awayPacked[3]).isEqualTo(AiObsLayout.PLAY_HEIGHT - 1 - homePacked[3])

        val homeActor = homePacked[0]
        val homeCell = homeStep.policy.obs.cells.first { it.player?.playerIndex == homeActor }
        assertThat(homeCell.player?.playerIndex).isEqualTo(homeActor)
        val awayCell = awayStep.policy.obs.cells.first { it.player?.playerIndex == homeActor && it.player?.teamRel == AiTeamRel.THEM }
        assertThat(awayCell.player?.playerIndex).isEqualTo(homeActor)
    }

    @Test
    fun `submit uses LegalAction id from AI step`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val before = api.aiStep(match)
        val run = before.harness.legalRows.first { it.action is LegalAction.Run }
        api.play(match.matchId, run.action.id)

        val after = api.aiStep(match)
        assertThat(after.policy.obs.actionsRemaining).isEqualTo(before.policy.obs.actionsRemaining - 1)
    }

    @Test
    fun `submitting an unknown legalActionId fails closed`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        assertBadRequest { api.play(match.matchId, LegalActionId("not-a-real-legal-action")) }
    }

    @Test
    fun `GUI legal-actions and AI step share the same LegalAction id set`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val guiIds = api.legalActions(match).map { it.id }.toSet()
        val aiIds = api.aiStep(match).harness.legalRows.map { it.action.id }.toSet()
        assertThat(aiIds).isEqualTo(guiIds)

        val guiRun = api.legalActions<LegalAction.Run>(match).first()
        val aiRun = api.aiStep(match).harness.legalRows.first { it.action.id == guiRun.id }
        assertThat(aiRun.action).isInstanceOf(LegalAction.Run::class.java)
    }

    @Test
    fun `Sprint and Weave twins to the same destination are distinct AI rows via attachedBonus pack`() {
        val match = match {
            homeBonus(BonusPlay.Sprint, BonusPlay.Weave)
            actionsRemaining(2)
            addPlayer(3, 6, profile = TestProfiles.withMove(4))
            addPlayer(5, 6, team = TeamSide.AWAY)
        }
        val runs = match.legalActions<LegalAction.Run>()
        val sprintRuns = runs.filter { it.requiredBonusCard == BonusPlay.Sprint }
        val weaveRuns = runs.filter { it.requiredBonusCard == BonusPlay.Weave }
        assertThat(sprintRuns).isNotEmpty
        assertThat(weaveRuns).isNotEmpty
        val dest = sprintRuns.map { it.targetCoordinate }.toSet().intersect(weaveRuns.map { it.targetCoordinate }.toSet())
        check(dest.isNotEmpty()) { "expected a square tagged by both Sprint and Weave; sprint=${sprintRuns.map { it.targetCoordinate }} weave=${weaveRuns.map { it.targetCoordinate }}" }
        val shared = dest.first()
        val sprintRun = sprintRuns.first { it.targetCoordinate == shared }
        val weaveRun = weaveRuns.first { it.targetCoordinate == shared }
        val step = api.aiStep(match)
        val sharedChoices = step.harness.legalRows.filter { it.action.id == sprintRun.id || it.action.id == weaveRun.id }
        assertThat(sharedChoices).hasSize(2)
        val byId = sharedChoices.associateBy { it.action.id }
        val sprintChoice = byId.getValue(sprintRun.id)
        val weaveChoice = byId.getValue(weaveRun.id)
        assertThat(sprintChoice.action.requiredBonusCard).isEqualTo(BonusPlay.Sprint)
        assertThat(weaveChoice.action.requiredBonusCard).isEqualTo(BonusPlay.Weave)
        val sprintPacked = step.policy.legalMoves[step.harness.legalRows.indexOf(sprintChoice)]
        val weavePacked = step.policy.legalMoves[step.harness.legalRows.indexOf(weaveChoice)]
        assertThat(sprintPacked[5]).isEqualTo(BonusPlay.Sprint.ordinal + 1)
        assertThat(weavePacked[5]).isEqualTo(BonusPlay.Weave.ordinal + 1)
    }

    @Test
    fun `Back on Your Feet play row wraps PlayBackOnYourFeet with requiredBonusCard`() {
        lateinit var prone: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Back on Your Feet`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            prone = addPlayer(5, 6, status = PlayerStatus.PRONE)
        }
        match.play(match.legalActions<LegalAction.StandUp>().single { it.playerId == prone.player.id })
        val step = api.aiStep(match)
        val choice = step.harness.legalRows.single { it.action is LegalAction.PlayBackOnYourFeet }
        assertThat(choice.action).isInstanceOf(LegalAction.PlayBackOnYourFeet::class.java)
        assertThat(choice.action.requiredBonusCard).isEqualTo(BonusPlay.`Back on Your Feet`)
        val packed = step.policy.legalMoves[step.harness.legalRows.indexOf(choice)]
        assertThat(packed[1]).isEqualTo(AiLegalKind.playBackOnYourFeet.ordinal)
        assertThat(packed[5]).isEqualTo(BonusPlay.`Back on Your Feet`.ordinal + 1)
    }

    @Test
    fun `obs hasBall is true for a loose-ball square and false for a player without the ball`() {
        val match = match {
            addPlayer(5, 6)
            addLooseBall(6, 8)
        }
        val cells = api.aiStep(match).policy.obs.cells
        val loose = cells[AiObsLayout.squareIndex(6, 8)]
        val occupant = cells[AiObsLayout.squareIndex(5, 6)]
        assertThat(loose.hasBall).isTrue()
        assertThat(loose.player).isNull()
        assertThat(loose.distToBall).isEqualTo(0)
        assertThat(occupant.hasBall).isFalse()
        assertThat(occupant.player).isNotNull()
        assertThat(occupant.distToBall).isEqualTo(
            AiObsLayout.distToBall(Coordinate(5, 6), Coordinate(6, 8)),
        )
    }

    @Test
    fun `obs hasBall is true for a carrier square`() {
        val match = match { holdingBall(addPlayer(5, 6)) }
        val held = api.aiStep(match).policy.obs.cells[AiObsLayout.squareIndex(5, 6)]
        assertThat(held.hasBall).isTrue()
        assertThat(held.player).isNotNull()
        assertThat(held.distToBall).isEqualTo(0)
        assertThat(held.pitchX).isEqualTo(AiObsLayout.pitchX0(Coordinate(5, 6), TeamSide.HOME))
        assertThat(held.pitchY).isEqualTo(AiObsLayout.pitchY0(Coordinate(5, 6), TeamSide.HOME))
    }

    @Test
    fun `packed legal rows jam actorHasBall and targetHasBall onto each move`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            runner = addPlayer(5, 6, profile = TestProfiles.withMove(4))
            addLooseBall(6, 8)
        }
        val step = api.aiStep(match)
        assertThat(step.policy.legalMoves).isNotEmpty()
        assertThat(step.policy.legalMoves.first()).hasSize(AiObsLayout.ACTION_FACTOR_COUNT)

        val pickup = step.harness.legalRows.first { it.action is LegalAction.Run &&(it.action as LegalAction.Run).targetCoordinate == Coordinate(6, 8) }
        val empty = step.harness.legalRows.first { it.action is LegalAction.Run &&(it.action as LegalAction.Run).playerId == runner.player.id &&(it.action as LegalAction.Run).targetCoordinate != Coordinate(6, 8) }
        val pickupPacked = step.policy.legalMoves[step.harness.legalRows.indexOf(pickup)]
        val emptyPacked = step.policy.legalMoves[step.harness.legalRows.indexOf(empty)]
        assertThat(pickupPacked[AiObsLayout.FACTOR_ACTOR_HAS_BALL]).isEqualTo(0)
        assertThat(pickupPacked[AiObsLayout.FACTOR_TARGET_HAS_BALL]).isEqualTo(1)
        assertThat(emptyPacked[AiObsLayout.FACTOR_ACTOR_HAS_BALL]).isEqualTo(0)
        assertThat(emptyPacked[AiObsLayout.FACTOR_TARGET_HAS_BALL]).isEqualTo(0)

        val carrierMatch = match { holdingBall(addPlayer(5, 6, profile = TestProfiles.withMove(4))) }
        val carrierStep = api.aiStep(carrierMatch)
        val carrierRun = carrierStep.harness.legalRows.first { it.action is LegalAction.Run }
        val carrierPacked = carrierStep.policy.legalMoves[carrierStep.harness.legalRows.indexOf(carrierRun)]
        assertThat(carrierPacked[AiObsLayout.FACTOR_ACTOR_HAS_BALL]).isEqualTo(1)
        assertThat(carrierPacked[AiObsLayout.FACTOR_TARGET_HAS_BALL]).isEqualTo(0)
    }

    @Test
    fun `AI step JSON nests LegalAction on harness rows`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        @Suppress("UNCHECKED_CAST")
        val raw = restTemplate.getForObject(
            "/api/ai/matches/${match.matchId.value}/step?forSeat=HOME",
            Map::class.java,
        ) as Map<String, Any?>

        assertThat(raw["schemaVersion"]).isEqualTo(AiObsLayout.SCHEMA_VERSION)
        assertThat(raw["actingSeat"]).isEqualTo("HOME")

        @Suppress("UNCHECKED_CAST")
        val obs = raw["obs"] as Map<String, Any?>
        assertThat(obs["phase"]).isInstanceOf(Number::class.java)

        @Suppress("UNCHECKED_CAST")
        val cells = obs["cells"] as List<Map<String, Any?>>
        assertThat(cells.first()["terrain"]).isInstanceOf(Number::class.java)

        @Suppress("UNCHECKED_CAST")
        val rows = raw["legalRows"] as List<Map<String, Any?>>
        assertThat(rows).isNotEmpty
        val first = rows.first()
        assertThat(first).containsKeys("action", "passingLegal", "softMaskLegal")
        assertThat(first).doesNotContainKeys("factors", "sugarFactors", "kindIndex", "legalActionId")
        @Suppress("UNCHECKED_CAST")
        val action = first["action"] as Map<String, Any?>
        assertThat(action["kind"]).isInstanceOf(String::class.java)
        assertThat(action["id"]).isInstanceOf(String::class.java)
    }
}
