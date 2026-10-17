package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.model.pending.ClaimChallenge
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withTurnNumber
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChallengeTurnStepsTest {
    private fun tableau(vararg deckIndexes: Int, homeHand: List<Int> = emptyList()): CardState {
        val actives = deckIndexes.map { ChallengeCardCatalog.card(it) }
        val hand = homeHand.map { ChallengeCardCatalog.card(it) }
        val used = (actives + hand).map { it.id }.toSet()
        return CardState(
            activeSlots = actives,
            deck = ChallengeCardCatalog.allCards().filter { it.id !in used },
            homeHand = hand,
        )
    }

    @Test
    fun `refresh fills empty slots then offers ReplaceChallenge when tableau was already full`() {
        val ids = ChallengeCardCatalog.allCards()
        val cards = CardState(
            activeSlots = listOf(ids[0], ids[1], ids[2]),
            deck = ids.drop(3),
        )
        val session = MatchMutator(matchState(cards = cards, actionsRemaining = 0, phase = TurnPhase.PRE_TURN).withTurnNumber(3))
        val offered = ChallengeTurnSteps.refreshAndMaybeOfferReplace(session)

        assertThat(offered).isTrue()
        assertThat(session.events.filterIsInstance<DomainEvent.ChallengeTableauRefreshed>()).isEmpty()
        assertThat(session.events.filterIsInstance<DomainEvent.ChallengeCardReplaced>()).isEmpty()
        session.setPendingDecision(ReplaceChallenge())
        assertThat(session.pendingAs<ReplaceChallenge>()).isNotNull()
        assertThat(session.cardState.activeSlots).containsExactly(ids[0], ids[1], ids[2])
    }

    @Test
    fun `claim auto-picks first eligible Drop Them and awards points`() {
        val takeDown = ChallengeCardCatalog.card(21)
        val cards = tableau(1, 21, 7)
        val board = GameScenario.board()
        val blocker = board.addPlayer(5, 8, profile = TestProfiles.lineman())
        val target = board.addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        val session = MatchMutator(matchState(board = board.board, cards = cards).withTurnNumber(3))
        session.snapshotBallsPossessedAtTurnStart(TeamSide.HOME)
        session.beginPlayerAction(blocker.player.id, ActionType.BLOCK, OpenPlayerAction.Origin.BUDGETED)
        session.board.playerKnockedDown(target.player.id)
        val completed = session.resolveCurrentPlayerAction()
        session.recordTurnAction(completed.playerId, completed.actionType, isFree = false)
        val pending = ChallengeTurnSteps.maybeClaimAfterAction(session, completed)

        assertThat(pending).isNull()
        assertThat(session.cardState.activeSlots[1]).isNull()
        assertThat(session.cardState.handCards(TeamSide.HOME)).containsExactly(takeDown)
        assertThat(session.scoreFor(TeamSide.HOME).points).isEqualTo(2)
        assertThat(session.events.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).hasSize(1)
    }

    @Test
    fun `claim offers coach choice when two Drop Them are eligible`() {
        val cards = tableau(21, 22, 7)
        val board = GameScenario.board()
        val blocker = board.addPlayer(5, 8, profile = TestProfiles.lineman())
        val target = board.addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        val session = MatchMutator(matchState(cards = cards, board = board.board).withTurnNumber(3))
        session.snapshotBallsPossessedAtTurnStart(TeamSide.HOME)
        session.clearPendingDecision() // takeAction would consume the budgeted RPA before claim
        session.beginPlayerAction(blocker.player.id, ActionType.BLOCK, OpenPlayerAction.Origin.BUDGETED)
        session.board.playerKnockedDown(target.player.id)
        val completed = session.resolveCurrentPlayerAction()
        session.recordTurnAction(completed.playerId, completed.actionType, isFree = false)
        val pending = ChallengeTurnSteps.maybeClaimAfterAction(session, completed)

        assertThat(pending).isNotNull
        assertThat(pending!!.eligibleSlotIndexes).containsExactly(0, 1)
        session.setPendingDecision(pending)
        assertThat(session.pendingAs<ClaimChallenge>().eligibleSlotIndexes).containsExactly(0, 1)
        assertThat(session.cardState.handCards(TeamSide.HOME)).isEmpty()
        assertThat(session.events.filterIsInstance<DomainEvent.ChallengeCardClaimed>()).isEmpty()
    }

    @Test
    fun `Coach A first turn does not claim`() {
        val cards = tableau(21, 1, 7)
        val board = GameScenario.board()
        val blocker = board.addPlayer(5, 8, profile = TestProfiles.lineman())
        val target = board.addPlayer(6, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        val session = MatchMutator(matchState(board = board.board, cards = cards))
        session.beginPlayerAction(blocker.player.id, ActionType.BLOCK, OpenPlayerAction.Origin.BUDGETED)
        session.board.playerKnockedDown(target.player.id)
        val completed = session.resolveCurrentPlayerAction()
        session.recordTurnAction(completed.playerId, completed.actionType)
        ChallengeTurnSteps.maybeClaimAfterAction(session, completed)

        assertThat(session.cardState.handCards(TeamSide.HOME)).isEmpty()
        assertThat(session.scoreFor(TeamSide.HOME).points).isZero()
    }

    @Test
    fun `Clean Sweep awards plus two after third claim`() {
        val cards = CardState.dealInCatalogOrder()
        val session = MatchMutator(matchState(cards = cards).withTurnNumber(3))
        session.claimChallengeCard(TeamSide.HOME, 0)
        session.claimChallengeCard(TeamSide.HOME, 1)
        session.claimChallengeCard(TeamSide.HOME, 2)
        assertThat(session.cardState.claimsThisTurn).isEqualTo(3)
        session.awardCleanSweep(TeamSide.HOME)
        assertThat(session.scoreFor(TeamSide.HOME).points).isEqualTo(5)
        assertThat(session.events.filterIsInstance<DomainEvent.CleanSweep>()).hasSize(1)
    }

    @Test
    fun `hand trim discards oldest beyond three`() {
        val ids = ChallengeCardCatalog.allCards()
        val cards = CardState(
            activeSlots = listOf(ids[0], ids[1], ids[2]),
            deck = ids.drop(7),
            homeHand = listOf(ids[3], ids[4], ids[5], ids[6]),
        )
        val session = MatchMutator(matchState(cards = cards).withTurnNumber(3))
        ChallengeTurnSteps.endOfTurnCleanup(session)

        assertThat(session.cardState.handCards(TeamSide.HOME)).containsExactly(ids[4], ids[5], ids[6])
        assertThat(session.cardState.discard).contains(ids[3])
        assertThat(session.cardState.claimsThisTurn).isZero()
        val trimmed = session.events.filterIsInstance<DomainEvent.ChallengeHandTrimmed>().single()
        assertThat(trimmed.discardedBonusPlays).containsExactly(ids[3].bonusPlay)
        assertThat(trimmed.handSize).isEqualTo(3)
    }

    @Test
    fun `hand trim with five cards discards two oldest and keeps three`() {
        val ids = ChallengeCardCatalog.allCards()
        val cards = CardState(
            activeSlots = listOf(ids[0], ids[1], ids[2]),
            deck = ids.drop(8),
            homeHand = listOf(ids[3], ids[4], ids[5], ids[6], ids[7]),
        )
        val session = MatchMutator(matchState(cards = cards).withTurnNumber(3))
        ChallengeTurnSteps.endOfTurnCleanup(session)

        assertThat(session.cardState.handCards(TeamSide.HOME)).containsExactly(ids[5], ids[6], ids[7])
        assertThat(session.cardState.discard).containsExactly(ids[3], ids[4])
        val trimmed = session.events.filterIsInstance<DomainEvent.ChallengeHandTrimmed>().single()
        assertThat(trimmed.discardedBonusPlays).containsExactly(
            ids[3].bonusPlay,
            ids[4].bonusPlay,
        )
        assertThat(trimmed.handSize).isEqualTo(3)
    }

    @Test
    fun `hand of exactly three is not trimmed`() {
        val ids = ChallengeCardCatalog.allCards()
        val hand = listOf(ids[3], ids[4], ids[5])
        val cards = CardState(
            activeSlots = listOf(ids[0], ids[1], ids[2]),
            deck = ids.drop(6),
            homeHand = hand,
        )
        val session = MatchMutator(matchState(cards = cards).withTurnNumber(3))
        ChallengeTurnSteps.endOfTurnCleanup(session)

        assertThat(session.cardState.handCards(TeamSide.HOME)).containsExactlyElementsOf(hand)
        assertThat(session.cardState.discard).isEmpty()
        assertThat(session.events.filterIsInstance<DomainEvent.ChallengeHandTrimmed>()).isEmpty()
    }

    @Test
    fun `hand under three is not trimmed`() {
        val ids = ChallengeCardCatalog.allCards()
        val hand = listOf(ids[3], ids[4])
        val cards = CardState(
            activeSlots = listOf(ids[0], ids[1], ids[2]),
            deck = ids.drop(5),
            homeHand = hand,
        )
        val session = MatchMutator(matchState(cards = cards).withTurnNumber(3))
        ChallengeTurnSteps.endOfTurnCleanup(session)

        assertThat(session.cardState.handCards(TeamSide.HOME)).containsExactlyElementsOf(hand)
        assertThat(session.events.filterIsInstance<DomainEvent.ChallengeHandTrimmed>()).isEmpty()
    }

    @Test
    fun `end of turn hand trim only affects the active coach`() {
        val ids = ChallengeCardCatalog.allCards()
        val cards = CardState(
            activeSlots = listOf(ids[0], ids[1], ids[2]),
            deck = ids.drop(10),
            homeHand = listOf(ids[3], ids[4], ids[5], ids[6]),
            awayHand = listOf(ids[7], ids[8], ids[9]),
        )
        val session = MatchMutator(matchState(cards = cards).withTurnNumber(3))
        ChallengeTurnSteps.endOfTurnCleanup(session)

        assertThat(session.cardState.handCards(TeamSide.HOME)).containsExactly(ids[4], ids[5], ids[6])
        assertThat(session.cardState.handCards(TeamSide.AWAY)).containsExactly(ids[7], ids[8], ids[9])
        assertThat(session.events.filterIsInstance<DomainEvent.ChallengeHandTrimmed>().single().team)
            .isEqualTo(TeamSide.HOME)
    }

    @Test
    fun `refresh that deals last card arms Points Victory`() {
        val a = ChallengeCardCatalog.card(1)
        val b = ChallengeCardCatalog.card(2)
        val last = ChallengeCardCatalog.card(3)
        val cards = CardState(
            activeSlots = listOf(a, b, null),
            deck = listOf(last),
        )
        val session = MatchMutator(matchState(cards = cards).withTurnNumber(3))
        val offered = ChallengeTurnSteps.refreshAndMaybeOfferReplace(session)

        assertThat(offered).isFalse()
        assertThat(session.pointsVictoryTurnsRemaining).isEqualTo(2)
        assertThat(session.events.filterIsInstance<DomainEvent.PointsVictoryArmed>()).hasSize(1)
        assertThat(session.cardState.deck).isEmpty()
        assertThat(session.cardState.activeSlots[2]).isEqualTo(last)
    }

    @Test
    fun `replace that deals last card arms Points Victory`() {
        val ids = ChallengeCardCatalog.allCards().take(4)
        val cards = CardState(
            activeSlots = listOf(ids[0], ids[1], ids[2]),
            deck = listOf(ids[3]),
        )
        val session = MatchMutator(matchState(cards = cards, actionsRemaining = 0, phase = TurnPhase.PRE_TURN).withTurnNumber(3))
        val offered = ChallengeTurnSteps.refreshAndMaybeOfferReplace(session)
        assertThat(offered).isTrue()
        session.setPendingDecision(ReplaceChallenge())
        session.clearPendingDecision()
        session.replaceChallengeCard(0)

        assertThat(session.pointsVictoryTurnsRemaining).isEqualTo(2)
        assertThat(session.events.filterIsInstance<DomainEvent.PointsVictoryArmed>()).hasSize(1)
        assertThat(session.cardState.deck).isEmpty()
    }
}
