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
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.assertNone
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Accept / decline (Skip) coverage for free ability grants and ability re-roll choices.
 */
class FreeAbilityGrantApiTest : BaseApiTest() {

    private fun profile(teamId: String, name: String) =
        // TODO(7.13): prefer TeamCatalogTestHelper.profileWith(ability) over teamId+name hardcodes.
        TeamCatalog.team(teamId).roster.first { it.name == name }

    @Test
    fun `skipping a free ability grant does not consume the action budget`() {
        lateinit var slayer: PlacedPlayer
        val match = match {
            actionsRemaining(3)
            slayer = addPlayer(3, 6, profile = TeamCatalog.dwarves().roster.first { it.name == "Doom Seeker" })
            addPlayer(5, 6, team = TeamSide.AWAY)
        }

        match.play<LegalAction.Mark>(slayer, 4, 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        match.play<LegalAction.Skip>()
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
    }

    @Test
    fun `free ability grant pending exposes block and skip for relentless after mark`() {
        lateinit var slayer: PlacedPlayer
        val match = match {
            slayer = addPlayer(3, 6, profile = TeamCatalog.dwarves().roster.first { it.name == "Doom Seeker" })
            addPlayer(5, 6, team = TeamSide.AWAY)
        }

        match.play<LegalAction.Mark>(slayer, 4, 6)

        assertThat(match.singleLegalAction<LegalAction.Block>(slayer)).isNotNull()
        assertThat(match.singleLegalAction<LegalAction.Skip>()).isNotNull()
    }

    @Test
    fun `accepting relentless after mark free Block does not consume budget`() {
        lateinit var slayer: PlacedPlayer
        val match = match {
            actionsRemaining(3)
            slayer = addPlayer(3, 6, profile = TeamCatalog.dwarves().roster.first { it.name == "Doom Seeker" })
            addPlayer(5, 6, team = TeamSide.AWAY)
        }

        match.play<LegalAction.Mark>(slayer, 4, 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        dice.queueBlockDice(BlockDieFace.SHOVE)
        match.play<LegalAction.Block>(slayer)
        match.play<LegalAction.Mark>(slayer)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `relentless then all-in tackle free block leaves blocker prone`() {
        lateinit var slayer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(3)
            slayer = addPlayer(3, 6, profile = TeamCatalog.dwarves().roster.first { it.name == "Doom Seeker" })
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(5, 6, team = TeamSide.AWAY)
        }

        match.play<LegalAction.Mark>(slayer, 4, 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play<LegalAction.Block>(slayer)

        val playBonus = match.singleLegalAction<LegalAction.PlayBonusCard>()
        assertThat(playBonus.bonusPlay).isEqualTo(BonusPlay.`All-In Tackle`)
        match.play(playBonus)

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play<LegalAction.Block>(slayer)

        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertPiece(match.pieces(), slayer.player.id, PlayerStatus.PRONE, 4, 6)
        match.legalActions().assertNone(ActionType.SKIP)
        match.legalActions().assertNone(ActionType.BLOCK)
    }

    @Test
    fun `relentless smash then all-in tackle is not offered when no standing target remains`() {
        lateinit var slayer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(3)
            slayer = addPlayer(3, 6, profile = TeamCatalog.dwarves().roster.first { it.name == "Doom Seeker" })
            addPlayer(5, 6, team = TeamSide.AWAY)
        }

        match.play<LegalAction.Mark>(slayer, 4, 6)

        dice.queueBlockDice(BlockDieFace.SMASH).queueD6(1)
        match.play<LegalAction.Block>(slayer)

        match.legalActions().assertNone(ActionType.PLAY_ALL_IN_TACKLE)
        match.legalActions().assertNone(ActionType.SKIP)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertPiece(match.pieces(), slayer.player.id, PlayerStatus.STANDING, 4, 6)
    }

    @Test
    fun `charge then all-in tackle leaves blocker prone and returns to select`() {
        lateinit var beast: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(3)
            beast = addPlayer(3, 6, profile = profile("doomsworn", "Beastkin"))
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        match.play<LegalAction.Run>(beast, 4, 6)
        match.play<LegalAction.Mark>(beast, 5, 6)

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play<LegalAction.Block>(beast)

        val playBonus = match.singleLegalAction<LegalAction.PlayBonusCard>()
        assertThat(playBonus.bonusPlay).isEqualTo(BonusPlay.`All-In Tackle`)
        match.play(playBonus)

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play<LegalAction.Block>(beast)

        assertPiece(match.pieces(), beast.player.id, PlayerStatus.PRONE, 5, 6)
        // Ability grants resolve before after-own DT, so no free-block grant is listed while prone.
        match.legalActions().assertNone(ActionType.SKIP)
        match.legalActions().assertNone(ActionType.BLOCK)
    }

    @Test
    fun `accepting strike and fade free Run does not consume budget`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            blitzer = addPlayer(5, 6, profile = profile("amazon", "Blitzer"))
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == blitzer.player.id && it.path.steps <= 2 }
        match.play(run)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertPiece(match.pieces(), blitzer.player.id, x = run.targetCoordinate.x, y = run.targetCoordinate.y)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `declining strike and fade free Run does not consume budget`() {
        val match = match {
            addPlayer(5, 6, profile = profile("amazon", "Blitzer"))
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `accepting charge free Block does not consume budget`() {
        lateinit var beast: PlacedPlayer
        val match = match {
            beast = addPlayer(3, 6, profile = profile("doomsworn", "Beastkin"))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == beast.player.id && it.targetCoordinate == Coordinate(4, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == beast.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.Block>().single { it.playerId == beast.player.id })
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
    }

    @Test
    fun `declining charge free Block does not consume budget`() {
        lateinit var beast: PlacedPlayer
        val match = match {
            beast = addPlayer(3, 6, profile = profile("doomsworn", "Beastkin"))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == beast.player.id && it.targetCoordinate == Coordinate(4, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == beast.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `accepting pass on the run free short Throw does not consume budget`() {
        lateinit var thrower: PlacedPlayer
        lateinit var receiver: PlacedPlayer
        val match = match {
            thrower = addPlayer(3, 6, profile = profile("gilded-crown", "Thrower"))
            holdingBall(thrower)
            receiver = addPlayer(3, 4, team = thrower.team)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == thrower.player.id && it.targetCoordinate == Coordinate(4, 6) },
        )
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == Coordinate(3, 4) && it.target.range == ThrowRange.SHORT }
        dice.queueD6(6)
        match.play(throwAction)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertThat(player(match.pieces(), receiver.player.id).holdsBall).isTrue()
    }

    @Test
    fun `declining pass on the run free Throw does not consume budget`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(3, 6, profile = profile("gilded-crown", "Thrower"))
            holdingBall(thrower)
            addPlayer(3, 4, team = thrower.team)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == thrower.player.id && it.targetCoordinate == Coordinate(4, 6) },
        )
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertThat(player(match.pieces(), thrower.player.id).holdsBall).isTrue()
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `accepting catch and go free Run does not consume budget`() {
        lateinit var thrower: PlacedPlayer
        lateinit var catcher: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("human", "Thrower"))
            holdingBall(thrower)
            catcher = addPlayer(5, 4, team = thrower.team, profile = profile("human", "Catcher"))
        }

        dice.queueD6(6)
        match.play(
            match.legalActions<LegalAction.Throw>()
                .first { it.target.destination == Coordinate(5, 4) },
        )
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        val run = match.legalActions<LegalAction.Run>().first { it.playerId == catcher.player.id }
        match.play(run)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertPiece(match.pieces(), catcher.player.id, x = run.targetCoordinate.x, y = run.targetCoordinate.y)
    }

    @Test
    fun `declining catch and go free Run does not consume budget`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("human", "Thrower"))
            holdingBall(thrower)
            addPlayer(5, 4, team = thrower.team, profile = profile("human", "Catcher"))
        }

        dice.queueD6(6)
        match.play(
            match.legalActions<LegalAction.Throw>()
                .first { it.target.destination == Coordinate(5, 4) },
        )
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `accepting rally free Stand Up does not consume budget`() {
        lateinit var boar: PlacedPlayer
        lateinit var adjacent: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            adjacent = addPlayer(6, 6, status = PlayerStatus.PRONE)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(5, 5) },
        )
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        match.play(match.legalActions<LegalAction.StandUp>().single { it.playerId == adjacent.player.id })
        assertThat(player(match.pieces(), adjacent.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
    }

    @Test
    fun `declining rally free Stand Up does not consume budget`() {
        lateinit var boar: PlacedPlayer
        lateinit var adjacent: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            adjacent = addPlayer(6, 6, status = PlayerStatus.PRONE)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(5, 5) },
        )
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(player(match.pieces(), adjacent.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `rally with two prone allies stands both up one grant at a time`() {
        lateinit var boar: PlacedPlayer
        lateinit var left: PlacedPlayer
        lateinit var right: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            left = addPlayer(4, 6, status = PlayerStatus.PRONE)
            right = addPlayer(6, 6, status = PlayerStatus.PRONE)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(5, 5) },
        )
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        val allyIds = setOf(left.player.id, right.player.id)
        val first = match.legalActions<LegalAction.StandUp>().single()
        assertThat(allyIds).contains(first.playerId)
        match.play(first)

        val second = match.legalActions<LegalAction.StandUp>().single()
        assertThat(second.playerId).isEqualTo(allyIds.single { it != first.playerId })
        match.play(second)

        assertThat(player(match.pieces(), left.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), right.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
        match.legalActions().assertNone(ActionType.STAND_UP)
    }

    @Test
    fun `skipping first rally grant still offers the second`() {
        lateinit var boar: PlacedPlayer
        lateinit var left: PlacedPlayer
        lateinit var right: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            left = addPlayer(4, 6, status = PlayerStatus.PRONE)
            right = addPlayer(6, 6, status = PlayerStatus.PRONE)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(5, 5) },
        )

        val firstId = match.legalActions<LegalAction.StandUp>().single().playerId
        match.play(match.legalActions<LegalAction.Skip>().single())
        assertThat(player(match.pieces(), firstId).status).isEqualTo(PlayerStatus.PRONE)

        val second = match.legalActions<LegalAction.StandUp>().single()
        assertThat(second.playerId).isNotEqualTo(firstId)
        match.play(second)

        assertThat(player(match.pieces(), second.playerId).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), firstId).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        // Free grants drained; skipped ally may still Stand Up as a budgeted action.
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `skipping both rally grants clears free offers and leaves allies prone`() {
        lateinit var boar: PlacedPlayer
        lateinit var left: PlacedPlayer
        lateinit var right: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            left = addPlayer(4, 6, status = PlayerStatus.PRONE)
            right = addPlayer(6, 6, status = PlayerStatus.PRONE)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(5, 5) },
        )

        match.play(match.legalActions<LegalAction.Skip>().single())
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(player(match.pieces(), left.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(match.pieces(), right.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `strike and fade free Run does not re-offer a free grant after it completes`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            blitzer = addPlayer(5, 6, profile = profile("amazon", "Blitzer"))
            addPlayer(3, 6) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == blitzer.player.id && it.path.steps <= 2 }
        match.play(run)

        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertPiece(match.pieces(), blitzer.player.id, x = run.targetCoordinate.x, y = run.targetCoordinate.y)
        // Free follow-up drained — no re-query of Strike and Fade after the add-on Run.
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `skipping dance step after third does not consume budget`() {
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
        match.play(match.legalActions<LegalAction.Run>().first { it.path.steps == 1 })

        match.play(match.legalActions<LegalAction.Skip>().single())

        assertPiece(match.pieces(), dancer.player.id, x = 4, y = 6)
        // Declining Dance Step ends the after-third window → turn hands off.
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.AWAY)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `declining undying free Stand Up leaves player prone with full budget`() {
        lateinit var skeleton: PlacedPlayer
        val match = match {
            activeCoach(TeamSide.AWAY)
            actionsRemaining(1)
            addPlayer(5, 6, team = TeamSide.AWAY)
            addPlayer(6, 6)
            addLooseBall(7, 8)
            skeleton = addPlayer(8, 6, status = PlayerStatus.PRONE, profile = profile("restless-dead", "Skeleton"))
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)
        resolveForcedChallengeReplaceIfPresent(match.matchId)

        assertThat(match.legalActions<LegalAction.StandUp>()).isNotEmpty()
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(player(match.pieces(), skeleton.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `quick release continue declines the interrupt and resumes the Block`() {
        lateinit var carrier: PlacedPlayer
        val match = match {
            addPlayer(5, 6)
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY, profile = profile("dark-elf", "Runner")))
            addPlayer(6, 4, team = TeamSide.AWAY)
        }

        match.play(ActionType.BLOCK)
        assertThat(match.legalActions<LegalAction.ContinueBlock>()).isNotEmpty()

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.ContinueBlock>().single())

        assertThat(player(match.pieces(), carrier.player.id).holdsBall).isTrue()
        assertThat(player(match.pieces(), carrier.player.id).status).isEqualTo(PlayerStatus.STANDING)
    }

    @Test
    fun `quick release accept Throw moves the ball before the Block resumes`() {
        lateinit var carrier: PlacedPlayer
        lateinit var receiver: PlacedPlayer
        lateinit var blocker: PlacedPlayer
        val match = match {
            blocker = addPlayer(5, 6)
            carrier = holdingBall(addPlayer(6, 6, team = TeamSide.AWAY, profile = profile("dark-elf", "Runner")))
            receiver = addPlayer(6, 4, team = TeamSide.AWAY)
        }

        match.play(ActionType.BLOCK)

        val dump = match.legalActions<LegalAction.QuickReleaseThrow>().first()
        dice.queueD6(6)
        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(dump)

        assertThat(player(match.pieces(), receiver.player.id).holdsBall).isTrue()
        assertThat(player(match.pieces(), carrier.player.id).holdsBall).isFalse()
        assertThat(player(match.pieces(), carrier).status).isEqualTo(PlayerStatus.PRONE)

        //  [DiceRolled(purpose=THROW, d6=6, d8=null, threshold=3, modifier=0, checkResult=PASS, blockFaces=null, blockResult=null, foulResult=null, ability=null, bonusPlay=null),
        //      BallThrown(from=Coordinate(x=6, y=6), to=Coordinate(x=6, y=4)),
        //      DiceRolled(purpose=BLOCK, d6=null, d8=null, threshold=null, modifier=null, checkResult=null, blockFaces=[SMASH], blockResult=SMASH, foulResult=null, ability=null, bonusPlay=null),
        //      PlayerStatusChanged(playerId=PlayerId(value=a0), playerAt=Coordinate(x=6, y=6), from=STANDING, to=PRONE),
        //      DiceRolled(purpose=ARMOUR, d6=6, d8=null, threshold=4, modifier=0, checkResult=PASS, blockFaces=null, blockResult=null, foulResult=null, ability=null, bonusPlay=null),
        //      ActionComplete(playerId=PlayerId(value=h0), playerAt=Coordinate(x=5, y=6), actionType=BLOCK),
        //      TeamActionConsumed(actionsRemaining=2),
        //      PlayerAction(team=HOME, decisionType=REGULAR_PLAYER_ACTION, ability=null)]
        assertThat (response.events.bareEvents()).isEqualTo(listOf<DomainEvent>(
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.THROW, d6 = 6, threshold = 3, modifier = 0, checkResult = DiceCheckResult.PASS),
            DomainEvent.BallThrown(from = Coordinate(6, 6), to = Coordinate(6, 4)),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.BLOCK, blockFaces = listOf(BlockDieFace.SMASH), blockResult = BlockDieFace.SMASH),
            DomainEvent.PlayerStatusChanged(playerId = carrier.player.id, playerAt = Coordinate(6, 6), from = PlayerStatus.STANDING, to = PlayerStatus.PRONE),
            DomainEvent.DiceRolled(purpose = DiceRollPurpose.ARMOUR, d6 = 6, threshold = 4, modifier = 0, checkResult = DiceCheckResult.PASS),
            DomainEvent.ActionComplete(playerId = blocker.player.id, playerAt = Coordinate(5, 6), actionType = ActionType.BLOCK),
            DomainEvent.TeamActionConsumed(actionsRemaining = 2),
            DomainEvent.InputRequired.PlayerAction(team = TeamSide.HOME, decisionType = PlayerDecisionType.REGULAR_PLAYER_ACTION, )
        ))
    }

    @Test
    fun `pinpoint passer accept re-roll can save a failed Throw`() {
        lateinit var thrower: PlacedPlayer
        lateinit var receiver: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("silverleaf", "Thrower"))
            holdingBall(thrower)
            receiver = addPlayer(5, 4, team = thrower.team)
        }

        dice.queueD6(1) // natural 1 → fumble
        match.play(
            match.legalActions<LegalAction.Throw>()
                .first { it.target.destination == Coordinate(5, 4) },
        )
        assertThat(match.legalActions<LegalAction.ChooseThrowReroll>().map { it.reroll })
            .containsExactlyInAnyOrder(true, false)

        dice.queueD6(6)
        match.play(match.legalActions<LegalAction.ChooseThrowReroll>().single { it.reroll })
        assertThat(player(match.pieces(), receiver.player.id).holdsBall).isTrue()
        match.legalActions().assertNone(ActionType.CHOOSE_THROW_REROLL)
    }

    @Test
    fun `pinpoint passer decline re-roll keeps the failed Throw`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            thrower = addPlayer(5, 6, profile = profile("silverleaf", "Thrower"))
            holdingBall(thrower)
            addPlayer(5, 4, team = thrower.team)
        }

        dice.queueD6(1)
        match.play(
            match.legalActions<LegalAction.Throw>()
                .first { it.target.destination == Coordinate(5, 4) },
        )
        dice.queueD8(1)
        match.play(match.legalActions<LegalAction.ChooseThrowReroll>().single { !it.reroll })

        assertThat(player(match.pieces(), thrower.player.id).holdsBall).isFalse()
        match.legalActions().assertNone(ActionType.CHOOSE_THROW_REROLL)
    }
}
