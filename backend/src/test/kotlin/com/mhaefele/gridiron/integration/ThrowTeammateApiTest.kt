package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DiceCheckResult
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.PlayerAbility.`Ball Guard`
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.model.pending.PlayerDecisionType.REGULAR_PLAYER_ACTION
import com.mhaefele.gridiron.model.pending.PlayerDecisionType.THROW_TEAMMATE_CONTINUE
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TeamSide.AWAY
import com.mhaefele.gridiron.shared.TeamSide.HOME
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TeamCatalogTestHelper
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertEventsContain
import com.mhaefele.gridiron.support.assertEventsExactly
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThrowTeammateApiTest : BaseApiTest() {

    private val throwerProfile = TeamCatalogTestHelper.throwTeammateProfile()
    private val teammateProfile = TeamCatalogTestHelper.throwableTeammateOf(throwerProfile)
    private val ballGuardProfile = TeamCatalogTestHelper.profileWith(`Ball Guard`)
    private val occupantProfile = TestProfiles.lineman()
    private val throwThreshold = (throwerProfile.stats.throwStat as ThrowStat.Threshold).minimumRoll
    private val ballGuardArmour = (ballGuardProfile.stats.armour as ArmourStat.Threshold).minimumRoll
    private val occupantArmour = (occupantProfile.stats.armour as ArmourStat.Threshold).minimumRoll

    @Test
    fun `successful throw teammate lands standing on empty square`() {
        lateinit var thrown: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
        }

        match.play(
            match.legalActions<LegalAction.ThrowTeammateSelect>()
                .single { it.teammateId == thrown.player.id },
        )

        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(6)
        val response = match.play(landing)
        val pieces = match.pieces()

        assertThat(player(pieces, thrown.player.id).at).isEqualTo(Coordinate(5, 3))
        assertThat(player(pieces, thrown.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 6,
                threshold = throwThreshold,
                modifier = 0,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.PlayerMoved(thrown.player.id, Coordinate(5, 5), Coordinate(5, 3)),
        )
    }

    @Test
    fun `cancel after select restores other action choices`() {
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
        }

        assertThat(match.legalActions<LegalAction.Run>()).isNotEmpty()
        assertThat(match.legalActions<LegalAction.ThrowTeammateSelect>()).isNotEmpty()

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().first())

        assertThat(match.legalActions<LegalAction.ThrowTeammate>()).isNotEmpty()
        assertThat(match.legalActions<LegalAction.CancelThrowTeammate>()).hasSize(1)
        assertThat(match.legalActions<LegalAction.Run>()).isEmpty()

        match.play(match.legalActions<LegalAction.CancelThrowTeammate>().single())

        assertThat(match.legalActions<LegalAction.Run>()).isNotEmpty()
        assertThat(match.legalActions<LegalAction.ThrowTeammateSelect>()).isNotEmpty()
        assertThat(match.legalActions<LegalAction.ThrowTeammate>()).isEmpty()
    }

    @Test
    fun `landing squares stay within throw ruler not whole pitch`() {
        val match = match {
            // Corner thrower — far corner is beyond Euclidean 8.
            val thrower = addPlayer(1, 1, profile = throwerProfile)
            addPlayer(1, 2, team = thrower.team, profile = teammateProfile)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landings = match.legalActions<LegalAction.ThrowTeammate>()
        val destinations = landings.map { it.target.destination }

        assertThat(landings).isNotEmpty
        assertThat(destinations).doesNotContain(Coordinate(11, 15), Coordinate(10, 14))
        assertThat(destinations).contains(Coordinate(1, 3), Coordinate(3, 1), Coordinate(1, 9)) // orthogonal tip
    }

    @Test
    fun `failed throw teammate lands prone without armour`() {
        lateinit var thrown: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(2)
        match.play(landing)
        val pieces = match.pieces()

        assertThat(player(pieces, thrown.player.id).at).isEqualTo(Coordinate(5, 3))
        assertThat(player(pieces, thrown.player.id).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `fumble injures thrown teammate`() {
        lateinit var thrown: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(1)
        val response = match.play(landing)
        val pieces = match.pieces()

        assertThat(player(pieces, thrown.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertEventsContain(response, DomainEvent.PlayerInjured(thrown.player.id, Coordinate(5, 5)))
    }

    @Test
    fun `ball carrier cannot throw teammate`() {
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
            holdingBall(thrower)
        }

        assertThat(match.legalActions<LegalAction.ThrowTeammateSelect>()).isEmpty()
    }

    @Test
    fun `occupied landing knocks Ball Guard carrier and lets owning coach place the ball`() {
        lateinit var thrown: PlacedPlayer
        lateinit var carrier: PlacedPlayer
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
            carrier = holdingBall(addPlayer(5, 3, team = TeamSide.AWAY, profile = ballGuardProfile))
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(6)
        val landingResponse = match.play(landing)

        assertEventsExactly(
            landingResponse,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 6,
                threshold = throwThreshold,
                modifier = 0,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.PlayerStatusChanged(
                carrier.player.id,
                Coordinate(5, 3),
                PlayerStatus.STANDING,
                PlayerStatus.PRONE,
            ),
            DomainEvent.BallDropped(carrier.player.id, Coordinate(5, 3)),
            DomainEvent.InputRequired.PlayerAction(
                AWAY,
                THROW_TEAMMATE_CONTINUE,
                ability = `Ball Guard`,
                playerId = carrier.player.id,
            ),
        )

        val place = match.legalActions<LegalAction.PlaceLooseBall>()
            .single { it.to == Coordinate(4, 3) }
        dice.queueD6(6)
        val placeResponse = match.play(place)

        assertEventsExactly(
            placeResponse,
            DomainEvent.BallBounced(Coordinate(5, 3), Coordinate(4, 3)),
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 6,
                threshold = ballGuardArmour,
                modifier = -1,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.PlayerInjured(thrown.player.id, Coordinate(5, 5)),
            DomainEvent.ActionComplete(thrower.player.id, Coordinate(5, 6), ActionType.THROW_TEAMMATE),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
            DomainEvent.InputRequired.PlayerAction(HOME, REGULAR_PLAYER_ACTION),
        )
        assertThat(match.pieces().looseBallLocations).contains(Coordinate(4, 3))
        assertThat(player(match.pieces(), thrown.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(player(match.pieces(), carrier.player.id).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `failed empty landing with Ball Guard lets owning coach place the ball`() {
        lateinit var thrown: PlacedPlayer
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = holdingBall(
                addPlayer(5, 5, team = thrower.team, profile = TeamCatalogTestHelper.throwableTeammateOf(throwerProfile, `Ball Guard`)),
            )
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(2)
        val landingResponse = match.play(landing)

        assertEventsExactly(
            landingResponse,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 2,
                threshold = throwThreshold,
                modifier = 0,
                checkResult = DiceCheckResult.FAIL,
            ),
            DomainEvent.PlayerMoved(thrown.player.id, Coordinate(5, 5), Coordinate(5, 3)),
            DomainEvent.PlayerStatusChanged(
                thrown.player.id,
                Coordinate(5, 3),
                PlayerStatus.STANDING,
                PlayerStatus.PRONE,
            ),
            DomainEvent.BallDropped(thrown.player.id, Coordinate(5, 3)),
            DomainEvent.InputRequired.PlayerAction(
                HOME,
                THROW_TEAMMATE_CONTINUE,
                ability = `Ball Guard`,
                playerId = thrown.player.id,
            ),
        )

        val place = match.legalActions<LegalAction.PlaceLooseBall>()
            .single { it.to == Coordinate(4, 3) }
        val placeResponse = match.play(place)

        assertEventsExactly(
            placeResponse,
            DomainEvent.BallBounced(Coordinate(5, 3), Coordinate(4, 3)),
            DomainEvent.ActionComplete(thrower.player.id, Coordinate(5, 6), ActionType.THROW_TEAMMATE),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
            DomainEvent.InputRequired.PlayerAction(HOME, REGULAR_PLAYER_ACTION),
        )
        assertThat(match.pieces().looseBallLocations).contains(Coordinate(4, 3))
    }

    @Test
    fun `occupied landing knocks standing occupant then armour and injures thrown`() {
        lateinit var thrown: PlacedPlayer
        lateinit var occupant: PlacedPlayer
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
            occupant = addPlayer(5, 3, team = TeamSide.AWAY, profile = occupantProfile)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(6) // throw success
        dice.queueD6(6) // armour holds (−1)
        val response = match.play(landing)

        assertEventsExactly(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 6,
                threshold = throwThreshold,
                modifier = 0,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.PlayerStatusChanged(
                occupant.player.id,
                Coordinate(5, 3),
                PlayerStatus.STANDING,
                PlayerStatus.PRONE,
            ),
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 6,
                threshold = occupantArmour,
                modifier = -1,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.PlayerInjured(thrown.player.id, Coordinate(5, 5)),
            DomainEvent.ActionComplete(thrower.player.id, Coordinate(5, 6), ActionType.THROW_TEAMMATE),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
            DomainEvent.InputRequired.PlayerAction(HOME, REGULAR_PLAYER_ACTION),
        )
        assertThat(player(match.pieces(), occupant.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(match.pieces(), thrown.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `failed occupied landing rolls armour without minus one`() {
        lateinit var thrown: PlacedPlayer
        lateinit var occupant: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
            occupant = addPlayer(5, 3, team = TeamSide.AWAY, profile = occupantProfile)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(2) // throw fail
        dice.queueD6(6) // armour holds (no −1)
        val response = match.play(landing)

        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.THROW,
                d6 = 2,
                threshold = throwThreshold,
                modifier = 0,
                checkResult = DiceCheckResult.FAIL,
            ),
            DomainEvent.PlayerStatusChanged(
                occupant.player.id,
                Coordinate(5, 3),
                PlayerStatus.STANDING,
                PlayerStatus.PRONE,
            ),
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 6,
                threshold = occupantArmour,
                modifier = 0,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.PlayerInjured(thrown.player.id, Coordinate(5, 5)),
        )
        assertThat(player(match.pieces(), occupant.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(match.pieces(), thrown.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `occupied landing on prone occupant skips knock down and still rolls armour`() {
        lateinit var thrown: PlacedPlayer
        lateinit var occupant: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
            occupant = addPlayer(5, 3, team = TeamSide.AWAY, status = PlayerStatus.PRONE, profile = occupantProfile)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(6)
        dice.queueD6(6)
        val response = match.play(landing)

        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.PlayerStatusChanged>())
            .noneMatch { it.playerId == occupant.player.id }
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.ARMOUR,
                d6 = 6,
                threshold = occupantArmour,
                modifier = -1,
                checkResult = DiceCheckResult.PASS,
            ),
            DomainEvent.PlayerInjured(thrown.player.id, Coordinate(5, 5)),
        )
        assertThat(player(match.pieces(), occupant.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(match.pieces(), thrown.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `occupied landing with ball on thrown bounces from target square`() {
        lateinit var thrown: PlacedPlayer
        lateinit var occupant: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = holdingBall(addPlayer(5, 5, team = thrower.team, profile = teammateProfile))
            occupant = addPlayer(5, 3, team = TeamSide.AWAY, profile = occupantProfile)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(6) // throw
        dice.queueD6(6) // armour
        dice.queueD8(1) // bounce N from (5, 3) → (5, 2)
        val response = match.play(landing)

        assertEventsContain(
            response,
            DomainEvent.BallThrowMissed(Coordinate(5, 5), Coordinate(5, 3)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 1),
            DomainEvent.BallBounced(Coordinate(5, 3), Coordinate(5, 2)),
            DomainEvent.PlayerInjured(thrown.player.id, Coordinate(5, 5)),
        )
        assertThat(match.pieces().looseBallLocations).containsExactly(Coordinate(5, 2))
        assertThat(player(match.pieces(), occupant.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(match.pieces(), thrown.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `successful empty landing on loose ball bounces that ball`() {
        lateinit var thrown: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = addPlayer(5, 5, team = thrower.team, profile = teammateProfile)
            addLooseBall(5, 3)
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(6)
        dice.queueD8(1) // bounce N from (5, 3) → (5, 2)
        val response = match.play(landing)

        assertEventsContain(
            response,
            DomainEvent.PlayerMoved(thrown.player.id, Coordinate(5, 5), Coordinate(5, 3)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 1),
            DomainEvent.BallBounced(Coordinate(5, 3), Coordinate(5, 2)),
        )
        assertThat(player(match.pieces(), thrown.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), thrown.player.id).at).isEqualTo(Coordinate(5, 3))
        assertThat(match.pieces().looseBallLocations).containsExactly(Coordinate(5, 2))
    }

    @Test
    fun `failed empty landing with ball bounces from landing square`() {
        lateinit var thrown: PlacedPlayer
        val match = match {
            val thrower = addPlayer(5, 6, profile = throwerProfile)
            thrown = holdingBall(addPlayer(5, 5, team = thrower.team, profile = teammateProfile))
        }

        match.play(match.legalActions<LegalAction.ThrowTeammateSelect>().single())
        val landing = match.legalActions<LegalAction.ThrowTeammate>()
            .single { it.target.destination == Coordinate(5, 3) }

        dice.queueD6(2) // fail
        dice.queueD8(1) // bounce N from (5, 3) → (5, 2)
        val response = match.play(landing)

        assertEventsContain(
            response,
            DomainEvent.PlayerMoved(thrown.player.id, Coordinate(5, 5), Coordinate(5, 3)),
            DomainEvent.PlayerStatusChanged(
                thrown.player.id,
                Coordinate(5, 3),
                PlayerStatus.STANDING,
                PlayerStatus.PRONE,
            ),
            DomainEvent.BallDropped(thrown.player.id, Coordinate(5, 3)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BOUNCE, d8 = 1),
            DomainEvent.BallBounced(Coordinate(5, 3), Coordinate(5, 2)),
        )
        assertThat(player(match.pieces(), thrown.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(match.pieces(), thrown.player.id).holdsBall).isFalse()
        assertThat(match.pieces().looseBallLocations).containsExactly(Coordinate(5, 2))
    }
}
