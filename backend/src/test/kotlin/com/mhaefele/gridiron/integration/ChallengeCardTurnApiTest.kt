package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.SEND_THEM_OFF
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.STRIP_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.HUDDLE_UP
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.THREAD_THE_NEEDLE
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PUSH_FORWARD
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PLAY_TO_THE_STANDS
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChallengeCardTurnApiTest : BaseApiTest() {

    @Test
    fun `Run that picks up then continues moving claims Push Forward`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            challengeCards(PUSH_FORWARD, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            runner = addPlayer(5, 2, profile = TestProfiles.withMove(6))
            addLooseBall(5, 3)
        }

        val runToBall = match.legalActions<LegalAction.Run>()
            .first { it.path.squares == listOf(Coordinate(5, 2), Coordinate(5, 3)) }
        val pickupResponse = match.play(runToBall)

        assertThat(pickupResponse.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(TeamSide.HOME, PlayerDecisionType.RUN_CONTINUE, playerId = runner.id, ),
        )
        assertThat(player(match.pieces(), runner.player.id).holdsBall).isTrue()

        val continueRun = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 8) }
        val response = match.play(continueRun)

        val claimed = response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()
        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().challengeTitle).isEqualTo("Push Forward!")
        assertThat(match.gameState().homeScore).isEqualTo(1)
        assertThat(match.bonusHand().cards.map { it.challengeTitle }).contains(PUSH_FORWARD.displayName)
        assertThat(match.gameState().challenges.activeSlots.map { it?.title })
            .doesNotContain(PUSH_FORWARD.displayName)
    }

    @Test
    fun `Run that only picks up on the last square does not claim Push Forward`() {
        val match = match {
            challengeCards(PUSH_FORWARD, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 6, profile = TestProfiles.withMove(6))
            addLooseBall(5, 10)
        }

        val pickupRun = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 10) }
        val response = match.play(pickupRun)

        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).isEmpty()
        assertThat(match.gameState().challenges.activeSlots.map { it?.title })
            .contains(PUSH_FORWARD.displayName)
        assertThat(match.gameState().homeScore).isZero()
    }

    @Test
    fun `Coach A turn 1 block does not claim Drop Them`() {
        val match = match {
            turnNumber(1)
            challengeCards(DROP_THEM, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).isEmpty()
        assertThat(match.gameState().homeScore).isZero()
        assertThat(match.gameState().challenges.activeSlots.map { it?.title })
            .contains(DROP_THEM.displayName)
    }

    @Test
    fun `later turn auto-claims Drop Them after knockdown`() {
        val match = match {
            challengeCards(GRAB_IT, DROP_THEM, PLAY_TO_THE_STANDS)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).hasSize(1)
        assertThat(match.gameState().homeScore).isEqualTo(2)
        assertThat(match.gameState().challenges.activeSlots[1]).isNull()
    }

    @Test
    fun `later turn offers claim choice when two Drop Them are eligible`() {
        val match = match {
            challengeCards(DROP_THEM, DROP_THEM, PLAY_TO_THE_STANDS)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        match.play(match.legalActions<LegalAction.Block>().first())

        val claims = match.legalActions<LegalAction.ClaimChallenge>()
        assertThat(claims).hasSize(2)
        assertThat(claims.map { it.slotIndex }).containsExactlyInAnyOrder(0, 1)

        val response = match.play(claims.first { it.slotIndex == 1 })
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).hasSize(1)
        assertThat(match.gameState().homeScore).isEqualTo(2)
        assertThat(match.gameState().challenges.activeSlots[1]).isNull()
        assertThat(match.gameState().challenges.activeSlots[0]?.title)
            .isEqualTo(DROP_THEM.displayName)
    }

    @Test
    fun `later turn auto-claims Strip It when smash bounce is picked up by open teammate`() {
        lateinit var carrier: PlacedPlayer
        lateinit var openTeammate: PlacedPlayer
        val match = match {
            challengeCards(STRIP_IT, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY))
            openTeammate = addPlayer(6, 5)
        }

        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.SMASH)
        dice.queueD6(6)
        dice.queueD8(1)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(player(match.pieces(), openTeammate.player.id).holdsBall).isTrue()
        assertThat(player(match.pieces(), carrier.player.id).holdsBall).isFalse()
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).hasSize(1)
        assertThat(match.gameState().homeScore).isEqualTo(2)
        assertThat(match.gameState().challenges.activeSlots[0]).isNull()
    }

    @Test
    fun `later turn offers Free Up and Drop Them when smash bounce is picked up by blocker`() {
        lateinit var blocker: PlacedPlayer
        lateinit var carrier: PlacedPlayer
        val match = match {
            challengeCards(STRIP_IT, DROP_THEM, PLAY_TO_THE_STANDS)
            blocker = addPlayer(5, 6, profile = TestProfiles.withMove(2))
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY))
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        dice.queueD8(7)
        match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(player(match.pieces(), blocker.player.id).holdsBall).isTrue()
        assertThat(player(match.pieces(), carrier.player.id).holdsBall).isFalse()
        val claims = match.legalActions<LegalAction.ClaimChallenge>()
        assertThat(claims.map { it.slotIndex }).containsExactlyInAnyOrder(0, 1)
        assertThat(claims.map { it.challengeTitle }).containsExactlyInAnyOrder(
            STRIP_IT.displayName,
            DROP_THEM.displayName,
        )
    }

    @Test
    fun `later turn does not claim Strip It when smash bounce is hung onto by open opposition`() {
        lateinit var carrier: PlacedPlayer
        lateinit var oppositionRetainer: PlacedPlayer
        val match = match {
            challengeCards(STRIP_IT, DROP_THEM, PLAY_TO_THE_STANDS)
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY))
            oppositionRetainer = addPlayer(7, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        dice.queueD8(3)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(player(match.pieces(), oppositionRetainer.player.id).holdsBall).isTrue()
        assertThat(player(match.pieces(), carrier.player.id).holdsBall).isFalse()
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).hasSize(1)
        assertThat(match.gameState().homeScore).isEqualTo(2)
        assertThat(match.gameState().challenges.activeSlots[0]?.title)
            .isEqualTo(STRIP_IT.displayName)
        assertThat(match.gameState().challenges.activeSlots[1]).isNull()
        assertThat(match.legalActions<LegalAction.ClaimChallenge>()).isEmpty()
    }

    @Test
    fun `later turn offers Free Up Drop Them and Send Them Off after Ball Guard bounce`() {
        lateinit var ballHolder: PlacedPlayer
        // Mid-action Ball Guard pauses across an HTTP resume; claim facts must survive that pause.
        val match = match {
            challengeCards(STRIP_IT, DROP_THEM, SEND_THEM_OFF)
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            ballHolder = holdingBall(
                addPlayer(
                    6,
                    6,
                    team = TeamSide.AWAY,
                    profile = TeamCatalog.dwarves().roster.first { it.name == "Runner" },
                ),
            )
        }

        dice.queueBlockDice(BlockDieFace.FLATTEN)
        dice.queueD6(1)
        val blockResponse = match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(blockResponse.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.AWAY,
                PlayerDecisionType.BLOCK_CONTINUE,
                ability = PlayerAbility.`Ball Guard`,
                playerId = ballHolder.id,
            ),
        )
        assertThat(blockResponse.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>())
            .isEmpty()

        val place = match.legalActions<LegalAction.PlaceLooseBall>().first()
        match.play(place)

        val claims = match.legalActions<LegalAction.ClaimChallenge>()
        assertThat(claims.map { it.slotIndex }).containsExactlyInAnyOrder(0, 1, 2)
        assertThat(claims.map { it.challengeTitle }).containsExactlyInAnyOrder(
            STRIP_IT.displayName,
            DROP_THEM.displayName,
            SEND_THEM_OFF.displayName,
        )

        val stripIt = claims.first { it.slotIndex == 0 }
        val claimResponse = match.play(stripIt)
        assertThat(claimResponse.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>())
            .hasSize(1)
        assertThat(match.gameState().homeScore).isEqualTo(2)
        assertThat(match.gameState().challenges.activeSlots[0]).isNull()
    }

    @Test
    fun `ReplaceChallenge discards chosen slot and draws`() {
        val deckTopAfterActives = ChallengeCardCatalog.allIds()[3]
        val match = match {
            challengeCards(GRAB_IT, GRAB_IT, HUDDLE_UP)
            phase(TurnPhase.PRE_TURN)
            actionsRemaining(0)
            pendingDecision(ReplaceChallenge())
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
            addLooseBall(6, 8)
        }

        val replaces = match.legalActions<LegalAction.ReplaceChallenge>()
        assertThat(replaces).hasSize(3)
        val response = match.play(replaces.first { it.slotIndex == 2 })

        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardReplaced>()).hasSize(1)
        assertThat(match.gameState().challenges.discardCount).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `end of turn keeps a bonus hand of exactly three`() {
        val hand = spareBonusCards().take(3)
        val match = handLimitMatch(hand)

        val response = match.playAny<LegalAction>()
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeHandTrimmed>()).isEmpty()
    }

    @Test
    fun `end of turn discards bonus hand down to three not two`() {
        val hand = spareBonusCards().take(5)
        val match = handLimitMatch(hand)

        val response = match.playAny<LegalAction>()
        val trimmed = response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeHandTrimmed>().single()
        assertThat(trimmed.handSize).isEqualTo(3)
        assertThat(trimmed.discardedBonusPlays).containsExactly(
            hand[0].bonusPlay,
            hand[1].bonusPlay,
        )
    }

    private fun handLimitActives() = listOf(PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE, SEND_THEM_OFF)

    private fun spareBonusCards() =
        ChallengeCardCatalog.allCards().filter { it.challenge !in handLimitActives() }

    private fun handLimitMatch(hand: List<com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition>): MatchApi =
        match {
            bothTeamsDeployed()
            challengeCards(*handLimitActives().toTypedArray())
            homeHand(hand)
            actionsRemaining(1)
            addLooseBall(6, 8)
        }
}
