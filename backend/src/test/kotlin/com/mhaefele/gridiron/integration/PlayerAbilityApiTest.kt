package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.pending.PlayerDecisionType.BLOCK_CONTINUE
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.PlayerAbility.`Ball Guard`
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Direction
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TeamSide.HOME
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertAny
import com.mhaefele.gridiron.support.assertAvailable
import com.mhaefele.gridiron.support.assertAvailableVia
import com.mhaefele.gridiron.support.assertEventsContain
import com.mhaefele.gridiron.support.assertNone
import com.mhaefele.gridiron.support.assertUnavailable
import com.mhaefele.gridiron.support.bareEvents
import com.mhaefele.gridiron.support.onlyAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class PlayerAbilityApiTest : BaseApiTest() {

    @Test
    fun `relentless after mark offers free block without consuming action budget`() {
        // y=2: S . O     S=slayer  O=opp
        lateinit var slayer: PlacedPlayer
        val match = match {
            tinyBoard()
            slayer = addPlayer(1, 2, profile = TeamCatalog.dwarves().roster.first { it.name == "Doom Seeker" })
            addPlayer(5, 2) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(3, 2, team = TeamSide.AWAY)
        }

        val markResponse = match.play(slayer, ActionType.MARK, 2, 2)

        assertThat(markResponse.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                HOME,
                PlayerDecisionType.MARK_CONTINUE,
                ability = PlayerAbility.`Relentless After Mark`,
                playerId = slayer.player.id,
            ),
        )

        val legal = match.legalActions()
        legal.assertAny(ActionType.BLOCK, slayer)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(legal.onlyAction(ActionType.BLOCK))
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `second swing offers block dice reroll`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            tinyBoard()
            blitzer = addPlayer(2, 2, profile = TeamCatalog.orcs().roster.first { it.name == "Blitzer" })
            addPlayer(3, 2, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        val response = match.play(ActionType.BLOCK)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                HOME,
                BLOCK_CONTINUE,
                ability = PlayerAbility.`Second Swing`,
                playerId = blitzer.player.id,
            ),
        )

        val rerolls = match.legalActions<LegalAction.ChooseBlockReroll>()
        assertThat(rerolls.map { it.reroll }).containsExactlyInAnyOrder(true, false)
    }

    @Test
    fun `heavy hitter smash becomes flatten and still rolls armour`() {
        lateinit var target: PlacedPlayer
        val match = match {
            tinyBoard()
            addPlayer(2, 2, profile = TeamCatalog.orcs().roster.first { it.name == "Bruiser" })
            target = addPlayer(3, 2, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.MISS)
        dice.queueD6(6)
        val response = match.play(ActionType.BLOCK)

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.ARMOUR }
        assertThat(player(match.pieces(), target.player.id).status).isNotEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `sure scoop picks up ball on run onto ball square`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            tinyBoard()
            thrower = addPlayer(1, 2, profile = TeamCatalog.orcs().roster.first { it.name == "Thrower" })
            addLooseBall(3, 2)
        }

        match.play(thrower, ActionType.RUN, 3, 2)

        assertThat(player(match.pieces(), thrower.player.id).holdsBall).isTrue()
    }

    @Test
    fun `ball guard lets owning coach choose bounce square`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            tinyBoard()
            activeCoach(TeamSide.AWAY)
            runner = holdingBall(
                addPlayer(2, 2, profile = TeamCatalog.dwarves().roster.first { it.name == "Runner" }),
            )
            addPlayer(3, 2, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(ActionType.BLOCK)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                HOME,
                BLOCK_CONTINUE,
                ability = `Ball Guard`,
                playerId = runner.player.id,
            ),
        )

        val places = match.legalActions<LegalAction.PlaceLooseBall>()
        assertThat(places).isNotEmpty()
        val pick = places.first()
        match.play(pick)

        assertThat(match.pieces().looseBallLocations).contains(pick.to)
        assertThat(player(match.pieces(), runner.player.id).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `ball guard can bounce to an open teammates square`() {
        lateinit var runner: PlacedPlayer
        lateinit var teammmate: PlacedPlayer
        val match = match {
            tinyBoard()
            activeCoach(TeamSide.AWAY)
            runner = holdingBall(
                addPlayer(2, 2, profile = TeamCatalog.dwarves().roster.first { it.name == "Runner" }),
            )
            teammmate = addPlayer(1,1)
            addPlayer(3, 2, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH).queueD6(6)

        val response = match.play(ActionType.BLOCK)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(HOME, BLOCK_CONTINUE, `Ball Guard`, playerId = runner.player.id, ),
        )

        val teammmateBounce = match.legalActions<LegalAction.PlaceLooseBall>().first { it.targetCoordinate == Coordinate(1,1) }

        match.play(teammmateBounce)

        assertThat(match.pieces().looseBallLocations).isEmpty()
        assertThat(player(match.pieces(), teammmate).holdsBall).isTrue
        assertThat(player(match.pieces(), runner).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `ball guard can bounce to an marked teammates square`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            tinyBoard()
            activeCoach(TeamSide.AWAY)
            runner = holdingBall(
                addPlayer(2, 2, profile = TeamCatalog.dwarves().roster.first { it.name == "Runner" }),
            )
            addPlayer(3,3)
            addPlayer(3, 2, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH).queueD6(6)

        val response = match.play(match.legalActions<LegalAction.Block>().first{it.targetCoordinate == Coordinate(2,2)})

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(HOME, BLOCK_CONTINUE, `Ball Guard`, playerId = runner.player.id, ),
        )

        val teammmateBounce = match.legalActions<LegalAction.PlaceLooseBall>().filter { it.targetCoordinate == Coordinate(3,3) }.first()

        dice.queueBounce(Direction.W)
        match.play(teammmateBounce)

        assertThat(match.pieces().looseBallLocations).contains(Coordinate(2,3))
        assertThat(player(match.pieces(), runner).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `wild ward blocks assists not the block itself`() {
        // y=2: . B W     B=blocker  W=beastmaster; assister at (2,3)
        lateinit var blocker: PlacedPlayer
        lateinit var beastmaster: PlacedPlayer
        val match = match {
            tinyBoard(height = 4)
            blocker = addPlayer(2, 2)
            addPlayer(2, 3)
            beastmaster = addPlayer(
                3,
                2,
                team = TeamSide.AWAY,
                profile = TeamCatalog.team("gnome").roster.first { it.name == "Beastmaster" },
            )
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(blocker, ActionType.BLOCK, 3, 2)

        assertThat(player(match.pieces(), beastmaster.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.BLOCK && it.blockFaces != null && it.blockFaces.size == 1 }
    }

    @Test
    fun `juggernaut marked block is not assisted`() {
        val pump = TeamCatalog.team("gremlin").roster.first { it.name == "Rattle Cart" }
            .let { it.copy(stats = it.stats.copy(blockDiceCount = 1)) }
        lateinit var wagon: PlacedPlayer
        lateinit var target: PlacedPlayer
        val match = match {
            tinyBoard(height = 4)
            wagon = addPlayer(2, 2, profile = pump)
            addPlayer(2, 3)
            target = addPlayer(3, 2, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(wagon, ActionType.BLOCK, 3, 2)

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertEventsContain(
            response,
            DomainEvent.DiceRolled(
                purpose = DiceRollPurpose.BLOCK,
                blockFaces = listOf(BlockDieFace.SMASH),
                blockResult = BlockDieFace.SMASH,
            ),
        )
    }

    @Test
    fun `juggernaut may declare run whilst marked and finish marked`() {
        // y=2: . W O .     W=wagon  O=opp
        val pump = TeamCatalog.team("gremlin").roster.first { it.name == "Rattle Cart" }
        lateinit var wagon: PlacedPlayer
        val match = match {
            tinyBoard()
            wagon = addPlayer(2, 2, profile = pump)
            addPlayer(3, 2, team = TeamSide.AWAY)
        }

        val declare = match.legalActions<LegalAction.DeclareRun>()
            .single { it.playerId == wagon.player.id }
        dice.queueD6(2)
        match.play(declare)

        val legal = match.legalActions()
        // Stay diagonally adjacent (Marked) or step west (Open)
        legal.assertAvailable(wagon, ActionType.RUN, 3, 1) // may need height=4 for (3,1)? height=3: y=1 is home EZ
        // On height=3, y=1 is HOME end zone — still playable. (3,1) adjacent to opp at (3,2).
        // Open finish: (1,2) west of wagon.
        legal.assertAvailable(wagon, ActionType.RUN, 1, 2)

        match.play(wagon, ActionType.RUN, 3, 1)
        assertThat(player(match.pieces(), wagon.player.id).at).isEqualTo(Coordinate(3, 1))
    }

    companion object {
        @JvmStatic
        fun throughOccupantProfiles(): Stream<Arguments> = Stream.of(
            Arguments.of("plain lineman", TestProfiles.withMove(4), false),
            Arguments.of("ethereal Wraith", TestProfiles.wraith(), true),
            Arguments.of(
                "vault Bounder",
                TeamCatalog.team("gremlin").roster.first { it.name == "Bounder" },
                true,
            ),
        )

        fun sealedCorridor(width: Int = 5): Set<Coordinate> = GameScenario.sealedEndZones(width)
    }

    @ParameterizedTest(name = "{0}: through-occupant run expected={2}")
    @MethodSource("throughOccupantProfiles")
    fun `run through occupied square`(
        @Suppress("UNUSED_PARAMETER") label: String,
        profile: PositionProfile,
        canPassThrough: Boolean,
    ) {
        lateinit var runner: PlacedPlayer
        val match = match {
            tinyBoard(width = 5, blocked = sealedCorridor())
            runner = addPlayer(1, 2, profile = profile)
            addPlayer(3, 2, team = TeamSide.AWAY)
        }
        val legal = match.legalActions()

        legal.assertUnavailable(runner, ActionType.RUN, 3, 2)
        if (canPassThrough) {
            legal.assertAvailable(runner, ActionType.RUN, 5, 2)
            legal.assertUnavailable(runner, ActionType.RUN, 4, 2)
            match.play(runner, ActionType.RUN, 5, 2)
            assertThat(player(match.pieces(), runner.player.id).at).isEqualTo(Coordinate(5, 2))
        } else {
            legal.assertUnavailable(runner, ActionType.RUN, 5, 2)
        }
    }

    @ParameterizedTest(name = "{0}: through-teammate run expected={2}")
    @MethodSource("throughOccupantProfiles")
    fun `run through teammate square`(
        @Suppress("UNUSED_PARAMETER") label: String,
        profile: PositionProfile,
        canPassThrough: Boolean,
    ) {
        lateinit var runner: PlacedPlayer
        val match = match {
            tinyBoard(width = 5, blocked = sealedCorridor())
            runner = addPlayer(1, 2, profile = profile)
            addPlayer(3, 2)
        }
        val legal = match.legalActions()

        legal.assertUnavailable(runner, ActionType.RUN, 3, 2)
        if (canPassThrough) {
            legal.assertAvailable(runner, ActionType.RUN, 5, 2)
            match.play(runner, ActionType.RUN, 5, 2)
            assertThat(player(match.pieces(), runner.player.id).at).isEqualTo(Coordinate(5, 2))
        } else {
            legal.assertUnavailable(runner, ActionType.RUN, 5, 2)
        }
    }

    @Test
    fun `ethereal run through obstruction — plain cannot`() {
        // Column of blocks — no end-zone detour:
        // y=1..3 x=2 blocked; H at (1,2), D at (3,2)
        val wall = setOf(Coordinate(2, 1), Coordinate(2, 2), Coordinate(2, 3))

        lateinit var plain: PlacedPlayer
        val plainMatch = match {
            tinyBoard(width = 5, blocked = wall)
            plain = addPlayer(1, 2, profile = TestProfiles.withMove(4))
        }
        plainMatch.legalActions().assertUnavailable(plain, ActionType.RUN, 3, 2)

        lateinit var wraith: PlacedPlayer
        val ethMatch = match {
            tinyBoard(width = 5, blocked = wall)
            wraith = addPlayer(1, 2, profile = TestProfiles.wraith())
        }
        val legal = ethMatch.legalActions()
        legal.assertUnavailable(wraith, ActionType.RUN, 2, 2)
        legal.assertAvailableVia(wraith, ActionType.RUN, 3, 2, viaX = 2, viaY = 2)

        ethMatch.play(wraith, ActionType.RUN, 3, 2)
        assertThat(player(ethMatch.pieces(), wraith.player.id).at).isEqualTo(Coordinate(3, 2))
    }
}
