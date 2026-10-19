package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.assertPiece
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Action-tagged geometry bonuses + post-submit roll windows (Accurate / Underdog / Hail Mary on legal throws).
 */
class BonusPlayActionTaggingApiTest : BaseApiTest() {

    private fun card(bonus: BonusPlay): ChallengeCardDefinition =
        ChallengeCardCatalog.allCards().first { it.bonusPlay == bonus }

    @Test
    fun `Sprint tags only destinations beyond base Move and auto-plays on submit`() {
        val sprint = card(BonusPlay.Sprint)
        val match = match {
            homeBonus(BonusPlay.Sprint)
            actionsRemaining(2)
            addPlayer(4, 6, profile = TestProfiles.withMove(2))
        }

        val runs = match.legalActions<LegalAction.Run>()
        val base = runs.filter { it.requiredBonusCard == null }
        val sprintOnly = runs.filter { it.requiredBonusCard != null }

        assertThat(base).isNotEmpty
        assertThat(base).allMatch { it.path.steps <= 2 }
        assertThat(sprintOnly).isNotEmpty
        assertThat(sprintOnly).allMatch { it.requiredBonusCard?.bonusPlay == BonusPlay.Sprint && it.path.steps in 3..4 && it.id.value.contains("_BONUS_Sprint") }
        // No duplicate Sprint twin for a base-reachable path.
        val baseKeys = base.map { it.path.squares }.toSet()
        assertThat(sprintOnly.map { it.path.squares }).noneMatch { it in baseKeys }

        val pick = sprintOnly.first { it.path.steps == 4 }
        match.play(pick)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(sprint)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.Sprint)
        assertPiece(match.pieces(), pick.playerId, x = pick.path.squares.last().x, y = pick.path.squares.last().y)
    }

    @Test
    fun `Sprint alone does not open a Play* window`() {
        val match = match {
            homeBonus(BonusPlay.Sprint)
            actionsRemaining(2)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
        }
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions<LegalAction.Run>()).isNotEmpty()
    }

    @Test
    fun `Weave tags only destinations unreachable without it`() {
        val weave = card(BonusPlay.Weave)
        // Opp at (5,6) chokes the straight line; Move 4 cannot go around clear, so (7,6) needs Weave.
        val choke = match {
            homeBonus(BonusPlay.Weave)
            actionsRemaining(2)
            addPlayer(3, 6, profile = TestProfiles.withMove(4))
            addPlayer(5, 6, team = TeamSide.AWAY)
        }

        val chokeRuns = choke.legalActions<LegalAction.Run>()
        val chokeBase = chokeRuns.filter { it.requiredBonusCard == null }
        val chokeWeave = chokeRuns.filter { it.requiredBonusCard != null }

        assertThat(chokeWeave).isNotEmpty
        assertThat(chokeWeave).allMatch { it.requiredBonusCard?.bonusPlay == BonusPlay.Weave }
        val chokeBaseDest = chokeBase.map { it.targetCoordinate }.toSet()
        assertThat(chokeWeave.map { it.targetCoordinate }).noneMatch { it in chokeBaseDest }
        assertThat(chokeWeave.map { it.targetCoordinate }).anyMatch { it.x == 7 && it.y == 6 }

        // Same layout with Move 6: (7,6) is clear-path reachable — no Weave twin for that square.
        val clear = match {
            homeBonus(BonusPlay.Weave)
            actionsRemaining(2)
            addPlayer(3, 6, profile = TestProfiles.withMove(6))
            addPlayer(5, 6, team = TeamSide.AWAY)
        }
        val clearRuns = clear.legalActions<LegalAction.Run>()
        val clearBaseDest = clearRuns.filter { it.requiredBonusCard == null }.map { it.targetCoordinate }.toSet()
        val clearWeaveDest = clearRuns.filter { it.requiredBonusCard != null }.map { it.targetCoordinate }.toSet()
        assertThat(clearBaseDest).anyMatch { it.x == 7 && it.y == 6 }
        assertThat(clearWeaveDest).noneMatch { it.x == 7 && it.y == 6 }
        assertThat(clearWeaveDest.intersect(clearBaseDest)).isEmpty()
    }

    @Test
    fun `Press tags only paths beyond range 2`() {
        val aggressive = card(BonusPlay.Press)
        val match = match {
            homeBonus(BonusPlay.Press)
            actionsRemaining(2)
            addPlayer(4, 6, profile = TestProfiles.withMove(6))
            addPlayer(8, 6, team = TeamSide.AWAY)
        }

        val marks = match.legalActions<LegalAction.Mark>()
        val base = marks.filter { it.requiredBonusCard == null }
        val long = marks.filter { it.requiredBonusCard != null }
        assertThat(base).allMatch { it.path.steps <= 2 }
        assertThat(long).isNotEmpty
        assertThat(long).allMatch { it.requiredBonusCard?.bonusPlay == BonusPlay.Press && it.path.steps in 3..4 }
        match.play(long.first())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(aggressive)
    }

    @Test
    fun `Hail Mary is tagged on Throw and offered after submit`() {
        val hail = card(BonusPlay.`Hail Mary Pass`)
        lateinit var thrower: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Hail Mary Pass`)
            actionsRemaining(2)
            thrower = addPlayer(2, 6, profile = TestProfiles.withMove(6))
            addPlayer(10, 6)
            holdingBall(thrower)
        }

        val throws = match.legalActions<LegalAction.Throw>()
        assertThat(throws).isNotEmpty
        assertThat(throws).allMatch { it.requiredBonusCard == null }
        assertThat(throws).allMatch {
            it.availableBonusCards.any { c -> c.bonusPlay == BonusPlay.`Hail Mary Pass` }
        }

        match.play(throws.first())
        val bonusPlay = match.singleLegalAction<LegalAction.PlayBonusCard>()
        assertThat(bonusPlay.bonusPlay).isEqualTo(BonusPlay.`Hail Mary Pass`)

        dice.queueD6(4)
        match.play(bonusPlay)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(hail)
    }

    @Test
    fun `Dead Eye is tagged on Throw and offered after submit`() {
        val accurate = card(BonusPlay.`Dead Eye`)
        lateinit var thrower: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Dead Eye`)
            actionsRemaining(2)
            thrower = addPlayer(4, 6, profile = TestProfiles.withMove(6))
            addPlayer(6, 6)
            holdingBall(thrower)
        }

        val throwAction = match.legalActions<LegalAction.Throw>()
            .first { it.target.range != ThrowRange.HAND_OFF }
        assertThat(throwAction.requiredBonusCard).isNull()
        assertThat(throwAction.availableBonusCards).anyMatch { it.bonusPlay == BonusPlay.`Dead Eye` }
        match.play(throwAction)

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays).hasSize(1)
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`Dead Eye`)

        dice.queueD6(2)
        match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(accurate)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.`Dead Eye`)
    }

    @Test
    fun `Skip Dead Eye window still resolves the throw`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Dead Eye`)
            actionsRemaining(2)
            thrower = addPlayer(4, 6, profile = TestProfiles.withMove(6))
            addPlayer(6, 6)
            holdingBall(thrower)
        }

        match.play(
            match.legalActions<LegalAction.Throw>()
                .first { it.target.range != ThrowRange.HAND_OFF },
        )
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.DiceRolled>()).isNotEmpty()
    }

    @Test
    fun `declining Underdog still rolls unassisted block dice`() {
        val underdog = card(BonusPlay.Underdog)
        lateinit var target: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Underdog)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            target = addPlayer(6, 8, team = TeamSide.AWAY)
        }

        match.play(match.legalActions<LegalAction.Block>().first())
        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(underdog)
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
    }

    @Test
    fun `declining Hail Mary still resolves the throw check`() {
        val hail = card(BonusPlay.`Hail Mary Pass`)
        lateinit var thrower: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Hail Mary Pass`)
            actionsRemaining(2)
            thrower = addPlayer(2, 6, profile = TestProfiles.withMove(6))
            addPlayer(10, 6)
            holdingBall(thrower)
        }

        match.play(match.legalActions<LegalAction.Throw>().first())
        dice.queueD6(6)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())

        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).contains(hail)
        assertThat(match.pieces().players.any { it.holdsBall }).isTrue()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
    }

    @Test
    fun `Underdog is tagged on unassisted Block and offered before dice`() {
        val underdog = card(BonusPlay.Underdog)
        val match = match {
            homeBonus(BonusPlay.Underdog)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        val block = match.legalActions<LegalAction.Block>().first()
        assertThat(block.requiredBonusCard).isNull()
        assertThat(block.availableBonusCards).anyMatch { it.bonusPlay == BonusPlay.Underdog }
        match.play(block)

        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.Underdog)

        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.SMASH, BlockDieFace.SMASH)
        dice.queueD6(6)
        match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(underdog)
    }

    @Test
    fun `Ice Cold tags Throws while Marked`() {
        val nerves = card(BonusPlay.`Ice Cold`)
        lateinit var thrower: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Ice Cold`)
            actionsRemaining(2)
            thrower = addPlayer(5, 8, profile = TestProfiles.withMove(4))
            addPlayer(6, 8, team = TeamSide.AWAY)
            addPlayer(5, 6)
            holdingBall(thrower)
        }

        val throws = match.legalActions<LegalAction.Throw>()
        assertThat(throws).isNotEmpty
        assertThat(throws).allMatch { it.requiredBonusCard?.bonusPlay == BonusPlay.`Ice Cold` }

        dice.queueD6(6)
        match.play(throws.first { it.target.range != ThrowRange.HAND_OFF })
        match.legalActions<LegalAction.DeclineBonus>().firstOrNull()?.let { match.play(it) }
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(nerves)
    }

    @Test
    fun `RandomD6 Sprint offers PlaySprint before Move roll and bakes plus two into budget`() {
        val sprint = card(BonusPlay.Sprint)
        lateinit var runner: PlacedPlayer
        val base = TestProfiles.lineman()
        val match = match {
            homeBonus(BonusPlay.Sprint)
            actionsRemaining(2)
            runner = addPlayer(
                4,
                6,
                profile = base.copy(stats = base.stats.copy(move = MoveValue.RandomD6)),
            )
        }

        val declares = match.legalActions<LegalAction.DeclareRun>()
        assertThat(declares).hasSize(1)
        assertThat(declares.single().requiredBonusCard).isNull()
        assertThat(declares.single().playerId).isEqualTo(runner.player.id)

        match.play(declares.single())
        assertThat(match.legalActions<LegalAction.PlaySprint>()).hasSize(1)
        assertThat(match.legalActions<LegalAction.DeclineBonus>()).hasSize(1)

        dice.queueD6(2)
        match.play(match.legalActions<LegalAction.PlaySprint>().single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(sprint)
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).contains(BonusPlay.Sprint)

        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty
        assertThat(runs).allMatch { it.requiredBonusCard == null && it.path.steps <= 4 }
        assertThat(runs.any { it.path.steps in 3..4 }).isTrue()

        val pick = runs.first { it.path.steps == 4 }
        match.play(pick)
        assertPiece(
            match.pieces(),
            runner.player.id,
            x = pick.path.squares.last().x,
            y = pick.path.squares.last().y,
        )
    }

    @Test
    fun `RandomD6 Sprint decline rolls Move without plus two`() {
        lateinit var runner: PlacedPlayer
        val base = TestProfiles.lineman()
        val match = match {
            homeBonus(BonusPlay.Sprint)
            actionsRemaining(2)
            runner = addPlayer(
                4,
                6,
                profile = base.copy(stats = base.stats.copy(move = MoveValue.RandomD6)),
            )
        }
        match.play(match.legalActions<LegalAction.DeclareRun>().single())
        dice.queueD6(2)
        match.play(match.legalActions<LegalAction.DeclineBonus>().single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).isNotEmpty
        assertThat(loadMatch(match).currentTurnState.bonusPlaysUsedThisTurn).doesNotContain(BonusPlay.Sprint)
        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty
        assertThat(runs).allMatch { it.path.steps <= 2 }
        assertThat(runs.none { it.path.steps > 2 }).isTrue()
        assertThat(runner.player.id).isEqualTo(runs.first().playerId)
    }

    @Test
    fun `RandomD6 without Sprint rolls Move immediately`() {
        val base = TestProfiles.lineman()
        val match = match {
            actionsRemaining(2)
            addPlayer(
                4,
                6,
                profile = base.copy(stats = base.stats.copy(move = MoveValue.RandomD6)),
            )
        }
        dice.queueD6(3)
        match.play(match.legalActions<LegalAction.DeclareRun>().single())
        assertThat(match.legalActions<LegalAction.PlaySprint>()).isEmpty()
        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty
        assertThat(runs).allMatch { it.path.steps <= 3 }
    }

    @Test
    fun `Reserves tags placements then Play makes the action free`() {
        val reserves = card(BonusPlay.`Fresh Legs`)
        lateinit var reserve: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Fresh Legs`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            reserve = addReservesPlayer()
        }
        assertThat(match.legalActions<LegalAction.Run>()).isNotEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        val placement = match.legalActions<LegalAction.Reserves>()
            .first { it.playerId == reserve.player.id }
        assertThat(placement.availableBonusCards).anyMatch { it.bonusPlay == BonusPlay.`Fresh Legs` }

        match.play(placement)
        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`Fresh Legs`)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)

        match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(reserves)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        assertPiece(match.pieces(), reserve.player.id, status = PlayerStatus.STANDING, x = placement.placement.x, y = placement.placement.y)
    }

    @Test
    fun `Back on Your Feet tags Stand Up then Play resolves free`() {
        val jump = card(BonusPlay.`Back on Your Feet`)
        lateinit var prone: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Back on Your Feet`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            prone = addPlayer(5, 6, status = PlayerStatus.PRONE)
        }
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        val standUp = match.legalActions<LegalAction.StandUp>()
            .single { it.playerId == prone.player.id }
        assertThat(standUp.availableBonusCards).anyMatch { it.bonusPlay == BonusPlay.`Back on Your Feet` }

        match.play(standUp)
        val plays = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(plays.single().bonusPlay).isEqualTo(BonusPlay.`Back on Your Feet`)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)

        match.play(plays.single())
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).doesNotContain(jump)
        assertThat(match.gameState().actionsRemaining).isEqualTo(3)
        assertPiece(match.pieces(), prone.player.id, status = PlayerStatus.STANDING, x = 5, y = 6)
    }

    @Test
    fun `Skip Back on Your Feet window resolves Stand Up as budgeted`() {
        lateinit var prone: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`Back on Your Feet`)
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
            prone = addPlayer(5, 6, status = PlayerStatus.PRONE)
        }
        val standUp = match.legalActions<LegalAction.StandUp>()
            .single { it.playerId == prone.player.id }
        match.play(standUp)
        match.play(match.legalActions<LegalAction.DeclineBonus>().first())
        assertThat(match.gameState().actionsRemaining).isEqualTo(2)
        assertPiece(match.pieces(), prone.player.id, status = PlayerStatus.STANDING, x = 5, y = 6)
        assertThat(loadMatch(match).cards.handCards(TeamSide.HOME)).isNotEmpty()
    }
}
