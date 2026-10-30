package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.pending.OfferHoldTheLine
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * D1.D — bonus play windows: timing, one-type-per-turn, pause/resume after dice.
 */
class BonusPlayFrameworkApiTest : BaseApiTest() {

    private fun doOverIds() =
        ChallengeCardCatalog.allCards().filter { it.bonusPlay == BonusPlay.`Do-Over` }

    private fun handCards(homeHand: List<com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition>): CardState {
        val used = homeHand.map { it.id }.toSet()
        val actives = ChallengeCardCatalog.allCards().filter { it.id !in used }.take(3)
        return CardState(activeSlots = actives, deck = emptyList(), homeHand = homeHand)
    }

    @Test
    fun `Do-Over offered after block dice and only once per turn with two copies`() {
        val talents = doOverIds()
        assertThat(talents.size).isGreaterThanOrEqualTo(2)
        val match = match {
            cards(handCards(listOf(talents[0], talents[1])))
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        dice.queueBlockDice(BlockDieFace.SMASH) // Do-Over re-roll
        dice.queueD6(6)
        match.play(match.legalActions<LegalAction.Block>().first())

        val adjust = match.legalActions<LegalAction.AdjustBlockByDoOver>()
        assertThat(adjust).hasSize(1)
        assertThat(adjust.single().faces).containsExactly(BlockDieFace.SHOVE)
        assertThat(adjust).hasSize(1)

        match.play(adjust.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).containsExactly(talents[1])
        assertThat(loadMatch(match).cards.discard).contains(talents[0])
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.`Do-Over`)

        // Type already used — no second Do-Over adjust.
        assertThat(match.legalActions<LegalAction.AdjustBlockByDoOver>()).isEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
    }

    @Test
    fun `Skip Do-Over window resumes block resolution`() {
        val talent = doOverIds().first()
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(1)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(match.legalActions<LegalAction.AdjustBlockByDoOver>()).isNotEmpty()
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        // Shove follow-up (or end of open action) — Do-Over window itself is gone.
        assertThat(match.legalActions<LegalAction.AdjustBlockByDoOver>()).isEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions<LegalAction.DeclineBonus>()).isEmpty()
    }

    @Test
    fun `declining Hold the Line enters PLAYER_ACTION without re-offering`() {
        val match = match {
            homeBonus(BonusPlay.`Hold the Line`)
            phase(TurnPhase.PLAYER_ACTION)
            pendingDecision(
                OfferHoldTheLine(TeamSide.HOME),
            )
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
        }

        assertThat(match.legalActions<LegalAction.PlayBonusCard>().single().bonusPlay)
            .isEqualTo(BonusPlay.`Hold the Line`)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(match.legalActions<LegalAction.DeclineBonus>()).isEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions()).isNotEmpty()
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).isNotEmpty()
    }

    @Test
    fun `Do-Over skipped when block dice are already Smash`() {
        val talent = doOverIds().first()
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(1)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(1) // armour breaks — injury is also optimal, so no armour Do-Over either
        match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(talent)
        assertThat(loadMatch(match).board.players.getValue(target.player.id).status)
            .isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `Reserves tags placements then Play makes the action free`() {
        val reservesCard = ChallengeCardCatalog.allCards().first { it.bonusPlay == BonusPlay.`Fresh Legs` }
        lateinit var reserve: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Fresh Legs`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            reserve = addReservesPlayer()
        }

        assertThat(match.legalActions<LegalAction.Run>()).isNotEmpty()
        assertThat(match.legalActions<LegalAction.DeclineBonus>()).isEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        val placement = match.legalActions<LegalAction.Reserves>()
            .first { it.playerId == reserve.player.id }
        assertThat(placement.availableBonusCards).anyMatch { it == BonusPlay.`Fresh Legs` }

        match.play(placement)
        val play = match.legalActions<LegalAction.PlayBonusCard>().single()
        assertThat(play.bonusPlay).isEqualTo(BonusPlay.`Fresh Legs`)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)

        match.play(play)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(reservesCard)
    }

    @Test
    fun `Skip Reserves window resolves placement as budgeted`() {
        val reservesCard = ChallengeCardCatalog.allCards().first { it.bonusPlay == BonusPlay.`Fresh Legs` }
        lateinit var reserve: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Fresh Legs`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            reserve = addReservesPlayer()
        }
        val placement = match.legalActions<LegalAction.Reserves>()
            .first { it.playerId == reserve.player.id }
        match.play(placement)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(reservesCard)
    }

    @Test
    fun `Reserves Play onto loose ball bounces after bonus window`() {
        // Shake-loose: ballEncounter BOUNCE was dropped when rebuilding Reserves after Play/Skip pause.
        // Reserves prefer clear end-zone squares — threaten every other digout square with standing
        // opposition so the ball square is offered (rulebook “unless no other option”).
        val landing = Coordinate(5, 1)
        lateinit var reserve2: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Fresh Legs`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            reserve2 = addReservesPlayer()
            addLooseBall(landing.x, landing.y)
            for (x in 1..11) {
                if (x == landing.x) continue
                addPlayer(x, 2, team = TeamSide.AWAY, profile = TestProfiles.lineman())
            }
        }
        val placement = match.legalActions<LegalAction.Reserves>()
            .first { it.playerId == reserve2.player.id && it.placement == landing }
        assertThat(placement.ballEncounter).isEqualTo(BallEncounter.BOUNCE)

        dice.queueD8(1)
        match.play(placement)
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        assertPiece(match.pieces(), reserve2.player.id, status = PlayerStatus.STANDING, x = landing.x, y = landing.y)
        assertThat(match.pieces().looseBallLocations).isNotEmpty
        assertThat(match.pieces().looseBallLocations).doesNotContain(landing)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
    }

    @Test
    fun `Back on Your Feet Play then Reserves Decline does not leave Stand Up open`() {
        // Shake-loose / lookahead: Decline on a Reserves window must not begin Reserves while a
        // FREE Stand Up from Back on Your Feet is still open.
        lateinit var prone: PlacedPlayer
        lateinit var reserve: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Back on Your Feet`, BonusPlay.`Fresh Legs`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            prone = addPlayer(5, 6, status = PlayerStatus.PRONE)
            reserve = addReservesPlayer()
        }

        match.play(match.legalActions<LegalAction.StandUp>().single { it.playerId == prone.player.id })
        match.play(match.legalActions<LegalAction.PlayBonusCard>().first { it.bonusPlay == BonusPlay.`Back on Your Feet` })
        assertPiece(match.pieces(), prone.player.id, status = PlayerStatus.STANDING, x = 5, y = 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        assertThat(loadMatch(match).currentTurnState.currentPlayerAction).isNull()

        val placement = match.legalActions<LegalAction.Reserves>()
            .first { it.playerId == reserve.player.id }
        assertThat(placement.availableBonusCards).anyMatch { it == BonusPlay.`Fresh Legs` }
        match.play(placement)
        val decline = match.legalActions<LegalAction.DeclineBonus>()
        assertThat(decline).isNotEmpty
        match.play(decline.first())

        assertPiece(match.pieces(), reserve.player.id, status = PlayerStatus.STANDING, x = placement.placement.x, y = placement.placement.y)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertThat(loadMatch(match).currentTurnState.currentPlayerAction).isNull()
    }

    @Test
    fun `Sprint bonus-only Run auto-plays the card without a Play* window`() {
        val sprintCard = ChallengeCardCatalog.allCards().first { it.bonusPlay == BonusPlay.Sprint }
        lateinit var runner: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Sprint)
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(6))
        }

        // No Play* window when only Sprint is in hand (geometry is attached to Runs).
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()

        val sprintRun = match.legalActions<LegalAction.Run>()
            .first { it.path.steps == 7 && it.playerId == runner.player.id }
        assertThat(sprintRun.requiredBonusCard).isEqualTo(BonusPlay.Sprint)
        assertThat(sprintRun.id.value).contains("_BONUS_Sprint")

        val response = match.play(sprintRun)
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.BonusPlayed>()).isNotEmpty()
        assertPiece(match.pieces(), runner.player.id, x = sprintRun.path.squares.last().x, y = sprintRun.path.squares.last().y)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(sprintCard)
        assertThat(loadMatch(match).currentTurnState.bonusModifiers.holdTheLine).isFalse()
        assertThat(loadMatch(match).currentTurnState.bonusModifiers.gangUp).isFalse()
    }
}
