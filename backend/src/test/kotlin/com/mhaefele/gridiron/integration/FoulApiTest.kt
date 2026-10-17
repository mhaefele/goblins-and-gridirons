package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.dice.dto.FoulResult
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Direction
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.assertEventsContain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FoulApiTest : BaseApiTest() {

    @Test
    fun `open player adjacent to prone opposition can foul`() {
        lateinit var fouler: PlacedPlayer
        lateinit var prone: PlacedPlayer
        val match = match {
            fouler = addPlayer(5, 6)
            prone = addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        val fouls = match.legalActions<LegalAction.Foul>()
        assertThat(fouls).hasSize(1)
        assertThat(fouls.single().destination).isEqualTo(Coordinate(5, 5))
        assertThat(fouls.single().playerId).isEqualTo(fouler.player.id)
        assertThat(match.legalActionsForPlayer(fouler, ActionType.FOUL)).isNotEmpty()
        assertThat(match.legalActions<LegalAction.Foul>().map { it.destination })
            .containsExactly(Coordinate(5, 5))
        // Prone opposition has no foul actions of their own.
        assertThat(match.legalActionsForPlayer(prone)).isEmpty()
    }

    @Test
    fun `got them injures target without armour`() {
        lateinit var fouler: PlacedPlayer
        lateinit var prone: PlacedPlayer
        val match = match {
            fouler = addPlayer(5, 6)
            prone = addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        val response = match.play(match.legalActions<LegalAction.Foul>().single())
        val pieces = match.pieces()

        assertThat(player(pieces, prone.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(player(pieces, fouler.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.FOUL,
                blockFaces = listOf(BlockDieFace.SMASH),
                blockResult = BlockDieFace.SMASH,
                foulResult = FoulResult.GOT_THEM,
            ),
            DomainEvent.PlayerInjured(prone.player.id, Coordinate(5, 5)),
        )
    }

    @Test
    fun `spotted ejects fouler and leaves target prone`() {
        lateinit var fouler: PlacedPlayer
        lateinit var prone: PlacedPlayer
        val match = match {
            fouler = addPlayer(5, 6)
            prone = addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.Foul>().single())
        val pieces = match.pieces()

        assertThat(player(pieces, fouler.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(player(pieces, prone.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(pieces, prone.player.id).at).isEqualTo(Coordinate(5, 5))
    }

    @Test
    fun `spotted ball carrier ejects even when bounce returns to them`() {
        lateinit var fouler: PlacedPlayer
        val match = match {
            fouler = holdingBall(addPlayer(5, 6))
            addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        // Drop at (5,6) → N onto prone (rebounce) → S back onto fouler; they must not re-pick up before eject.
        dice.queueBounce(Direction.N, Direction.S)
        match.play(match.legalActions<LegalAction.Foul>().single())
        val pieces = match.pieces()

        assertThat(player(pieces, fouler.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(player(pieces, fouler.player.id).holdsBall).isFalse()
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(5, 6))
    }

    @Test
    fun `take that injures target and ejects fouler`() {
        lateinit var fouler: PlacedPlayer
        lateinit var prone: PlacedPlayer
        val match = match {
            fouler = addPlayer(5, 6)
            prone = addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        dice.queueBlockDice(BlockDieFace.TACKLE)
        match.play(match.legalActions<LegalAction.Foul>().single())
        val pieces = match.pieces()

        assertThat(player(pieces, prone.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(player(pieces, fouler.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `slipped ends fouler turn without injuring`() {
        lateinit var fouler: PlacedPlayer
        lateinit var prone: PlacedPlayer
        val match = match {
            fouler = addPlayer(5, 6)
            addPlayer(3, 6) // teammate keeps a non-empty menu after foul turn-end
            prone = addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        val response = match.play(match.legalActions<LegalAction.Foul>().single())
        val pieces = match.pieces()

        assertThat(player(pieces, fouler.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(pieces, prone.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertEventsContain(response, DomainEvent.PlayerTurnEnded(fouler.player.id))
        assertThat(match.legalActionsForPlayer(fouler)).isEmpty()
    }

    @Test
    fun `marked player cannot foul even when adjacent to prone`() {
        val match = match {
            addPlayer(5, 6)
            addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
            addPlayer(6, 6, team = TeamSide.AWAY) // standing opposition → Marked
        }

        assertThat(match.legalActions<LegalAction.Foul>()).isEmpty()
    }
}
