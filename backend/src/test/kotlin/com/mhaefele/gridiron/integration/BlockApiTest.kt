package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DiceCheckResult
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertEventsContain
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BlockApiTest : BaseApiTest() {

    @Test
    fun `smash on ball carrier drops the ball and bounces`() {
        lateinit var carrier: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY))
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6) // armour holds
        dice.queueD8(1) // bounce north from carrier square
        match.play(ActionType.BLOCK)

        val pieces = match.pieces()
        assertThat(player(pieces, carrier.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(pieces, carrier.player.id).holdsBall).isFalse()
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(6, 5))
    }

    @Test
    fun `smash knocks target prone when armour holds`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH) // block result
        dice.queueD6(6) // armour holds
        val response = match.play(ActionType.BLOCK)

        assertThat(player(match.pieces(), target.player.id).status)
            .isEqualTo(PlayerStatus.PRONE)
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.BLOCK,
                blockFaces = listOf(BlockDieFace.SMASH),
                blockResult = BlockDieFace.SMASH,
            ),
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 6,
                threshold = 2,
                modifier = 0,
                checkResult = DiceCheckResult.PASS,
            ),
        )
    }

    @Test
    fun `flatten injury sends target to reserves on failed armour`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.FLATTEN) // block result
        dice.queueD6(2) // armour fails
        val response = match.play(ActionType.BLOCK)

        assertThat(player(match.pieces(), target.player.id).status)
            .isEqualTo(PlayerStatus.RESERVES)
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.BLOCK,
                blockFaces = listOf(BlockDieFace.FLATTEN),
                blockResult = BlockDieFace.FLATTEN,
            ),
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 2,
                threshold = 2,
                modifier = -1,
                checkResult = DiceCheckResult.FAIL,
            ),
            DomainEvent.PlayerInjured(target.player.id, Coordinate(6, 6)),
        )
    }

    @Test
    fun `shove followup allows blocker to move into target square`() {
        lateinit var blocker: PlacedPlayer
        val match = match {
            blocker = addPlayer(5, 6)
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        val blockResponse = match.play(ActionType.BLOCK)

        assertThat(blockResponse.events.bareEvents().filterIsInstance<DomainEvent.ActionComplete>()).isEmpty()
        assertThat(blockResponse.events.bareEvents()).anyMatch { it is DomainEvent.InputRequired.PlayerAction && it.decisionType == PlayerDecisionType.BLOCK_CONTINUE }

        assertThat(match.legalActions()).containsExactlyInAnyOrder(
            LegalAction.Skip(blocker.player.id),
            LegalAction.Mark(blocker.player.id, Path(listOf(Coordinate(5, 6), Coordinate(6, 6)))),
        )

        val markMove = match.legalActions<LegalAction.Mark>()
            .single { it.targetCoordinate == Coordinate(6, 6) }
        val followUpResponse = match.play(markMove)

        assertThat(followUpResponse.events.bareEvents()).contains(
            DomainEvent.ActionComplete(blocker.player.id, Coordinate(6, 6), ActionType.BLOCK),
        )
        assertPiece(match.pieces(), blocker.player.id, PlayerStatus.STANDING, x = 6, y = 6)
    }

    @Test
    fun `shove followup mark does not burn same-action-once Mark`() {
        lateinit var blocker: PlacedPlayer
        val match = match {
            blocker = addPlayer(5, 6)
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        match.play(match.legalActions<LegalAction.Block>().single())
        match.play(
            match.legalActions<LegalAction.Mark>()
                .single { it.targetCoordinate == Coordinate(6, 6) },
        )

        assertThat(loadMatch(match).currentTurnState.actionSequenceFor(blocker.player.id))
            .containsExactly(ActionType.BLOCK)

        // Follow-up left the blocker Marked; Sidestep Open, then Mark must still be legal.
        val sidestep = match.legalActions<LegalAction.Sidestep>()
            .first { it.playerId == blocker.player.id && it.targetCoordinate == Coordinate(5, 6) }
        match.play(sidestep)

        assertThat(
            match.legalActions<LegalAction.Mark>()
                .any { it.playerId == blocker.player.id },
        ).isTrue()
    }

    @Test
    fun `shove into occupied square knocks target down`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            target = addPlayer(6, 6, team = TeamSide.AWAY)
            addPlayer(7, 6, team = TeamSide.AWAY)
        }

        val block = match.legalActions<LegalAction.Block>().single()

        dice.queueBlockDice(BlockDieFace.SHOVE) // block result
        dice.queueD6(6) // armour holds on collision knockdown
        match.play(block)

        assertPiece(match.pieces(), target.player.id, PlayerStatus.PRONE, x = 6, y = 6)
    }

    @Test
    fun `tackle ends blocker turn`() {
        lateinit var blocker: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            blocker = addPlayer(5, 6)
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss/Tackle turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        val block = match.legalActions<LegalAction.Block>().single()

        dice.queueBlockDice(BlockDieFace.TACKLE) // block result
        dice.queueD6(6) // armour holds
        match.play(block)

        assertThat(match.legalActions())
            .noneMatch { it is LegalAction.PlayerLegalAction && it.playerId == blocker.player.id }
    }

    @Test
    fun `miss ends blocker turn without moving target`() {
        lateinit var blocker: PlacedPlayer
        lateinit var target: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            blocker = addPlayer(5, 6)
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss/Tackle turn-end
            target = addPlayer(6, 6, team = TeamSide.AWAY)
        }

        val block = match.legalActions<LegalAction.Block>().single()

        dice.queueBlockDice(BlockDieFace.MISS) // block result
        match.play(block)

        assertPiece(match.pieces(), target.player.id, x = 6, y = 6)
        assertThat(match.legalActions())
            .noneMatch { it is LegalAction.PlayerLegalAction && it.playerId == blocker.player.id }
    }

    @Test
    fun `assisted flatten injures marked target`() {
        lateinit var blocker: PlacedPlayer
        lateinit var target: PlacedPlayer
        val match = match {
            blocker = addPlayer(5, 6)
            addPlayer(5, 5, team = blocker.team)
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        val block = match.legalActions<LegalAction.Block>()
            .first { it.playerId == blocker.player.id && it.destination == Coordinate(6, 6) }

        dice.queueBlockDice(BlockDieFace.MISS, BlockDieFace.FLATTEN) // assisted pool — flatten wins
        dice.queueD6(2) // armour fails
        val response = match.play(block)

        assertThat(player(match.pieces(), target.player.id).status)
            .isEqualTo(PlayerStatus.RESERVES)
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.BLOCK,
                blockFaces = listOf(BlockDieFace.MISS, BlockDieFace.FLATTEN),
                blockResult = BlockDieFace.FLATTEN,
            ),
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 2,
                threshold = 2,
                modifier = -1,
                checkResult = DiceCheckResult.FAIL,
            ),
        )
    }

    @Test
    fun `open player cannot block`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            runner = addPlayer(5, 6)
            addPlayer(7, 6, team = TeamSide.AWAY)
        }

        assertThat(match.legalActionsForPlayer(runner, ActionType.BLOCK))
            .isEqualTo(emptyList<LegalAction>())
    }
}
