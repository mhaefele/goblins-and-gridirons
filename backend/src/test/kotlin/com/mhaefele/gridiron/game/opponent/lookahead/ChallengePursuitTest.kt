package com.mhaefele.gridiron.game.opponent

import com.mhaefele.gridiron.api.dto.response.ClaimCertainty
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.PotentialChallengeClaim
import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.api.dto.response.playBonusLegalAction
import com.mhaefele.gridiron.game.opponent.lookahead.ChallengePursuit
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeCardId
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** D1.H — shallow challenge pursuit for the scripted bot. */
class ChallengePursuitTest {

    private val pid = PlayerId("p1")
    private val at = Coordinate(5, 6)

    private fun playBonus(bonus: BonusPlay, cardId: ChallengeCardId = ChallengeCardId("c-${bonus.name}")) =
        playBonusLegalAction(bonus)

    private fun runTo(x: Int, y: Int) =
        LegalAction.Run(pid, Path(listOf(at, Coordinate(x, y))))

    private fun throwTo(x: Int, y: Int) =
        LegalAction.Throw(pid, ThrowTarget(Coordinate(x, y), ThrowRange.SHORT, obstructed = false, throwCheckNeeded = 3))

    private fun blockAt(x: Int, y: Int) =
        LegalAction.Block(pid, Coordinate(x, y))

    private fun cardsWithActives(vararg deckIndexes: Int): CardState {
        val actives = deckIndexes.map { ChallengeCardCatalog.card(it) }
        val used = actives.map { it.id }.toSet()
        return CardState(
            activeSlots = actives + List(3 - actives.size) { null },
            deck = ChallengeCardCatalog.allCards().filter { it.id !in used },
        )
    }

    private fun markTo(x: Int, y: Int, vararg claims: PotentialChallengeClaim) =
        LegalAction.Mark(pid, Path(listOf(at, Coordinate(x, y))), potentialChallengeClaims = claims.toList())

    private fun claimHint(kind: ChallengeClaimCondition, points: Int = kind.points) =
        PotentialChallengeClaim(kind, kind.name, points, ClaimCertainty.CERTAIN)

    @Test
    fun `biasedLegal prefers Runs when KEEP_RUNNING active and runs available`() {
        val board = GameScenario.board().also { it.addPlayer(5, 6) }.board
        val state = matchState(
            board = board,
            cards = cardsWithActives(13), // KEEP_RUNNING / Sprint
        )
        val legal = listOf(runTo(6, 6), blockAt(6, 6), throwTo(7, 6))

        val biased = ChallengePursuit.biasedLegal(state, legal)

        assertThat(biased).isNotNull
        assertThat(biased!!).allMatch { it is LegalAction.Run || it is LegalAction.DeclareRun }
        assertThat(biased).hasSize(1)
    }

    @Test
    fun `biasedLegal prefers Fouls when SEND_THEM_OFF active and foul available`() {
        val board = GameScenario.board().also { it.addPlayer(5, 6) }.board
        val state = matchState(
            board = board,
            cards = cardsWithActives(25), // SEND_THEM_OFF / Slip Away
        )
        val foul = LegalAction.Foul(pid, Coordinate(6, 6))
        val legal = listOf(runTo(6, 6), blockAt(6, 6), foul)

        val biased = ChallengePursuit.biasedLegal(state, legal)

        assertThat(biased).isNotNull
        assertThat(biased!!).containsExactly(foul)
    }

    @Test
    fun `biasedLegal prefers Throws when CONNECT active`() {
        val board = GameScenario.board().also { it.addPlayer(5, 6) }.board
        val state = matchState(
            board = board,
            cards = cardsWithActives(9), // CONNECT / Extra Effort
        )
        val legal = listOf(runTo(6, 6), throwTo(7, 6), blockAt(6, 6))

        val biased = ChallengePursuit.biasedLegal(state, legal)

        assertThat(biased).isNotNull
        assertThat(biased!!).allMatch { it is LegalAction.Throw }
        assertThat(biased).hasSize(1)
    }

    @Test
    fun `biasedLegal prefers annotated Mark when LOCK_THEM_DOWN active`() {
        val board = GameScenario.board().also { it.addPlayer(5, 6) }.board
        val state = matchState(
            board = board,
            cards = cardsWithActives(23), // LOCK_THEM_DOWN
        )
        val distractor = markTo(6, 6)
        val focus = markTo(7, 6, claimHint(ChallengeClaimCondition.LOCK_THEM_DOWN))
        val legal = listOf(runTo(6, 6), distractor, focus)

        val biased = ChallengePursuit.biasedLegal(state, legal)

        assertThat(biased).containsExactly(focus)
    }

    @Test
    fun `biasedLegal for PUSH_FORWARD ignores annotations for other claims`() {
        val board = GameScenario.board().also { it.addPlayer(5, 6) }.board
        val state = matchState(
            board = board,
            cards = cardsWithActives(4), // PUSH_FORWARD
        )
        val otherClaim = markTo(6, 6, claimHint(ChallengeClaimCondition.LOCK_THEM_DOWN))
        val moveBall = runTo(7, 6).copy(potentialChallengeClaims = listOf(claimHint(ChallengeClaimCondition.PUSH_FORWARD)))
        val legal = listOf(otherClaim, moveBall, blockAt(6, 6))

        val biased = ChallengePursuit.biasedLegal(state, legal)

        assertThat(biased).containsExactly(moveBall)
    }

    @Test
    fun `biasedLegal prefers Blocks when STRIP_IT active not pickups`() {
        val board = GameScenario.board().also { it.addPlayer(5, 6) }.board
        val state = matchState(
            board = board,
            cards = cardsWithActives(11), // STRIP_IT
        )
        val pickup = LegalAction.Run(
            pid,
            Path(listOf(at, Coordinate(6, 6))),
            ballEncounter = com.mhaefele.gridiron.api.dto.response.BallEncounter.PICK_UP,
        )
        val strip = blockAt(6, 6).copy(potentialChallengeClaims = listOf(claimHint(ChallengeClaimCondition.STRIP_IT)))
        val legal = listOf(pickup, strip, runTo(7, 6))

        val biased = ChallengePursuit.biasedLegal(state, legal)

        assertThat(biased).containsExactly(strip)
    }

    @Test
    fun `pickPlayBonus prefers GANG_UP over interrupt-only`() {
        val legal = listOf(
            playBonus(BonusPlay.`Slip Away`),
            playBonus(BonusPlay.`Stick Close`),
            playBonus(BonusPlay.`Gang Up`),
            playBonus(BonusPlay.`Cut Off`),
        )

        val picked = ChallengePursuit.pickPlayBonus(legal)

        assertThat(picked).isNotNull
        assertThat(picked!!.bonusPlay).isEqualTo(BonusPlay.`Gang Up`)
    }

    @Test
    fun `pickPlayBonus returns null when only interrupt bonuses`() {
        val legal = listOf(
            playBonus(BonusPlay.`Slip Away`),
            playBonus(BonusPlay.`Stick Close`),
            playBonus(BonusPlay.`Cut Off`),
            playBonus(BonusPlay.Brace),
        )

        assertThat(ChallengePursuit.pickPlayBonus(legal)).isNull()
    }

    @Test
    fun `pickClaimChallenge prefers highest points then lowest slot`() {
        val low = LegalAction.ClaimChallenge(0, ChallengeClaimCondition.GRAB_IT, "Grab It!", 1)
        val highLater = LegalAction.ClaimChallenge(2, ChallengeClaimCondition.SEND_THEM_OFF, "Send Them Off!", 3)
        val highEarly = LegalAction.ClaimChallenge(1, ChallengeClaimCondition.SEND_THEM_OFF, "Send Them Off!", 3)

        assertThat(ChallengePursuit.pickClaimChallenge(listOf(low, highLater, highEarly)))
            .isEqualTo(highEarly)
    }

    @Test
    fun `pickReplaceChallenge prefers lowest points then lowest slot`() {
        val highEarly = LegalAction.ReplaceChallenge(0, ChallengeClaimCondition.SEND_THEM_OFF, "Send Them Off!")
        val lowLater = LegalAction.ReplaceChallenge(2, ChallengeClaimCondition.GRAB_IT, "Grab It!")
        val lowEarly = LegalAction.ReplaceChallenge(1, ChallengeClaimCondition.HUDDLE_UP, "Huddle Up!")

        assertThat(ChallengePursuit.pickReplaceChallenge(listOf(highEarly, lowLater, lowEarly)))
            .isEqualTo(lowEarly)
    }
}
