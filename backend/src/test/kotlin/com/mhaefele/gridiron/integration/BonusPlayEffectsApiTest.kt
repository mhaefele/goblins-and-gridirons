package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.pending.OfferHoldTheLine
import com.mhaefele.gridiron.model.pending.OfferGangUp
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertAny
import com.mhaefele.gridiron.support.assertNone
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * D1.E acceptance — one focused API test per bonus effect not already covered well by
 * [BonusPlayFrameworkApiTest] / [BonusPlayActionTaggingApiTest].
 *
 * Opposition interrupts seed the card in [CardState.awayHand]; after HOME completes the trigger
 * action, [MatchApi.legalActions] surfaces AWAY's Play* / free grant (respondingCoach).
 */
class BonusPlayEffectsApiTest : BaseApiTest() {

    private fun card(bonus: BonusPlay): ChallengeCardDefinition =
        ChallengeCardCatalog.allCards().first { it.bonusPlay == bonus }



    private fun adjacent(a: Coordinate, b: Coordinate): Boolean {
        val dx = abs(a.x - b.x)
        val dy = abs(a.y - b.y)
        return dx <= 1 && dy <= 1 && (dx != 0 || dy != 0)
    }

    @Test
    fun `Cut Off after opposition Run offers free Mark ending adjacent to runner`() {
        val cutOff = card(BonusPlay.`Cut Off`)
        lateinit var runner: PlacedPlayer
        lateinit var intervenor: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Cut Off`)
            actionsRemaining(2)
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            // Decoy: without mustEndAdjacentTo, intervenor could Mark this HOME player instead.
            addPlayer(8, 8, profile = TestProfiles.lineman())
            intervenor = addPlayer(8, 6, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == runner.player.id && it.path.squares.last() == Coordinate(6, 6) }
        match.play(run)

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`Cut Off`)

        match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).doesNotContain(cutOff)

        val marks = match.legalActions<LegalAction.Mark>()
        assertThat(marks).isNotEmpty
        assertThat(marks).allMatch { it.playerId == intervenor.player.id && adjacent(it.path.squares.last(), Coordinate(6, 6)) }
        // Decoy-only landing (8,7) marks (8,8) but is not adjacent to the runner.
        assertThat(marks.map { it.path.squares.last() }).doesNotContain(Coordinate(8, 7))

        val pick = marks.first { it.path.squares.last() == Coordinate(7, 6) }
        match.play(pick)
        assertPiece(match.pieces(), intervenor.player.id, x = 7, y = 6)
    }

    @Test
    fun `Cut Off is not offered when no Open ally can Mark adjacent to runner`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Cut Off`)
            actionsRemaining(2)
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            addPlayer(2, 8) // teammate keeps a non-empty menu after Run uses same-action-once
            // Open but too far for a Mark (range 2) to reach adjacency with the runner at (6,6).
            addPlayer(10, 10, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == runner.player.id && it.path.squares.last() == Coordinate(6, 6) }
        match.play(run)

        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).contains(card(BonusPlay.`Cut Off`))
    }

    @Test
    fun `Skipping Cut Off free Mark returns card to hand`() {
        val cutOff = card(BonusPlay.`Cut Off`)
        lateinit var runner: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Cut Off`)
            actionsRemaining(2)
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            addPlayer(8, 6, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == runner.player.id && it.path.squares.last() == Coordinate(6, 6) }
        match.play(run)

        val play = match.legalActions<LegalAction.PlayBonusCard>().single()
        val response = match.play(play)
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).doesNotContain(cutOff)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.`Cut Off`)

        val skip = match.legalActions<LegalAction.Skip>().single()
        val skipResponse = match.play(skip)
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).contains(cutOff)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).doesNotContain(BonusPlay.`Cut Off`)
        assertThat(skipResponse.events.map { it.event }.filterIsInstance<DomainEvent.BonusReturned>())
            .anyMatch { it.bonusPlay == BonusPlay.`Cut Off` }
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.BonusPlayed>()).isNotEmpty
    }

    @Test
    fun `Stick Close after opposition Sidestep moves marker into vacated square`() {
        val stickClose = card(BonusPlay.`Stick Close`)
        lateinit var marker: PlacedPlayer
        lateinit var sidestepper: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Stick Close`)
            actionsRemaining(2)
            marker = addPlayer(5, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
            sidestepper = addPlayer(6, 8, profile = TestProfiles.lineman())
        }

        val sidestep = match.legalActions<LegalAction.Sidestep>()
            .first { it.playerId == sidestepper.player.id && it.path.squares.last() == Coordinate(7, 8) }
        match.play(sidestep)

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`Stick Close`)

        val response = match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).doesNotContain(stickClose)
        assertPiece(match.pieces(), marker.player.id, x = 6, y = 8)
        assertPiece(match.pieces(), sidestepper.player.id, x = 7, y = 8)
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.PlayerMoved>())
            .anyMatch { it.bonusPlay == BonusPlay.`Stick Close` && it.to == Coordinate(6, 8) }
    }

    @Test
    fun `Do-Over after throw check re-rolls then resolves`() {
        val talent = card(BonusPlay.`Do-Over`)
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(2)
            val thrower = addPlayer(4, 6, profile = TestProfiles.withMove(6))
            addPlayer(6, 6, profile = TestProfiles.lineman())
            holdingBall(thrower)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.range != ThrowRange.HAND_OFF }
        dice.queueD6(1) // fail first check
        match.play(throwAction)

        val adjust = match.legalActions<LegalAction.AdjustThrowByDoOver>()
        assertThat(adjust).hasSize(1)
        assertThat(adjust.single().naturalRoll).isEqualTo(1)
        assertThat(adjust).hasSize(1)

        dice.queueD6(6) // Do-Over re-roll succeeds
        val response = match.play(adjust.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(talent)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.`Do-Over`)
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.DiceRolled>())
            .anyMatch { it.bonusPlay == BonusPlay.`Do-Over` && it.d6 == 6 }
        assertThat(match.pieces().players.any { it.holdsBall }).isTrue()
    }

    @Test
    fun `Do-Over on a Pinpoint Passer does not open a second reroll`() {
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(2)
            val thrower = addPlayer(4, 6, profile = TeamCatalog.team("silverleaf").roster.first { it.name == "Thrower" })
            addPlayer(6, 6, profile = TestProfiles.lineman())
            holdingBall(thrower)
        }

        dice.queueD6(1)
        match.play(match.legalActions<LegalAction.Throw>().first { it.target.range != ThrowRange.HAND_OFF })

        assertThat(match.legalActions<LegalAction.AdjustThrowByDoOver>()).hasSize(1)
        assertThat(match.legalActions<LegalAction.ChooseThrowReroll>().map { it.reroll }).containsExactlyInAnyOrder(true, false)

        dice.queueD6(1)
        dice.queueD8(1)
        match.play(match.legalActions<LegalAction.AdjustThrowByDoOver>().single())

        match.legalActions().assertNone(ActionType.CHOOSE_THROW_REROLL)
        match.legalActions().assertNone(ActionType.ADJUST_THROW_BY_DO_OVER)
    }

    @Test
    fun `Do-Over after armour hold Skip keeps target prone`() {
        val talent = card(BonusPlay.`Do-Over`)
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH) // optimal block — no Do-Over
        dice.queueD6(6) // armour holds — Do-Over offered
        match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(match.legalActions<LegalAction.AdjustArmourByDoOver>().single().naturalRoll).isEqualTo(6)
        assertThat(match.legalActions<LegalAction.AdjustArmourByDoOver>()).hasSize(1)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)

        match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(talent)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `Do-Over skipped when armour already breaks`() {
        val talent = card(BonusPlay.`Do-Over`)
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(1) // armour fails — injury already optimal
        match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(talent)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `Do-Over skipped when throw already succeeds`() {
        val talent = card(BonusPlay.`Do-Over`)
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(2)
            val thrower = addPlayer(4, 6, profile = TestProfiles.withMove(6))
            addPlayer(6, 6, profile = TestProfiles.lineman())
            holdingBall(thrower)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.range != ThrowRange.HAND_OFF }
        dice.queueD6(6)
        match.play(throwAction)

        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(talent)
        assertThat(match.pieces().players.any { it.holdsBall }).isTrue()
    }

    @Test
    fun `Slip Away after opposition Mark interrupts with free Sidestep`() {
        val slipAway = card(BonusPlay.`Slip Away`)
        lateinit var marker: PlacedPlayer
        lateinit var marked: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Slip Away`)
            actionsRemaining(2)
            marker = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            marked = addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.playerId == marker.player.id && it.path.squares.last() == Coordinate(6, 8) }
        match.play(mark)

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`Slip Away`)

        match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).doesNotContain(slipAway)

        val sidesteps = match.legalActions<LegalAction.Sidestep>()
        assertThat(sidesteps).isNotEmpty
        assertThat(sidesteps).allMatch { it.playerId == marked.player.id }
        // Must end Open — (8,8) is clear of the Marker at (6,8); (7,7) would stay Marked.
        val step = sidesteps.first { it.path.squares.last() == Coordinate(8, 8) }
        match.play(step)
        assertPiece(match.pieces(), marked.player.id, x = 8, y = 8)
    }

    @Test
    fun `Stick Close after Slip Away Sidestep moves marker into vacated square`() {
        val stickClose = card(BonusPlay.`Stick Close`)
        lateinit var marker: PlacedPlayer
        lateinit var marked: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Stick Close`)
            awayBonus(BonusPlay.`Slip Away`)
            actionsRemaining(2)
            marker = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            marked = addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == marker.player.id && it.path.squares.last() == Coordinate(6, 8) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())
        match.play(
            match.legalActions<LegalAction.Sidestep>()
                .first { it.playerId == marked.player.id && it.path.squares.last() == Coordinate(8, 8) },
        )

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`Stick Close`)

        val response = match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(stickClose)
        assertPiece(match.pieces(), marker.player.id, x = 7, y = 8)
        assertPiece(match.pieces(), marked.player.id, x = 8, y = 8)
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.PlayerMoved>())
            .anyMatch { it.bonusPlay == BonusPlay.`Stick Close` && it.to == Coordinate(7, 8) }
    }

    @Test
    fun `Sprint auto-plays and allows Move plus two`() {
        val sprint = card(BonusPlay.Sprint)
        lateinit var runner: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Sprint)
            actionsRemaining(2)
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
        }

        val sprintRun = match.legalActions<LegalAction.Run>()
            .first { it.requiredBonusCard == BonusPlay.Sprint && it.path.steps == 4 }
        match.play(sprintRun)

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(sprint)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.Sprint)
        assertPiece(
            match.pieces(),
            runner.player.id,
            x = sprintRun.path.squares.last().x,
            y = sprintRun.path.squares.last().y,
        )
    }

    @Test
    fun `All-In Tackle after Mark grants free Block then knocks blocker prone`() {
        val diving = card(BonusPlay.`All-In Tackle`)
        lateinit var marker: PlacedPlayer
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(2)
            marker = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.playerId == marker.player.id && it.path.squares.last() == Coordinate(6, 8) }
        match.play(mark)

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`All-In Tackle`)
        match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(diving)

        val block = match.singleLegalAction<LegalAction.Block>(marker)
        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        match.play(block)

        assertPiece(match.pieces(), target.player.id, status = PlayerStatus.PRONE, x = 7, y = 8)
        assertPiece(match.pieces(), marker.player.id, status = PlayerStatus.PRONE, x = 6, y = 8)
    }

    @Test
    fun `All-In Tackle prone drops blocker ball and bounces`() {
        lateinit var marker: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(2)
            marker = holdingBall(addPlayer(5, 8, profile = TestProfiles.withMove(2)))
            addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.playerId == marker.player.id && it.path.squares.last() == Coordinate(6, 8) }
        match.play(mark)
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6) // target armour holds
        dice.queueD8(1) // bounce north from blocker square after All-In Tackle prone
        match.play(match.singleLegalAction<LegalAction.Block>(marker))

        val pieces = match.pieces()
        assertPiece(pieces, marker.player.id, status = PlayerStatus.PRONE, x = 6, y = 8)
        assertThat(player(pieces, marker.player.id).holdsBall).isFalse()
        assertThat(pieces.looseBallLocations).containsExactly(Coordinate(6, 7))
    }

    @Test
    fun `All-In Tackle prone with Ball Guard lets coach place the dropped ball`() {
        // All-In Tackle nests blocker knock-down (and Ball Guard) under Mark's free Block.
        lateinit var marker: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(2)
            marker = holdingBall(
                addPlayer(5, 8, profile = TeamCatalog.dwarves().roster.first { it.name == "Runner" }),
            )
            addPlayer(4, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play<LegalAction.Mark>(marker, 6, 8)
        match.play<LegalAction.PlayBonusCard>()

        dice.queueBlockDice(BlockDieFace.MISS)
        val response = match.play<LegalAction.Block>(marker)

        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.MARK_CONTINUE,
                ability = PlayerAbility.`Ball Guard`,
                playerId = marker.player.id,
            ),
        )
        assertPiece(match.pieces(), marker.player.id, PlayerStatus.PRONE, 6, 8)
        assertThat(player(match.pieces(), marker.player.id).holdsBall).isFalse()
        val place = match.legalActions<LegalAction.PlaceLooseBall>().first()
        match.play(place)
        assertThat(match.pieces().looseBallLocations).contains(place.to)
        assertThat(match.legalActions<LegalAction.PlaceLooseBall>()).isEmpty()
    }

    @Test
    fun `Brace after Block target lets opposition play before dice`() {
        val tough = card(BonusPlay.Brace)
        val match = match {
            awayBonus(BonusPlay.Brace)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(match.legalActions<LegalAction.Block>().first())

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.Brace)

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        match.play(plays.single())

        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).doesNotContain(tough)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.Brace)
        assertThat(match.pieces().players.any { it.status == PlayerStatus.PRONE }).isTrue()
    }

    @Test
    fun `declining Cut Off keeps card and resumes without free Mark`() {
        val cutOff = card(BonusPlay.`Cut Off`)
        lateinit var runner: PlacedPlayer
        lateinit var intervenor: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Cut Off`)
            actionsRemaining(2)
            runner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            intervenor = addPlayer(8, 6, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == runner.player.id && it.path.squares.last() == Coordinate(6, 6) }
        match.play(run)

        assertThat(match.legalActions<LegalAction.PlayBonusCard>().single().bonusPlay)
            .isEqualTo(BonusPlay.`Cut Off`)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).contains(cutOff)
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions<LegalAction.Skip>()).isEmpty()
        assertPiece(match.pieces(), intervenor.player.id, x = 8, y = 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
    }

    @Test
    fun `declining Stick Close keeps marker in place and card in hand`() {
        val stickClose = card(BonusPlay.`Stick Close`)
        lateinit var marker: PlacedPlayer
        lateinit var sidestepper: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Stick Close`)
            actionsRemaining(2)
            marker = addPlayer(5, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
            sidestepper = addPlayer(6, 8, profile = TestProfiles.lineman())
        }

        val sidestep = match.legalActions<LegalAction.Sidestep>()
            .first { it.playerId == sidestepper.player.id && it.path.squares.last() == Coordinate(7, 8) }
        match.play(sidestep)

        match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).contains(stickClose)
        assertPiece(match.pieces(), marker.player.id, x = 5, y = 8)
        assertPiece(match.pieces(), sidestepper.player.id, x = 7, y = 8)
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
    }

    @Test
    fun `declining Slip Away keeps marked player in place and card in hand`() {
        val slipAway = card(BonusPlay.`Slip Away`)
        lateinit var marked: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Slip Away`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            marked = addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        val mark = match.legalActions<LegalAction.Mark>()
            .first { it.path.squares.last() == Coordinate(6, 8) }
        match.play(mark)

        match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).contains(slipAway)
        assertPiece(match.pieces(), marked.player.id, x = 7, y = 8)
        assertThat(match.legalActions<LegalAction.Skip>()).isEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions<LegalAction.Sidestep>().none { it.playerId == marked.player.id }).isTrue()
    }

    @Test
    fun `declining Slip Away closes Mark so a later action can begin`() {
        lateinit var reserve: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Fresh Legs`)
            awayBonus(BonusPlay.`Slip Away`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
            reserve = addReservesPlayer()
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.path.squares.last() == Coordinate(6, 8) },
        )
        val declineSlipAway = match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        assertThat(declineSlipAway.events.bareEvents().filterIsInstance<DomainEvent.ActionComplete>())
            .anyMatch { it.actionType == ActionType.MARK }

        val deploy = match.legalActions<LegalAction.Reserves>()
            .first { it.playerId == reserve.player.id }
        match.play(deploy)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertPiece(match.pieces(), reserve.player.id, PlayerStatus.STANDING, deploy.placement)
    }

    @Test
    fun `Skipping Slip Away free Sidestep leaves card not discarded`() {
        val slipAway = card(BonusPlay.`Slip Away`)
        lateinit var marked: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Slip Away`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            marked = addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.path.squares.last() == Coordinate(6, 8) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).doesNotContain(slipAway)

        val skip = match.play(match.legalActions<LegalAction.Skip>().single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).contains(slipAway)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).doesNotContain(BonusPlay.`Slip Away`)
        assertPiece(match.pieces(), marked.player.id, x = 7, y = 8)
        assertThat(skip.events.bareEvents().filterIsInstance<DomainEvent.ActionComplete>())
            .anyMatch { it.actionType == ActionType.MARK }
    }

    @Test
    fun `declining All-In Tackle keeps card without free Block grant`() {
        val diving = card(BonusPlay.`All-In Tackle`)
        lateinit var marker: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(2)
            marker = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == marker.player.id && it.path.squares.last() == Coordinate(6, 8) },
        )
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(diving)
        assertThat(match.legalActions<LegalAction.Skip>()).isEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
    }

    @Test
    fun `Skipping All-In Tackle free Block leaves card discarded and blocker standing`() {
        val diving = card(BonusPlay.`All-In Tackle`)
        lateinit var marker: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(2)
            marker = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == marker.player.id && it.path.squares.last() == Coordinate(6, 8) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(diving)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.`All-In Tackle`)
        assertPiece(match.pieces(), marker.player.id, status = PlayerStatus.STANDING, x = 6, y = 8)
    }

    @Test
    fun `declining Brace still resolves block dice`() {
        val tough = card(BonusPlay.Brace)
        lateinit var target: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.Brace)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(match.legalActions<LegalAction.Block>().first())
        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.AWAY)).contains(tough)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
    }

    @Test
    fun `Momentum then All-In Tackle in after-own window play sequentially one grant at a time`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum, BonusPlay.`All-In Tackle`)
            actionsRemaining(3)
            blitzer = addPlayer(4, 6, profile = TestProfiles.withMove(4))
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
            addPlayer(7, 7, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )

        val firstWindow = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(firstWindow.map { it.bonusPlay }).contains(BonusPlay.Momentum, BonusPlay.`All-In Tackle`)
        match.play(firstWindow.first { it.bonusPlay == BonusPlay.Momentum })

        // Only Momentum grant — free Block, no stacked second window.
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions<LegalAction.Block>().filter { it.playerId == blitzer.player.id }).isNotEmpty()

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.Block>().first { it.playerId == blitzer.player.id })

        val secondWindow = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(secondWindow.map { it.bonusPlay }).containsExactly(BonusPlay.`All-In Tackle`)
        match.play(secondWindow.single())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).isEmpty()
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.Momentum, BonusPlay.`All-In Tackle`)
        assertThat(match.legalActions<LegalAction.Block>().filter { it.playerId == blitzer.player.id }).isNotEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.Block>().first { it.playerId == blitzer.player.id })
        // Open Mark finished — back to budgeted picks; no mid-action Skip / bonus window.
        match.legalActions().assertNone(ActionType.SKIP)
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions<LegalAction.DeclineBonus>()).isEmpty()
    }

    @Test
    fun `Momentum free Block Strike and Fade free Run completes without a further free grant`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum)
            actionsRemaining(3)
            blitzer = addPlayer(4, 6, profile = TestProfiles.withMove(4).copy(ability = PlayerAbility.`Strike and Fade`))
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.singleLegalAction<LegalAction.Block>(blitzer, 7, 6))

        val strikeAndFade = match.legalActions<LegalAction.Run>()
            .first { it.playerId == blitzer.player.id && it.path.steps <= 2 }
        match.play(strikeAndFade)

        assertPiece(match.pieces(), blitzer.player.id, x = strikeAndFade.targetCoordinate.x, y = strikeAndFade.targetCoordinate.y)
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `Momentum free Block Strike and Fade skip clears the free grant`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum)
            actionsRemaining(3)
            blitzer = addPlayer(4, 6, profile = TestProfiles.withMove(4).copy(ability = PlayerAbility.`Strike and Fade`))
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.singleLegalAction<LegalAction.Block>(blitzer, 7, 6))

        assertThat(match.legalActions<LegalAction.Run>()).isNotEmpty
        assertThat(match.legalActions<LegalAction.Run>()).allMatch { it.path.steps <= 2 }
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertPiece(match.pieces(), blitzer.player.id, x = 6, y = 6)
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `Momentum free Block Relentless After Block free Block re-offers then skip clears`() {
        // EXECUTE_AFTER_OWN re-queries Block abilities after every completed add-on Block —
        // so Relentless After Block can chain until Skip (or no markable target remains).
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum)
            actionsRemaining(3)
            blitzer = addPlayer(
                4, 6,
                profile = TestProfiles.withMove(4).copy(ability = PlayerAbility.`Relentless After Block`),
            )
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
            addPlayer(7, 7, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.singleLegalAction<LegalAction.Block>(blitzer, 7, 6))

        val firstRelentless = match.legalActions<LegalAction.Block>().first { it.playerId == blitzer.player.id }
        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(firstRelentless)

        assertThat(match.legalActions<LegalAction.Block>()).isNotEmpty
        match.legalActions().assertAny(ActionType.SKIP)
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `Momentum free Block Relentless After Block skip clears the free grant`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum)
            actionsRemaining(3)
            blitzer = addPlayer(
                4, 6,
                profile = TestProfiles.withMove(4).copy(ability = PlayerAbility.`Relentless After Block`),
            )
            addPlayer(2, 8) // teammate keeps a non-empty menu after Miss turn-end
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
            addPlayer(7, 7, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.singleLegalAction<LegalAction.Block>(blitzer, 7, 6))

        assertThat(match.legalActions<LegalAction.Block>()).isNotEmpty
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `Momentum after Run then Mark offers free Block on accept`() {
        val momentum = card(BonusPlay.Momentum)
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum)
            actionsRemaining(3)
            blitzer = addPlayer(4, 6, profile = TestProfiles.withMove(4))
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )

        val play = match.legalActions<LegalAction.PlayBonusCard>().single()
        assertThat(play.bonusPlay).isEqualTo(BonusPlay.Momentum)
        match.play(play)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(momentum)

        val block = match.singleLegalAction<LegalAction.Block>(blitzer, 7, 6)
        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(block)
        // Free Block — budget still has one RegularPlayerAction left after Run+Mark.
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
    }

    @Test
    fun `declining Momentum keeps card without free Block grant`() {
        val momentum = card(BonusPlay.Momentum)
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum)
            actionsRemaining(3)
            blitzer = addPlayer(4, 6, profile = TestProfiles.withMove(4))
            addPlayer(7, 6, team = TeamSide.AWAY)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(momentum)
        assertThat(match.legalActions<LegalAction.Skip>()).isEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
    }

    @Test
    fun `Skipping Momentum free Block leaves card discarded`() {
        val momentum = card(BonusPlay.Momentum)
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum)
            actionsRemaining(3)
            blitzer = addPlayer(4, 6, profile = TestProfiles.withMove(4))
            addPlayer(7, 6, team = TeamSide.AWAY)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())
        match.play(match.legalActions<LegalAction.Skip>().single())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(momentum)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.Momentum)
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
    }

    @Test
    fun `Spiked Gauntlets accept applies AV 6 before armour`() {
        val bladed = card(BonusPlay.`Spiked Gauntlets`)
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Spiked Gauntlets`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        match.play(match.legalActions<LegalAction.Block>().first())

        val play = match.legalActions<LegalAction.PlayBonusCard>().single()
        assertThat(play.bonusPlay).isEqualTo(BonusPlay.`Spiked Gauntlets`)
        dice.queueD6(5) // fails AV 6+, would hold on dwarf AV 5+
        match.play(play)

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(bladed)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }

    @Test
    fun `declining Spiked Gauntlets keeps card and rolls normal armour`() {
        val bladed = card(BonusPlay.`Spiked Gauntlets`)
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Spiked Gauntlets`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        match.play(match.legalActions<LegalAction.Block>().first())
        dice.queueD6(5) // holds on dwarf AV 5+
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(bladed)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
    }

    @Test
    fun `Extra Effort is offered after third action when card is in hand`() {
        val extraEffort = card(BonusPlay.`Extra Effort`)
        val match = match {
            homeBonus(BonusPlay.`Extra Effort`)
            addPlayer(2, 3, profile = TestProfiles.withMove(2))
            addPlayer(2, 2, profile = TestProfiles.withMove(2))
            addPlayer(2, 4, profile = TestProfiles.withMove(2))
            addLooseBall(8, 8)
        }

        match.play(match.legalActions<LegalAction.Run>().first { it.path.steps == 1 })
        match.play(match.legalActions<LegalAction.Run>().first { it.path.steps == 1 })
        val response = match.play(match.legalActions<LegalAction.Run>().first { it.path.steps == 1 })

        assertThat(match.gameState().actionsRemaining).isEqualTo(0)
        assertThat(response.events.bareEvents()).contains(
            DomainEvent.InputRequired.PlayerAction(
                TeamSide.HOME,
                PlayerDecisionType.EXTRA_EFFORT_OFFER,
            ),
        )
        val play = match.legalActions<LegalAction.PlayBonusCard>().single()
        assertThat(play.bonusPlay).isEqualTo(BonusPlay.`Extra Effort`)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(extraEffort)
    }

    @Test
    fun `Extra Effort accept after third action grants a fourth`() {
        val extraEffort = card(BonusPlay.`Extra Effort`)
        val match = match {
            cards(
                CardState(
                    activeSlots = listOf(null, null, null),
                    deck = emptyList(),
                    homeHand = listOf(extraEffort),
                ),
            )
            actionsRemaining(0)
            pendingDecision(
                OfferExtraEffort(TeamSide.HOME),
            )
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        val play = match.legalActions<LegalAction.PlayBonusCard>().single()
        assertThat(play.bonusPlay).isEqualTo(BonusPlay.`Extra Effort`)
        match.play(play)

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(extraEffort)
        assertThat(match.gameState().actionsRemaining).isEqualTo(1)
        assertThat(match.legalActions()).isNotEmpty()
    }

    @Test
    fun `declining Extra Effort leaves turn with no further actions`() {
        val extraEffort = card(BonusPlay.`Extra Effort`)
        val match = match {
            cards(
                CardState(
                    activeSlots = listOf(null, null, null),
                    deck = emptyList(),
                    homeHand = listOf(extraEffort),
                ),
            )
            actionsRemaining(0)
            pendingDecision(
                OfferExtraEffort(TeamSide.HOME),
            )
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            addLooseBall(5, 9)
        }

        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(extraEffort)
        // Budget exhausted and declined — HOME's turn ends; no Extra Effort fourth action.
        assertThat(match.gameState().activeCoach).isEqualTo(TeamSide.AWAY)
    }

    @Test
    fun `Hold the Line accept sets modifier and enters PLAYER_ACTION`() {
        val defensive = card(BonusPlay.`Hold the Line`)
        val match = match {
            homeBonus(BonusPlay.`Hold the Line`)
            phase(TurnPhase.PLAYER_ACTION)
            pendingDecision(
                OfferHoldTheLine(TeamSide.HOME),
            )
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
        }

        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())
        val state = loadMatch(match)
        assertThat(state.cards.handCards(TeamSide.HOME)).doesNotContain(defensive)
        assertThat(state.currentTurnState.bonusModifiers.holdTheLine).isTrue()
        assertThat(state.currentTurnState.phase).isEqualTo(TurnPhase.PLAYER_ACTION)
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions()).isNotEmpty()
    }

    @Test
    fun `Gang Up accept sets assisted-blocks modifier`() {
        val blocking = card(BonusPlay.`Gang Up`)
        val match = match {
            homeBonus(BonusPlay.`Gang Up`)
            phase(TurnPhase.TURN_START)
            pendingDecision(
                OfferGangUp(TeamSide.HOME),
            )
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            addPlayer(6, 6, team = TeamSide.AWAY)
        }

        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())
        val state = loadMatch(match)
        assertThat(state.cards.handCards(TeamSide.HOME)).doesNotContain(blocking)
        assertThat(state.currentTurnState.bonusModifiers.gangUp).isTrue()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions()).isNotEmpty()
    }

    @Test
    fun `declining Gang Up keeps card and continues into the turn`() {
        val blocking = card(BonusPlay.`Gang Up`)
        val match = match {
            homeBonus(BonusPlay.`Gang Up`)
            // Production advances past TURN_START/GANG_UP in the same beat as the offer.
            phase(TurnPhase.PRE_TURN)
            pendingDecision(
                OfferGangUp(TeamSide.HOME),
            )
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
        }

        match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        val state = loadMatch(match)
        assertThat(state.cards.handCards(TeamSide.HOME)).contains(blocking)
        assertThat(state.currentTurnState.bonusModifiers.gangUp).isFalse()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions()).isNotEmpty()
    }

    @Test
    fun `Do-Over after armour accept re-rolls the check`() {
        val talent = card(BonusPlay.`Do-Over`)
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Do-Over`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6) // armour holds — Do-Over offered
        match.play(match.legalActions<LegalAction.Block>().first())

        dice.queueD6(1) // re-roll breaks armour
        match.play(match.legalActions<LegalAction.AdjustArmourByDoOver>().single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(talent)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.RESERVES)
    }
}
