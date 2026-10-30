package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.ai.dto.AiLegalKind
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.AiTeamRel
import com.mhaefele.gridiron.api.ai.dto.AiTerrain
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
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
    fun `AI step returns schema version acting seat and factored legals`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val step = api.aiStep(match)

        assertThat(step.schemaVersion).isEqualTo(AiObsLayout.SCHEMA_VERSION)
        assertThat(step.schemaVersion).isEqualTo(AiObsLayout.SCHEMA_VERSION)
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

        val run = step.harness.legalRows.first { it.kind == AiLegalKind.run }
        assertThat(run.factors).hasSize(2)
        assertThat(run.factors[0]).isInstanceOf(AiFactor.Actor::class.java)
        assertThat(run.factors[1]).isInstanceOf(AiFactor.BoardTarget::class.java)
        assertThat(run.legalActionId.value).isNotBlank()
        assertThat(run.potentialChallengeClaims).isNotNull()
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
        assertThat(homeView.policy.obs.cells[absoluteIndex]).isEqualTo(awayView.policy.obs.cells[flippedIndex])

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
    fun `AWAY perspective remaps legal board targets and actor playerIndex is stable`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val homeStep = api.aiStep(match, perspectiveSeat = TeamSide.HOME)
        val awayStep = api.aiStep(match, perspectiveSeat = TeamSide.AWAY)

        val homeRun = homeStep.harness.legalRows.first { it.kind == AiLegalKind.run }
        val awayRun = awayStep.harness.legalRows.first { it.legalActionId == homeRun.legalActionId }

        val homeActor = (homeRun.factors[0] as AiFactor.Actor).playerIndex
        val homeTarget = (homeRun.factors[1] as AiFactor.BoardTarget).boardIndex
        val awayActor = (awayRun.factors[0] as AiFactor.Actor).playerIndex
        val awayTarget = (awayRun.factors[1] as AiFactor.BoardTarget).boardIndex

        assertThat(awayActor).isEqualTo(homeActor)
        assertThat(awayTarget).isEqualTo(AiObsLayout.toPerspectiveIndex(homeTarget, TeamSide.AWAY))

        val homeCell = homeStep.policy.obs.cells.first { it.player?.playerIndex == homeActor }
        assertThat(homeCell.player?.playerIndex).isEqualTo(homeActor)
        val awayCell = awayStep.policy.obs.cells.first { it.player?.playerIndex == awayActor && it.player?.teamRel == AiTeamRel.THEM }
        assertThat(awayCell.player?.playerIndex).isEqualTo(awayActor)
    }

    @Test
    fun `submit uses legalActionId sidecar from AI step`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val before = api.aiStep(match)
        val run = before.harness.legalRows.first { it.kind == AiLegalKind.run }
        api.play(match.matchId, run.legalActionId)

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
    fun `GUI legal-actions and AI step share the same legalActionId set`() {
        dice.queueD6(4)
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())
        placeAllInitialPieces(match)

        val guiIds = api.legalActions(match).map { it.id }.toSet()
        val aiIds = api.aiStep(match).harness.legalRows.map { it.legalActionId }.toSet()
        assertThat(aiIds).isEqualTo(guiIds)

        val guiRun = api.legalActions<LegalAction.Run>(match).first()
        val aiRun = api.aiStep(match).harness.legalRows.first { it.legalActionId == guiRun.id }
        assertThat(aiRun.kind).isEqualTo(AiLegalKind.run)
    }

    @Test
    fun `Sprint and Weave twins to the same destination collapse to one AI row with Sprint sugar`() {
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
        val sharedChoices = step.harness.legalRows.filter { it.legalActionId == sprintRun.id || it.legalActionId == weaveRun.id }
        assertThat(sharedChoices).hasSize(1)
        val kept = sharedChoices.single()
        assertThat(kept.legalActionId).isEqualTo(sprintRun.id)
        assertThat(kept.factors.filterIsInstance<AiFactor.AttachedBonus>()).isEmpty()
        assertThat(kept.sugarFactors.filterIsInstance<AiFactor.AttachedBonus>().single().index).isEqualTo(0)
        assertThat(kept.factors.filterIsInstance<AiFactor.BoardTarget>()).hasSize(1)
    }

    @Test
    fun `Back on Your Feet play row is playBackOnYourFeet with empty factors`() {
        lateinit var prone: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Back on Your Feet`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            prone = addPlayer(5, 6, status = PlayerStatus.PRONE)
        }
        match.play(match.legalActions<LegalAction.StandUp>().single { it.playerId == prone.player.id })
        val choice = api.aiStep(match).harness.legalRows.single { it.kind == AiLegalKind.playBackOnYourFeet }
        assertThat(choice.factors).isEmpty()
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
        assertThat(occupant.hasBall).isFalse()
        assertThat(occupant.player).isNotNull()
    }

    @Test
    fun `obs hasBall is true for a carrier square`() {
        val match = match { holdingBall(addPlayer(5, 6)) }
        val held = api.aiStep(match).policy.obs.cells[AiObsLayout.squareIndex(5, 6)]
        assertThat(held.hasBall).isTrue()
        assertThat(held.player).isNotNull()
    }

    @Test
    fun `AI step JSON encodes packable obs enums as ordinals and kind as name plus index`() {
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
        assertThat(first["kind"]).isInstanceOf(String::class.java)
        assertThat(first["kindIndex"]).isInstanceOf(Number::class.java)
        assertThat((first["kindIndex"] as Number).toInt())
            .isEqualTo(AiLegalKind.fromWireName(first["kind"] as String).ordinal)
    }
}
