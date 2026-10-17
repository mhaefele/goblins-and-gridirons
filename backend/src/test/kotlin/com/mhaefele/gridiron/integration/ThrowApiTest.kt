package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DiceCheckResult
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.assertEventsContain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThrowApiTest : BaseApiTest() {

    @Test
    fun `successful short throw transfers ball to catcher`() {
        lateinit var thrower: PlacedPlayer
        lateinit var catcher: PlacedPlayer
        val match = match {
            thrower = holdingBall(addPlayer(5, 6))
            catcher = addPlayer(5, 4, team = thrower.team)
        }

        val throwAction = match.singleLegalAction<LegalAction.Throw> { it.target.destination == Coordinate(5, 4) }

        dice.queueD6(6)
        val response = match.play(throwAction)
        val pieces = match.pieces()

        assertThat(player(pieces, thrower.player.id).holdsBall).isFalse()
        assertThat(player(pieces, catcher.player.id).holdsBall).isTrue()
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 6,
                threshold = 5,
                modifier = 0,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.BallThrown(Coordinate(5, 6), Coordinate(5, 4)),
        )
    }

    @Test
    fun `hand off transfers ball without rolling`() {
        lateinit var thrower: PlacedPlayer
        lateinit var receiver: PlacedPlayer
        val match = match {
            thrower = holdingBall(addPlayer(5, 6))
            receiver = addPlayer(6, 6, team = thrower.team)
        }

        val response = match.play<LegalAction.Throw>()
        val pieces = match.pieces()

        assertThat(player(pieces, thrower.player.id).holdsBall).isFalse()
        assertThat(player(pieces, receiver.player.id).holdsBall).isTrue()
        assertEventsContain(
            response,
            DomainEvent.BallThrown(Coordinate(5, 6), Coordinate(6, 6)),
        )
    }

    @Test
    fun `failed throw bounces from target square`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(5, 4, team = thrower.team)
        }

        val throwAction = match.singleLegalAction<LegalAction.Throw> { it.target.destination == Coordinate(5, 4) }

        dice.queueD6(2) // throw check — miss
        dice.queueD8(1) // bounce direction
        val response = match.play(throwAction)
        val pieces = match.pieces()

        assertThat(pieces.players.none { it.holdsBall }).isTrue()
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 2,
                threshold = 5,
                modifier = 0,
                checkResult = DiceCheckResult.FAIL,
            ),
            DomainEvent.BallThrowMissed(Coordinate(5, 6), Coordinate(5, 4)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 1),
        )
    }

    @Test
    fun `fumble bounces from thrower square`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(5, 4, team = thrower.team)
        }

        val throwAction = match.singleLegalAction<LegalAction.Throw> { it.target.destination == Coordinate(5, 4) }

        dice.queueD6(1) // natural 1 — fumble
        dice.queueD8(1) // bounce direction
        val response = match.play(throwAction)
        val pieces = match.pieces()

        assertThat(player(pieces, thrower.player.id).holdsBall).isFalse()
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 1,
                threshold = 5,
                modifier = 0,
                checkResult = DiceCheckResult.FUMBLE,
            ),
            DomainEvent.BallDropped(thrower.player.id, Coordinate(5, 6)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 1),
        )
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(5, 5))
    }

    @Test
    fun `modified one fumble bounces from thrower square`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(5, 1, team = thrower.team) // distance 5 → Long (−1)
        }

        val throwAction = match.singleLegalAction<LegalAction.Throw> { it.target.destination == Coordinate(5, 1) }

        dice.queueD6(2) // natural 2 + Long −1 → modified 1 → fumble
        dice.queueD8(1) // bounce direction
        val response = match.play(throwAction)
        val pieces = match.pieces()

        assertThat(player(pieces, thrower.player.id).holdsBall).isFalse()
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 2,
                threshold = 5,
                modifier = -1,
                checkResult = DiceCheckResult.FUMBLE,
            ),
            DomainEvent.BallDropped(thrower.player.id, Coordinate(5, 6)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 1),
        )
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(5, 5))
    }
}
