package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DiceCheckResult
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Direction
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertAny
import com.mhaefele.gridiron.support.assertAvailable
import com.mhaefele.gridiron.support.assertAvailableVia
import com.mhaefele.gridiron.support.assertNone
import com.mhaefele.gridiron.support.assertUnavailable
import com.mhaefele.gridiron.support.bareEvents
import com.mhaefele.gridiron.support.onlyAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * PA.F.2 — focused acceptance coverage for ability families (hooks already wired).
 */
class PlayerAbilityAcceptanceApiTest : BaseApiTest() {

    private fun profile(teamId: String, name: String): PositionProfile =
        TeamCatalog.team(teamId).roster.first { it.name == name }

    private fun chebyshev(a: Coordinate, b: Coordinate): Int =
        max(abs(a.x - b.x), abs(a.y - b.y))

    // --- PA.1 free follow-ups ---

    @Test
    fun `relentless free block cannot target non-marked opponent`() {
        val match = match {
            addPlayer(3, 6, profile = TeamCatalog.dwarves().roster.first { it.name == "Doom Seeker" })
            addPlayer(5, 6, team = TeamSide.AWAY)
            addPlayer(8, 6, team = TeamSide.AWAY)
        }

        match.play<LegalAction.Mark> { it.targetCoordinate == Coordinate(4, 6) }

        val blocks = match.legalActions<LegalAction.Block>()
        assertThat(blocks.map { it.destination }).containsExactly(Coordinate(5, 6))
    }

    @Test
    fun `relentless after block offers free block without consuming budget`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            // No catalog position uses after-Block Relentless; keep the hook covered with an explicit profile.
            blitzer = addPlayer( 5, 6, profile = profile("dark-elf", "Blitzer").copy(ability = PlayerAbility.`Relentless After Block`))
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        val response = match.play(ActionType.BLOCK)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.BLOCK_CONTINUE,
                ability = PlayerAbility.`Relentless After Block`,
                playerId = blitzer.player.id,
            ),
        )
        match.legalActions().assertAny(ActionType.BLOCK)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.Block>().first { it.playerId == blitzer.player.id })
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        // Relentless After Block chains until Skip (or no markable target).
        match.legalActions().assertAny(ActionType.SKIP)
        match.play(match.legalActions<LegalAction.Skip>().single())
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `strike and fade free run is at most 2 squares and may end adjacent`() {
        val match = match {
            addPlayer(5, 6, profile = profile("amazon", "Blitzer"))
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)

        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty()
        assertThat(runs).allMatch { it.path.steps <= 2 }
        // Old wrong: ≤3 and forbid adjacent ends — both gone.
        assertThat(runs).noneMatch { it.path.steps == 3 }
        assertThat(runs.map { it.targetCoordinate })
            .containsAnyOf(Coordinate(5, 5), Coordinate(5, 7), Coordinate(6, 5), Coordinate(6, 7), Coordinate(7, 6))
        match.play(runs.first { it.path.steps <= 2 })
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `charge free block after run then mark`() {
        lateinit var beast: PlacedPlayer
        val match = match {
            beast = addPlayer(3, 6, profile = profile("doomsworn", "Beastkin"))
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == beast.player.id && it.targetCoordinate == Coordinate(4, 6) }
        match.play(run)

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.playerId == beast.player.id && it.targetCoordinate == Coordinate(5, 6) }
        val response = match.play(mark)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.MARK_CONTINUE,
                ability = PlayerAbility.Charge,
                playerId = beast.player.id,
            ),
        )
        match.legalActions().assertAny(ActionType.BLOCK)
        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.Block>().single { it.playerId == beast.player.id })
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `charge mark only does not grant free block`() {
        val match = match {
            addPlayer(4, 6, profile = profile("doomsworn", "Beastkin"))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.targetCoordinate == Coordinate(5, 6) }
        match.play(mark)

        match.legalActions().assertNone(ActionType.SKIP)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
    }

    @Test
    fun `pass on the run offers free short throw after run`() {
        lateinit var thrower: PlacedPlayer
        lateinit var receiver: PlacedPlayer
        val match = match {
            thrower = addPlayer(3, 6, profile = profile("gilded-crown", "Thrower"))
            holdingBall(thrower)
            receiver = addPlayer(3, 4, team = thrower.team)
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == thrower.player.id && it.targetCoordinate == Coordinate(4, 6) }
        val response = match.play(run)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.RUN_CONTINUE,
                ability = PlayerAbility.`Pass on the Run`,
                playerId = thrower.player.id,
            ),
        )
        val throws = match.legalActions<LegalAction.Throw>()
        assertThat(throws).isNotEmpty()
        assertThat(throws).allMatch { it.target.range == ThrowRange.SHORT }
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        dice.queueD6(6)
        match.play(throws.first { it.target.destination == Coordinate(3, 4) })
        assertThat(player(match.pieces(), receiver.player.id).holdsBall).isTrue()
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `catch and go free run after open catch`() {
        lateinit var thrower: PlacedPlayer
        lateinit var catcher: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("human", "Thrower"))
            holdingBall(thrower)
            catcher = addPlayer(5, 4, team = thrower.team, profile = profile("human", "Catcher"))
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(5, 4) }
        dice.queueD6(6)
        val response = match.play(throwAction)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.THROW_CONTINUE,
                ability = PlayerAbility.`Catch and Go`,
                playerId = catcher.player.id,
            ),
        )
        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty()
        assertThat(runs).allMatch { it.playerId == catcher.player.id }

        val run = runs.first()
        match.play(run)
        assertPiece(match.pieces(), catcher.player.id, x = run.targetCoordinate.x, y = run.targetCoordinate.y)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `catch and go does not grant when catcher is marked`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("human", "Thrower"))
            holdingBall(thrower)
            addPlayer(5, 4, team = thrower.team, profile = profile("human", "Catcher"))
            addPlayer(5, 3, team = TeamSide.AWAY)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(5, 4) }
        dice.queueD6(6)
        match.play(throwAction)

        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `rally offers free stand up only to adjacent prone ally`() {
        lateinit var boar: PlacedPlayer
        lateinit var adjacent: PlacedPlayer
        lateinit var far: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            adjacent = addPlayer(6, 6, status = PlayerStatus.PRONE)
            far = addPlayer(8, 6, status = PlayerStatus.PRONE)
        }

        // Stay adjacent to the prone ally after the action so Rally still applies.
        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(5, 5) }
        val response = match.play(run)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.RUN_CONTINUE,
                ability = PlayerAbility.Rally,
                playerId = adjacent.player.id,
            ),
        )
        val standUps = match.legalActions<LegalAction.StandUp>()
        assertThat(standUps.map { it.playerId }).containsExactly(adjacent.player.id)
        match.play(standUps.single())
        assertThat(player(match.pieces(), adjacent.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), far.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `rally offers free stand up after a mark`() {
        lateinit var boar: PlacedPlayer
        lateinit var adjacent: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            adjacent = addPlayer(6, 6, status = PlayerStatus.PRONE)
            addPlayer(7, 6, team = TeamSide.AWAY)
        }

        // Mark to (6,5): still adjacent to prone (6,6), now Marked by (7,6).
        val response = match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(6, 5) },
        )

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.MARK_CONTINUE,
                ability = PlayerAbility.Rally,
                playerId = adjacent.player.id,
            ),
        )
        match.play(match.legalActions<LegalAction.StandUp>().single { it.playerId == adjacent.player.id })
        assertThat(player(match.pieces(), adjacent.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `rally offers free stand up after a block`() {
        lateinit var boar: PlacedPlayer
        lateinit var adjacent: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            adjacent = addPlayer(4, 6, status = PlayerStatus.PRONE)
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        // Miss ends the blocker's turn-slot but still reaches ability follow-ups (same as Strike and Fade).
        dice.queueBlockDice(BlockDieFace.MISS)
        val response = match.play(ActionType.BLOCK)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.BLOCK_CONTINUE,
                ability = PlayerAbility.Rally,
                playerId = adjacent.player.id,
            ),
        )
        match.play(match.legalActions<LegalAction.StandUp>().single { it.playerId == adjacent.player.id })
        assertThat(player(match.pieces(), adjacent.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `undying offers free stand up for prone player after pre-turn`() {
        lateinit var skeleton: PlacedPlayer
        val match = match {
            activeCoach(TeamSide.AWAY)
            actionsRemaining(1)
            addPlayer(5, 6, team = TeamSide.AWAY)
            addPlayer(6, 6)
            addLooseBall(7, 8)
            skeleton = addPlayer(8, 6, status = PlayerStatus.PRONE, profile = profile("restless-dead", "Skeleton"))
            // Old wrong: dugout Reserves grant — must not reappear.
            addReservesPlayer(team = TeamSide.HOME, profile = profile("restless-dead", "Skeleton"))
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)
        resolveForcedChallengeReplaceIfPresent(match.matchId)

        match.legalActions().assertNone(ActionType.RESERVES)
        val standUps = match.legalActions<LegalAction.StandUp>()
        assertThat(standUps).isNotEmpty()
        assertThat(standUps).allMatch { it.playerId == skeleton.player.id }

        match.play(standUps.first())
        assertThat(player(match.pieces(), skeleton.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
    }

    // --- PA.2 block dice ---

    @Test
    fun `second swing second reroll is denied`() {
        val match = match {
            addPlayer(5, 6, profile = TeamCatalog.orcs().roster.first { it.name == "Blitzer" })
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)

        val reroll = match.legalActions<LegalAction.ChooseBlockReroll>()
            .single { it.reroll }
        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        match.play(reroll)

        match.legalActions().assertNone(ActionType.CHOOSE_BLOCK_REROLL)
    }

    @Test
    fun `non specialist does not offer block dice reroll`() {
        val match = match {
            addPlayer(5, 6, profile = TeamCatalog.orcs().roster.first { it.name == "Lineman" })
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)

        match.legalActions().assertNone(ActionType.CHOOSE_BLOCK_REROLL)
    }

    @Test
    fun `non heavy hitter smash still rolls armour`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = TeamCatalog.orcs().roster.first { it.name == "Lineman" })
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.ARMOUR }
    }

    @Test
    fun `anchored turns shove into miss`() {
        lateinit var bodyguard: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            bodyguard = addPlayer(6, 6, team = TeamSide.AWAY, profile = profile("gilded-crown", "Bodyguard"))
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        match.play(ActionType.BLOCK)

        assertThat(player(match.pieces(), bodyguard.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), bodyguard.player.id).at).isEqualTo(Coordinate(6, 6))
    }

    @Test
    fun `colossus blocker turns smash into flatten`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = profile("gnome", "Oakwalker"))
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        // Oakwalker rolls 2 block dice — SMASH→FLATTEN (not the old Backpedal/Shove sub).
        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.MISS)
        dice.queueD6(6) // armour holds after Flatten KD
        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.ARMOUR }
        // Old wrong: Smash→Shove leaving target Standing — gone.
        assertThat(player(match.pieces(), target.player.id).status).isNotEqualTo(PlayerStatus.STANDING)
    }

    @Test
    fun `colossus blocker turns tackle into flatten`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = profile("goblin", "Troll"))
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.TACKLE, BlockDieFace.MISS)
        dice.queueD6(6)
        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.ARMOUR }
        // Old wrong: Tackle→Shove / turn-ending Tackle — gone.
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
    }

    @Test
    fun `underfoot turns tackle into miss`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = profile("ogre", "Grubling"))
            target = addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.TACKLE)
        match.play(ActionType.BLOCK)

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.STANDING)
    }

    @Test
    fun `underfoot adjacency ends opposition run`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            // Grubling (Underfoot) at (5,6); opposition runner starts Open at (3,6).
            addPlayer(5, 6, team = TeamSide.AWAY, profile = profile("ogre", "Grubling"))
            runner = addPlayer(3, 6, profile = TestProfiles.withMove(4))
        }

        val runs = match.legalActions<LegalAction.Run>()
            .filter { it.playerId == runner.player.id }
        // May enter / end on the first adjacent square, but cannot continue past it.
        assertThat(runs.map { it.targetCoordinate }).contains(Coordinate(4, 6))
        assertThat(runs.filter { Coordinate(4, 6) in it.path.squares.dropLast(1) }).isEmpty()
        assertThat(runs.filter { Coordinate(5, 7) in it.path.squares.dropLast(1) }).isEmpty()
        assertThat(runs.filter { Coordinate(5, 5) in it.path.squares.dropLast(1) }).isEmpty()
    }

    @Test
    fun `beneath notice turns tackle into miss`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = profile("gremlin", "Gremlin"))
            target = addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.TACKLE)
        match.play(ActionType.BLOCK)

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.STANDING)
    }

    @Test
    fun `violent d6 of 6 injures without resolving block dice`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = profile("graveborn", "Werewolf"))
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueD6(6)
        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.VIOLENT && it.d6 == 6 }
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .noneMatch { it.purpose == DiceRollPurpose.BLOCK }
        // Old wrong: D8 nat-8 gate — gone.
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .noneMatch { it.purpose == DiceRollPurpose.VIOLENT && it.d8 != null }
    }

    @Test
    fun `violent d6 of 6 on foul injures without resolving foul die`() {
        lateinit var prone: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = profile("graveborn", "Werewolf"))
            prone = addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Foul>().single())

        assertThat(player(match.pieces(), prone.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.VIOLENT && it.d6 == 6 }
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .noneMatch { it.purpose == DiceRollPurpose.FOUL }
    }

    @Test
    fun `violent d6 of 6 on a ball carrier bounces the ball`() {
        lateinit var target: PlacedPlayer
        val match = match {
            addPlayer(5, 6, profile = profile("graveborn", "Werewolf"))
            target = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman()))
        }

        dice.queueD6(6)
        dice.queueBounce(Direction.N)
        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(player(match.pieces(), target.player.id).holdsBall).isFalse()
        assertThat(match.pieces().looseBallLocations).containsExactly(Coordinate(6, 5))
    }

    @Test
    fun `lobber shove is treated as miss but smash still knocks down`() {
        lateinit var slinger: PlacedPlayer
        lateinit var target: PlacedPlayer
        val match = match {
            // Open Lobber (not Marked) — card requires Open for the Block Action.
            slinger = addPlayer(3, 6, profile = profile("gremlin", "Spore Slinger"))
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        val block = match.legalActions<LegalAction.Block>()
            .single { it.playerId == slinger.player.id && it.destination == Coordinate(6, 6) }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        match.play(block)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), target.player.id).at).isEqualTo(Coordinate(6, 6))

        // Fresh match for Smash — old wrong Smash→Miss must not recur.
        lateinit var slinger2: PlacedPlayer
        lateinit var target2: PlacedPlayer
        val match2 = match {
            slinger2 = addPlayer(3, 6, profile = profile("gremlin", "Spore Slinger"))
            target2 = addPlayer(6, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }
        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        match2.play(
            match2.legalActions<LegalAction.Block>()
                .single { it.playerId == slinger2.player.id },
        )
        assertThat(player(match2.pieces(), target2.player.id).status).isEqualTo(PlayerStatus.PRONE)
}

    @Test
    fun `lobber can block open opponent within three squares whilst open`() {
        lateinit var slinger: PlacedPlayer
        val match = match {
            slinger = addPlayer(3, 6, profile = profile("gremlin", "Spore Slinger"))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        val blocks = match.legalActions<LegalAction.Block>()
            .filter { it.playerId == slinger.player.id }
        assertThat(blocks.map { it.destination }).contains(Coordinate(6, 6))
        // Old wrong: Marked Lobber required for BLOCK in the action set — Open is enough.
        assertThat(blocks).isNotEmpty()
    }

    // --- PA.3 ball ---

    @Test
    fun `non handling mark onto ball square bounces instead of pickup`() {
        val match = match {
            addPlayer(4, 10)
            addPlayer(6, 10, team = TeamSide.AWAY)
            addLooseBall(5, 10)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.targetCoordinate == Coordinate(5, 10) }
        dice.queueD8(1)
        val response = match.play(mark)

        assertThat(player(match.pieces(), mark.playerId).holdsBall).isFalse()
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.BOUNCE }
    }

    @Test
    fun `sure scoop picks up on mark onto ball square`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(4, 10, profile = TeamCatalog.orcs().roster.first { it.name == "Thrower" })
            addPlayer(6, 10, team = TeamSide.AWAY)
            addLooseBall(5, 10)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.playerId == thrower.player.id && it.targetCoordinate == Coordinate(5, 10) }
        match.play(mark)

        assertThat(player(match.pieces(), thrower.player.id).holdsBall).isTrue()
        assertThat(match.pieces().looseBallLocations).isEmpty()
    }

    @Test
    fun `ball guard bounce squares are adjacent only and prompts owning coach not blocker`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            activeCoach(TeamSide.AWAY)
            runner = addPlayer(5, 6, profile = TeamCatalog.dwarves().roster.first { it.name == "Runner" })
            holdingBall(runner)
            addPlayer(6, 6, team = TeamSide.AWAY)
        }
        val knockedAt = Coordinate(5, 6)

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.BLOCK_CONTINUE,
                ability = PlayerAbility.`Ball Guard`,
                playerId = runner.player.id,
            ),
        )
        val places = match.legalActions<LegalAction.PlaceLooseBall>()
        assertThat(places).isNotEmpty()
        assertThat(places).allMatch { chebyshev(knockedAt, it.to) == 1 }
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .noneMatch { it.purpose == DiceRollPurpose.BOUNCE }
        assertThat(player(match.pieces(), runner.player.id).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `pinpoint passer offers throw dice reroll on fail`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("silverleaf", "Thrower"))
            holdingBall(thrower)
            addPlayer(5, 4, team = thrower.team)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(5, 4) }
        dice.queueD6(1) // natural 1 → fumble; re-roll can still save it
        match.play(throwAction)

        val choices = match.legalActions<LegalAction.ChooseThrowReroll>()
        assertThat(choices.map { it.reroll }).containsExactlyInAnyOrder(true, false)
    }

    @Test
    fun `pinpoint passer skips throw dice reroll on catch`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("silverleaf", "Thrower"))
            holdingBall(thrower)
            addPlayer(5, 4, team = thrower.team)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(5, 4) }
        dice.queueD6(5)
        match.play(throwAction)

        match.legalActions().assertNone(ActionType.CHOOSE_THROW_REROLL)
        assertThat(player(match.pieces(), thrower.player.id).holdsBall).isFalse()
    }

    @Test
    fun `quick release interrupt offers throw or continue block to the carrier coach`() {
        lateinit var carrier: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY, profile = profile("dark-elf", "Runner")))
            addPlayer(6, 4, team = TeamSide.AWAY)
        }

        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.AWAY,
                PlayerDecisionType.BLOCK_CONTINUE,
                ability = PlayerAbility.`Quick Release`,
                playerId = carrier.player.id,
            ),
        )
        assertThat(match.legalActions<LegalAction.ContinueBlock>()).isNotEmpty()
        assertThat(
            match.legalActions<LegalAction.QuickReleaseThrow>().isNotEmpty() || match.legalActions<LegalAction.Throw>().isNotEmpty(),
        ).isTrue()
    }

    @Test
    fun `quick release with no valid targets is skipped`() {
        lateinit var carrier: PlacedPlayer
        lateinit var blocker: PlacedPlayer
        val match = match {
            blocker = addPlayer(5, 6)
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY, profile = profile("dark-elf", "Runner")))
            addPlayer(1, 15, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        dice.queueBounce(Direction.SE)
        val response = match.play(match.legalActions().onlyAction(ActionType.BLOCK))

        assertThat(player(match.pieces(), carrier.player.id).holdsBall).isFalse()
        assertThat(player(match.pieces(), carrier).status).isEqualTo(PlayerStatus.PRONE)

        assertThat (response.events.bareEvents()).isEqualTo(listOf(
            DomainEvent.ActionStarted(playerId=blocker.player.id, playerAt=Coordinate(x=5, y=6), actionType= ActionType.BLOCK),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BLOCK, blockFaces = listOf(BlockDieFace.SMASH), blockResult = BlockDieFace.SMASH),
            DomainEvent.PlayerStatusChanged(playerId = carrier.player.id, playerAt = Coordinate(6, 6), from = PlayerStatus.STANDING, to = PlayerStatus.PRONE),
            DomainEvent.BallDropped(playerId=carrier.player.id, playerAt=Coordinate(x=6, y=6)),
            DomainEvent.DiceRolled(purpose= DiceRollPurpose.BOUNCE, d8=4),
            DomainEvent.BallBounced(from=Coordinate(x=6, y=6), to=Coordinate(x=7, y=7)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.ARMOUR, d6 = 6, threshold = 4, modifier = 0, checkResult = DiceCheckResult.PASS),
            DomainEvent.ActionComplete(playerId = blocker.player.id, playerAt = Coordinate(5, 6), actionType = ActionType.BLOCK),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
            DomainEvent.InputRequired.PlayerAction(team = TeamSide.HOME, decisionType = PlayerDecisionType.REGULAR_PLAYER_ACTION, )
        ))
    }

    // --- PA.4 / PA.5 movement & defensive ---

    @Test
    fun `elusive must end run open and may continue past adjacent squares`() {
        // height=5; wall at x=3 on y=2 and y=4 leaves choke at (3,3):
        //   y=4: . . O . .
        //   y=3: H . . . D
        //   y=2: . . O . .
        fun corridor(runnerProfile: PositionProfile): Pair<MatchApi, PlacedPlayer> {
            lateinit var runner: PlacedPlayer
            val match = match {
                tinyBoard(width = 5, height = 5)
                runner = addPlayer(1, 3, profile = runnerProfile)
                addPlayer(3, 2, team = TeamSide.AWAY)
                addPlayer(3, 4, team = TeamSide.AWAY)
            }
            return match to runner
        }

        val (plainMatch, plainRunner) = corridor(TestProfiles.withMove(6))
        plainMatch.legalActions().assertUnavailable(plainRunner, ActionType.RUN, 5, 3)

        val (match, goblin) = corridor(profile("goblin", "Goblin"))
        val legal = match.legalActions()
        legal.assertUnavailable(goblin, ActionType.RUN, 3, 3)
        legal.assertAvailable(goblin, ActionType.RUN, 5, 3)
        legal.assertAvailableVia(goblin, ActionType.RUN, 5, 3, viaX = 3, viaY = 3)

        match.play(goblin, ActionType.RUN, 5, 3)
        assertPiece(match.pieces(), goblin.player.id, x = 5, y = 3)
}

    @Test
    fun `nimble beast can be targeted by block and cannot throw`() {
        val match = match {
            addPlayer(5, 6)
            addPlayer(6, 6, team = TeamSide.AWAY, profile = profile("gnome", "Woodland Fox"))
        }

        // Old wrong: Nimble untargetable — gone.
        match.legalActions().assertAny(ActionType.BLOCK)

        lateinit var litheCarrier: PlacedPlayer
        val match2 = match {
            litheCarrier = addPlayer(5, 6, profile = profile("gnome", "Woodland Fox"))
            holdingBall(litheCarrier)
            addPlayer(5, 4, team = litheCarrier.team)
        }
        match2.legalActions().assertNone(ActionType.THROW)
        assertThat(player(match2.pieces(), litheCarrier.player.id).holdsBall).isTrue()
}

    @Test
    fun `defensive blocks assists not blocks`() {
        lateinit var jaguar: PlacedPlayer
        lateinit var awayBlocker: PlacedPlayer
        val match = match {
            activeCoach(TeamSide.AWAY)
            jaguar = addPlayer(6, 6, profile = profile("amazon", "Blocker"))
            awayBlocker = addPlayer(5, 6, team = TeamSide.AWAY)
            // Would-be assister adjacent to Disruptor target.
            addPlayer(6, 5, team = TeamSide.AWAY)
        }

        // Old wrong: Disruptor cannot be Blocked — gone.
        val block = match.legalActions<LegalAction.Block>()
            .first { it.playerId == awayBlocker.player.id }
        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(block)

        assertThat(player(match.pieces(), jaguar.player.id).status).isEqualTo(PlayerStatus.PRONE)
        // Unassisted: single die despite adjacent teammate.
        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.purpose == DiceRollPurpose.BLOCK && it.blockFaces != null && it.blockFaces.size == 1 }
    }

    @Test
    fun `dance step converts a budgeted Sidestep to free`() {
        lateinit var dancer: PlacedPlayer
        val match = match {
            dancer = addPlayer(3, 6, profile = profile("wood-elf", "Glade Dancer"))
            addPlayer(5, 6, team = TeamSide.AWAY)
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.playerId == dancer.player.id && it.targetCoordinate == Coordinate(4, 6) }
        match.play(mark)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        val sidesteps = match.legalActions<LegalAction.Sidestep>()
        assertThat(sidesteps).isNotEmpty()
        assertThat(sidesteps).allMatch { it.playerId == dancer.player.id }

        val step = sidesteps.first { it.targetCoordinate == Coordinate(3, 6) }
        match.play(step)

        assertPiece(match.pieces(), dancer.player.id, x = 3, y = 6)
        // Dance Step converted the Sidestep — budget still has both remaining slots.
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SIDESTEP)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `dance step offers free sidestep after the third action when still marked`() {
        lateinit var dancer: PlacedPlayer
        val match = match {
            dancer = addPlayer(3, 6, profile = profile("wood-elf", "Glade Dancer"))
            addPlayer(2, 3)
            addPlayer(2, 2)
            addPlayer(5, 6, team = TeamSide.AWAY)
            addLooseBall(8, 8)
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == dancer.player.id && it.targetCoordinate == Coordinate(4, 6) },
        )
        match.play(match.legalActions<LegalAction.Run>().first { it.path.steps == 1 })
        val response = match.play(match.legalActions<LegalAction.Run>().first { it.path.steps == 1 })

        assertThat(match.gameState().actionsRemaining).isEqualTo(0)
        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.DANCE_STEP_OFFER,
                ability = PlayerAbility.`Dance Step`,
                playerId = dancer.player.id,
            ),
        )
        val sidesteps = match.legalActions<LegalAction.Sidestep>()
        assertThat(sidesteps).isNotEmpty()
        assertThat(sidesteps).allMatch { it.playerId == dancer.player.id }

        match.play(sidesteps.first { it.targetCoordinate == Coordinate(3, 6) })

        assertPiece(match.pieces(), dancer.player.id, x = 3, y = 6)
        // Free Sidestep spent the after-third window → turn hands off (no Extra Effort in hand).
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.AWAY)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `dance step is not offered after third when no Sidestep is available`() {
        lateinit var dancer: PlacedPlayer
        lateinit var allyA: PlacedPlayer
        lateinit var allyB: PlacedPlayer
        val match = match {
            // Open Glade Dancer — Sidestep illegal while Open.
            dancer = addPlayer(3, 6, profile = profile("wood-elf", "Glade Dancer"))
            allyA = addPlayer(5, 4)
            allyB = addPlayer(7, 4)
            addLooseBall(8, 8)
        }

        match.play(match.legalActions<LegalAction.Run>().first { it.playerId == dancer.player.id && it.path.steps == 1 })
        match.play(match.legalActions<LegalAction.Run>().first { it.playerId == allyA.player.id && it.path.steps == 1 })
        val response = match.play(match.legalActions<LegalAction.Run>().first { it.playerId == allyB.player.id && it.path.steps == 1 })

        assertThat(response.events.bareEvents().filterIsInstance<DomainEvent.InputRequired.PlayerAction>())
            .noneMatch { it.decisionType == PlayerDecisionType.DANCE_STEP_OFFER }
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.AWAY)
    }

    @Test
    fun `rally offers free stand up after a throw`() {
        lateinit var thrower: PlacedPlayer
        lateinit var receiver: PlacedPlayer
        lateinit var proneAlly: PlacedPlayer
        val match = match {
            // Official Ale Hog is Unthrowable — copy Rally onto a throwable profile for Throw path coverage.
            thrower = addPlayer(5, 6, profile = profile("human", "Thrower").copy(ability = PlayerAbility.Rally))
            holdingBall(thrower)
            receiver = addPlayer(5, 4, team = thrower.team)
            proneAlly = addPlayer(6, 6, status = PlayerStatus.PRONE, team = thrower.team)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(5, 4) }
        dice.queueD6(6)
        val response = match.play(throwAction)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.THROW_CONTINUE,
                ability = PlayerAbility.Rally,
                playerId = proneAlly.player.id,
            ),
        )
        val standUps = match.legalActions<LegalAction.StandUp>()
        assertThat(standUps.map { it.playerId }).containsExactly(proneAlly.player.id)
        match.play(standUps.single())
        assertThat(player(match.pieces(), proneAlly.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), receiver.player.id).holdsBall).isTrue()
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    // --- D1.G challenge-gated ---

    @Test
    fun `thirst block injures vampire after miss on claim step`() {
        lateinit var vampire: PlacedPlayer
        val match = match {
            vampire = addPlayer(5, 6, profile = profile("vampire", "Runner"))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)

        assertThat(player(match.pieces(), vampire.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `thirst does not injure vampire thrower after failed throw check`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("vampire", "Thrower"))
            holdingBall(thrower)
            addPlayer(5, 4, team = thrower.team)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(5, 4) }
        dice.queueD6(1) // fumble
        dice.queueD8(1) // bounce from thrower
        match.play(throwAction)

        assertThat(player(match.pieces(), thrower.player.id).status).isEqualTo(PlayerStatus.STANDING)
    }

    @Test
    fun `thirst skips when blocker scored touchdown before thirst beat`() {
        lateinit var vampire: PlacedPlayer
        val match = match {
            vampire = holdingBall(addPlayer(5, 15, profile = profile("vampire", "Runner")))
            addPlayer(6, 15, team = TeamSide.AWAY)
            addPlayer(7, 14, profile = profile("vampire", "Thrall"))
        }

        dice.queueBlockDice(BlockDieFace.MISS, BlockDieFace.SHOVE)
        val block = match.legalActions<LegalAction.Block>()
            .single { it.playerId == vampire.player.id }
        match.play(block)
        match.play(LegalAction.Skip(vampire.player.id))

        assertThat(player(match.pieces(), vampire.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(match.gameState().homeScore).isEqualTo(4)
    }

    @Test
    fun `devoted takes thirst injury instead of adjacent vampire`() {
        lateinit var vampire: PlacedPlayer
        lateinit var thrall: PlacedPlayer
        val match = match {
            vampire = addPlayer(5, 6, profile = profile("vampire", "Runner"))
            // Adjacent to vampire but not marking the block target (avoids assist / 2 dice).
            thrall = addPlayer(4, 6, profile = profile("vampire", "Thrall"))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        val block = match.legalActions<LegalAction.Block>()
            .single { it.playerId == vampire.player.id }
        match.play(block)

        assertThat(player(match.pieces(), vampire.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), thrall.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }
}
