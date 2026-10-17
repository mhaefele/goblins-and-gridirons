package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.MatchActionResponse
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.SEND_THEM_OFF
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.STRIP_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.KEEP_RUNNING
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.HUDDLE_UP
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.THREAD_THE_NEEDLE
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PUSH_FORWARD
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.ALL_HANDS
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PLAY_TO_THE_STANDS
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.HOLD_THE_PIT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.STAR_TURN
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.CONNECT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.FAN_OUT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.LOCK_THEM_DOWN
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.MatchBuilder
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val awayEndZone = Coordinate(5, 15)
private val trapdoor = Coordinate(6, 8)

/**
 * End-to-end claim coverage for every [ChallengeClaimCondition] condition — happy path plus near-misses
 * where the action almost qualifies but must not claim.
 *
 * Turn / refresh / multi-slot choice mechanics stay in [ChallengeCardTurnApiTest].
 */
class ChallengeClaimsApiTest : BaseApiTest() {

    // --- Grab It! -----------------------------------------------------------------

    @Test
    fun `Grab It claims when a Run picks up a ball the team did not hold at turn start`() {
        val match = match {
            challengeCards(GRAB_IT, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            // MA 1 so the pickup ends the action (no RUN_CONTINUE).
            addPlayer(5, 6, profile = TestProfiles.withMove(1))
            addLooseBall(5, 7)
        }

        val response = match.play<LegalAction.Run> { it.path.squares.last() == Coordinate(5, 7) }

        assertClaimed(response, GRAB_IT)
        assertThat(match.gameState().homeScore).isEqualTo(1)
    }

    @Test
    fun `Grab It does not claim when the picked-up ball was already possessed at turn start`() {
        val match = match {
            addPlayer(5, 6, profile = TestProfiles.withMove(1))
            val ball = addLooseBall(5, 7)
            cardsWithActives(
                listOf(GRAB_IT, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE),
                ballsPossessedAtTurnStart = setOf(ball.id),
            )
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == Coordinate(5, 7) },
        )

        assertNoClaim(response)
        assertThat(match.gameState().challenges.activeSlots.map { it?.title })
            .contains(GRAB_IT.displayName)
    }

    // --- Huddle Up! -----------------------------------------------------------------

    @Test
    fun `Huddle Up claims when a Mark ends adjacent to two teammates`() {
        val match = match {
            challengeCards(HUDDLE_UP, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 7, profile = TestProfiles.withMove(2))
            addPlayer(4, 8)
            addPlayer(6, 8)
            addPlayer(5, 9, team = TeamSide.AWAY) // give the Mark a legal target square
        }

        val response = match.play<LegalAction.Mark> { it.path.squares.last() == Coordinate(5, 8) }

        assertClaimed(response, HUDDLE_UP)
        assertThat(match.gameState().homeScore).isEqualTo(1)
    }

    @Test
    fun `Huddle Up does not claim when the actor ends adjacent to only one teammate`() {
        val match = match {
            challengeCards(HUDDLE_UP, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 7, profile = TestProfiles.withMove(2))
            addPlayer(4, 8)
            addPlayer(5, 9, team = TeamSide.AWAY)
        }

        val response = match.play(
            match.legalActions<LegalAction.Mark>().first { it.path.squares.last() == Coordinate(5, 8) },
        )

        assertNoClaim(response)
    }

    // --- Push Forward! ----------------------------------------------------------------

    @Test
    fun `Push Forward claims when a carrier Runs four or more squares further from own End Zone`() {
        val match = match {
            challengeCards(PUSH_FORWARD, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            holdingBall(addPlayer(5, 6, profile = TestProfiles.withMove(4)))
        }

        val response = match.play<LegalAction.Run> { it.path.squares.last() == Coordinate(5, 10) }

        assertClaimed(response, PUSH_FORWARD)
        assertThat(match.gameState().homeScore).isEqualTo(1)
    }

    @Test
    fun `Push Forward does not claim when the ball moves only three squares further from own End Zone`() {
        val match = match {
            challengeCards(PUSH_FORWARD, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            holdingBall(addPlayer(5, 6, profile = TestProfiles.withMove(3)))
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == Coordinate(5, 9) },
        )

        assertNoClaim(response)
    }

    // --- Fan Out! -------------------------------------------------------------------

    @Test
    fun `Fan Out claims when a Run leaves no teammates adjacent`() {
        val match = match {
            challengeCards(FAN_OUT, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 6, profile = TestProfiles.withMove(4))
            addPlayer(5, 7) // starts adjacent; runner leaves them alone
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == Coordinate(5, 10) },
        )

        assertClaimed(response, FAN_OUT)
        assertThat(match.gameState().homeScore).isEqualTo(1)
    }

    @Test
    fun `Fan Out does not claim when another teammate pair remains adjacent`() {
        val match = match {
            challengeCards(FAN_OUT, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 6, profile = TestProfiles.withMove(4))
            addPlayer(5, 7)
            addPlayer(6, 7) // stay adjacent to the non-runner after the Run
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == Coordinate(5, 10) },
        )

        assertNoClaim(response)
    }

    // --- Play to the Stands! -------------------------------------------------------

    @Test
    fun `Play to the Stands claims when a Run scores a Touchdown`() {
        val match = match {
            challengeCards(PLAY_TO_THE_STANDS, GRAB_IT, THREAD_THE_NEEDLE)
            actionsRemaining(2)
            holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == awayEndZone },
        )

        assertClaimed(response, PLAY_TO_THE_STANDS)
        // TD (+4) + Play to the Stands (+1)
        assertThat(match.gameState().homeScore).isEqualTo(5)
        assertThat(match.gameState().challenges.activeSlots[0]).isNull()
    }

    @Test
    fun `Play to the Stands claims on a Touchdown that spends the last team action`() {
        val match = match {
            challengeCards(PLAY_TO_THE_STANDS, GRAB_IT, THREAD_THE_NEEDLE)
            actionsRemaining(1)
            holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
        }

        // Last action → turn ends → Away Pre-Turn New Ball bounce.
        dice.queueD8(1)
        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == awayEndZone },
        )

        assertClaimed(response, PLAY_TO_THE_STANDS)
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.TouchdownScored>()).hasSize(1)
        assertThat(match.gameState().homeScore).isEqualTo(5)
    }

    @Test
    fun `Play to the Stands does not claim when a Run stops short of the End Zone`() {
        val match = match {
            challengeCards(PLAY_TO_THE_STANDS, GRAB_IT, THREAD_THE_NEEDLE)
            holdingBall(addPlayer(5, 13, profile = TestProfiles.withMove(1)))
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == Coordinate(5, 14) },
        )

        assertNoClaim(response)
        assertThat(match.gameState().homeScore).isZero()
    }

    // --- Connect! ---------------------------------------------------------

    @Test
    fun `Connect claims on a successful short Throw`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            challengeCards(CONNECT, GRAB_IT, DROP_THEM)
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(5, 4, team = thrower.team)
        }

        dice.queueD6(6)
        val response = match.play(
            match.legalActions<LegalAction.Throw>().single { it.target.destination == Coordinate(5, 4) },
        )

        assertClaimed(response, CONNECT)
        assertThat(match.gameState().homeScore).isEqualTo(1)
    }

    @Test
    fun `Connect does not claim a Hand-off`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            challengeCards(CONNECT, GRAB_IT, DROP_THEM)
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(6, 6, team = thrower.team)
        }

        val response = match.play(match.legalActions<LegalAction.Throw>().single())

        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.BallThrown>()).hasSize(1)
        assertNoClaim(response)
    }

    // --- Strip It! -------------------------------------------------------------

    @Test
    fun `Strip It claims when smash bounce is picked up by an open teammate`() {
        lateinit var openTeammate: PlacedPlayer
        val match = match {
            challengeCards(STRIP_IT, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            holdingBall(addPlayer(6, 6, team = TeamSide.AWAY))
            openTeammate = addPlayer(6, 5)
        }

        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.SMASH)
        dice.queueD6(6)
        dice.queueD8(1)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(player(match.pieces(), openTeammate.player.id).holdsBall).isTrue()
        assertClaimed(response, STRIP_IT)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `Strip It does not claim when smash bounce is hung onto by open opposition`() {
        lateinit var oppositionRetainer: PlacedPlayer
        val match = match {
            challengeCards(STRIP_IT, PLAY_TO_THE_STANDS, THREAD_THE_NEEDLE)
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            holdingBall(addPlayer(6, 6, team = TeamSide.AWAY))
            oppositionRetainer = addPlayer(7, 6, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        dice.queueD8(3)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertThat(player(match.pieces(), oppositionRetainer.player.id).holdsBall).isTrue()
        assertNoClaim(response)
        assertThat(match.gameState().challenges.activeSlots.map { it?.title })
            .contains(STRIP_IT.displayName)
    }

    // --- Keep Running! -------------------------------------------------------------------

    @Test
    fun `Keep Running claims on the third Run of the turn`() {
        val match = match {
            challengeCards(KEEP_RUNNING, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            actionsUsed(
                mapOf(
                    PlayerId("prior-1") to setOf(ActionType.RUN),
                    PlayerId("prior-2") to setOf(ActionType.RUN),
                ),
            )
            addPlayer(4, 6, profile = TestProfiles.withMove(2))
            addLooseBall(8, 8) // keep a ball in play so turn-end skips New Ball
        }

        val response = match.play(match.legalActions<LegalAction.Run>().first())

        assertClaimed(response, KEEP_RUNNING)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `Keep Running does not claim on the second Run of the turn`() {
        val match = match {
            challengeCards(KEEP_RUNNING, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            actionsUsed(mapOf(PlayerId("prior-1") to setOf(ActionType.RUN)))
            addPlayer(4, 6, profile = TestProfiles.withMove(2))
            addLooseBall(8, 8)
        }

        val response = match.play(match.legalActions<LegalAction.Run>().first())

        assertNoClaim(response)
    }

    @Test
    fun `Keep Running claims on third Run after a Touchdown on the second Run`() {
        lateinit var thirdRunner: PlacedPlayer
        val match = match {
            challengeCards(KEEP_RUNNING, PLAY_TO_THE_STANDS, GRAB_IT)
            actionsRemaining(2)
            actionsUsed(mapOf(PlayerId("prior-1") to setOf(ActionType.RUN)))
            holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
            thirdRunner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            addLooseBall(8, 8)
        }

        val tdResponse = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == awayEndZone },
        )
        assertClaimed(tdResponse, PLAY_TO_THE_STANDS)
        assertThat(match.gameState().homeScore).isEqualTo(5) // TD (+4) + Play to the Stands (+1)

        val thirdResponse = match.play(
            match.legalActions<LegalAction.Run>().first { it.playerId == thirdRunner.player.id },
        )
        assertClaimed(thirdResponse, KEEP_RUNNING)
        assertThat(match.gameState().homeScore).isEqualTo(7) // + Keep Running (+2)
    }

    @Test
    fun `Keep Running claims on third Run after a Touchdown on the first Run`() {
        lateinit var secondRunner: PlacedPlayer
        lateinit var thirdRunner: PlacedPlayer
        val match = match {
            challengeCards(KEEP_RUNNING, PLAY_TO_THE_STANDS, GRAB_IT)
            actionsRemaining(3)
            holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
            secondRunner = addPlayer(3, 6, profile = TestProfiles.withMove(2))
            thirdRunner = addPlayer(4, 6, profile = TestProfiles.withMove(2))
            addLooseBall(8, 8)
        }

        val tdResponse = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == awayEndZone },
        )
        assertClaimed(tdResponse, PLAY_TO_THE_STANDS)
        assertThat(match.gameState().homeScore).isEqualTo(5)

        val secondResponse = match.play(
            match.legalActions<LegalAction.Run>().first { it.playerId == secondRunner.player.id },
        )
        assertNoClaim(secondResponse)

        val thirdResponse = match.play(
            match.legalActions<LegalAction.Run>().first { it.playerId == thirdRunner.player.id },
        )
        assertClaimed(thirdResponse, KEEP_RUNNING)
        assertThat(match.gameState().homeScore).isEqualTo(7)
    }

    @Test
    fun `third Run that scores a Touchdown offers Keep Running and Play to the Stands`() {
        val match = match {
            challengeCards(KEEP_RUNNING, PLAY_TO_THE_STANDS, GRAB_IT)
            actionsRemaining(1)
            actionsUsed(
                mapOf(
                    PlayerId("prior-1") to setOf(ActionType.RUN),
                    PlayerId("prior-2") to setOf(ActionType.RUN),
                ),
            )
            holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
        }

        // Last budgeted action → after claim, Away Pre-Turn New Ball bounce.
        dice.queueD8(1)
        val tdResponse = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == awayEndZone },
        )
        assertThat(tdResponse.events.map { it.event }.filterIsInstance<DomainEvent.TouchdownScored>()).hasSize(1)
        assertNoClaim(tdResponse)

        val claims = match.legalActions<LegalAction.ClaimChallenge>()
        assertThat(claims.map { it.challengeTitle }).containsExactlyInAnyOrder(
            KEEP_RUNNING.displayName,
            PLAY_TO_THE_STANDS.displayName,
        )

        val claimResponse = match.play(claims.first { it.challengeTitle == KEEP_RUNNING.displayName })
        assertClaimed(claimResponse, KEEP_RUNNING)
        // TD (+4) + Keep Running (+2); Play to the Stands left unclaimed that step
        assertThat(match.gameState().homeScore).isEqualTo(6)
        assertThat(match.gameState().challenges.activeSlots.map { it?.title })
            .contains(PLAY_TO_THE_STANDS.displayName)
    }

    @Test
    fun `Keep Running claims when the third Run scores a Touchdown and Play to the Stands is not active`() {
        val match = match {
            challengeCards(KEEP_RUNNING, GRAB_IT, THREAD_THE_NEEDLE)
            actionsRemaining(1)
            actionsUsed(
                mapOf(
                    PlayerId("prior-1") to setOf(ActionType.RUN),
                    PlayerId("prior-2") to setOf(ActionType.RUN),
                ),
            )
            holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
        }

        dice.queueD8(1)
        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == awayEndZone },
        )

        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.TouchdownScored>()).hasSize(1)
        assertClaimed(response, KEEP_RUNNING)
        assertThat(match.gameState().homeScore).isEqualTo(6) // TD (+4) + Keep Running (+2)
    }

    // --- Hold the Pit! -----------------------------------------------------------------

    @Test
    fun `Hold the Pit claims when an Action ends on a Trapdoor while opposition holds a ball`() {
        val match = match {
            challengeCards(HOLD_THE_PIT, GRAB_IT, THREAD_THE_NEEDLE)
            addPlayer(6, 7, profile = TestProfiles.withMove(2))
            holdingBall(addPlayer(3, 6, team = TeamSide.AWAY))
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == trapdoor },
        )

        assertClaimed(response, HOLD_THE_PIT)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `Hold the Pit does not claim on a Trapdoor when opposition does not hold a ball`() {
        val match = match {
            challengeCards(HOLD_THE_PIT, GRAB_IT, THREAD_THE_NEEDLE)
            addPlayer(6, 7, profile = TestProfiles.withMove(2))
            addLooseBall(3, 6)
        }

        val response = match.play(
            match.legalActions<LegalAction.Run>().first { it.path.squares.last() == trapdoor },
        )

        assertNoClaim(response)
    }

    // --- Thread the Needle! -----------------------------------------------------------

    @Test
    fun `Thread the Needle claims on a successful Long Throw`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            challengeCards(THREAD_THE_NEEDLE, GRAB_IT, DROP_THEM)
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(5, 1, team = thrower.team) // distance 5 → Long (−1)
        }

        dice.queueD6(6) // natural 6 + Long −1 still passes threshold 5
        val response = match.play(
            match.legalActions<LegalAction.Throw>().single { it.target.destination == Coordinate(5, 1) },
        )

        assertThat(
            response.events.map { it.event }.filterIsInstance<DomainEvent.DiceRolled>()
                .any { it.purpose.name == "THROW" && it.modifier == -1 },
        ).isTrue()
        assertClaimed(response, THREAD_THE_NEEDLE)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `Thread the Needle does not claim a successful short Throw with no negative modifier`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            challengeCards(THREAD_THE_NEEDLE, GRAB_IT, DROP_THEM)
            thrower = holdingBall(addPlayer(5, 6))
            addPlayer(5, 4, team = thrower.team)
        }

        dice.queueD6(6)
        val response = match.play(
            match.legalActions<LegalAction.Throw>().single { it.target.destination == Coordinate(5, 4) },
        )

        assertNoClaim(response)
    }

    // --- All Hands! ---------------------------------------------------------------

    @Test
    fun `All Hands claims when three different players make three different Actions`() {
        lateinit var blocker: PlacedPlayer
        val match = match {
            challengeCards(ALL_HANDS, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            actionsUsed(
                mapOf(
                    PlayerId("prior-run") to setOf(ActionType.RUN),
                    PlayerId("prior-mark") to setOf(ActionType.MARK),
                ),
            )
            blocker = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
            addLooseBall(8, 8)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Block>().first { it.playerId == blocker.player.id })

        assertClaimed(response, ALL_HANDS)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `All Hands does not claim with only two different players`() {
        lateinit var blocker: PlacedPlayer
        val match = match {
            challengeCards(ALL_HANDS, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            blocker = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            actionsUsed(
                mapOf(
                    blocker.player.id to setOf(ActionType.RUN),
                    PlayerId("prior-mark") to setOf(ActionType.MARK),
                ),
            )
            addPlayer(6, 8, team = TeamSide.AWAY)
            addLooseBall(8, 8)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Block>().first { it.playerId == blocker.player.id })

        assertNoClaim(response)
    }

    // --- Star Turn! ------------------------------------------------------

    @Test
    fun `Star Turn claims when one player finishes a third distinct budgeted Action`() {
        lateinit var star: PlacedPlayer
        val match = match {
            challengeCards(STAR_TURN, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            star = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            actionsUsed(mapOf(star.player.id to setOf(ActionType.RUN, ActionType.MARK)))
            addPlayer(6, 8, team = TeamSide.AWAY)
            addLooseBall(8, 8)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Block>().first { it.playerId == star.player.id })

        assertClaimed(response, STAR_TURN)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `Star Turn does not claim after only two distinct budgeted Actions`() {
        lateinit var star: PlacedPlayer
        val match = match {
            challengeCards(STAR_TURN, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            star = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            actionsUsed(mapOf(star.player.id to setOf(ActionType.RUN)))
            addPlayer(6, 8, team = TeamSide.AWAY)
            addLooseBall(8, 8)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Block>().first { it.playerId == star.player.id })

        assertNoClaim(response)
    }

    // --- Drop Them! ---------------------------------------------------------------

    @Test
    fun `Drop Them claims after a Block knocks the target Down`() {
        val match = match {
            challengeCards(DROP_THEM, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertClaimed(response, DROP_THEM)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `Drop Them does not claim a Shove that does not knock the target Down`() {
        val match = match {
            challengeCards(DROP_THEM, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SHOVE)
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertNoClaim(response)
        assertThat(match.gameState().challenges.activeSlots.map { it?.title })
            .contains(DROP_THEM.displayName)
    }

    // --- Lock Them Down! ------------------------------------------------------------------

    @Test
    fun `Lock Them Down claims when a Mark uniquely marks two unmarked opposition players`() {
        val match = match {
            challengeCards(LOCK_THEM_DOWN, GRAB_IT, PLAY_TO_THE_STANDS)
            // Start Open; one step onto (5,7) becomes Marked by both targets (Mark stops on becoming Marked).
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            addPlayer(4, 8, team = TeamSide.AWAY)
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        val response = match.play(
            match.legalActions<LegalAction.Mark>().first { it.path.squares.last() == Coordinate(5, 7) },
        )

        assertClaimed(response, LOCK_THEM_DOWN)
        assertThat(match.gameState().homeScore).isEqualTo(2)
    }

    @Test
    fun `Lock Them Down does not claim when the marker uniquely marks only one opposition player`() {
        val match = match {
            challengeCards(LOCK_THEM_DOWN, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        val response = match.play(
            match.legalActions<LegalAction.Mark>().first { it.path.squares.last() == Coordinate(5, 7) },
        )

        assertNoClaim(response)
    }

    @Test
    fun `Lock Them Down does not claim when a second teammate also marks one of the targets`() {
        val match = match {
            challengeCards(LOCK_THEM_DOWN, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 6, profile = TestProfiles.withMove(2))
            addPlayer(3, 8) // co-marks (4,8)
            addPlayer(4, 8, team = TeamSide.AWAY)
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        val response = match.play(
            match.legalActions<LegalAction.Mark>().first { it.path.squares.last() == Coordinate(5, 7) },
        )

        // (4,8) is co-marked; only (6,8) is unique → no claim
        assertNoClaim(response)
    }

    // --- Send Them Off! -------------------------------------------------------------

    @Test
    fun `Send Them Off claims when a Flatten Block injures the target`() {
        val match = match {
            challengeCards(SEND_THEM_OFF, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.FLATTEN)
        dice.queueD6(1) // armour fails
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertClaimed(response, SEND_THEM_OFF)
        assertThat(match.gameState().homeScore).isEqualTo(3)
    }

    @Test
    fun `Send Them Off claims when a Foul gets them`() {
        val match = match {
            challengeCards(SEND_THEM_OFF, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 6)
            addPlayer(5, 5, team = TeamSide.AWAY, status = PlayerStatus.PRONE)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        val response = match.play(match.legalActions<LegalAction.Foul>().single())

        assertClaimed(response, SEND_THEM_OFF)
        assertThat(match.gameState().homeScore).isEqualTo(3)
    }

    @Test
    fun `Send Them Off does not claim a Smash knockdown that fails to injure`() {
        val match = match {
            challengeCards(SEND_THEM_OFF, GRAB_IT, PLAY_TO_THE_STANDS)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        dice.queueBlockDice(BlockDieFace.SMASH)
        dice.queueD6(6) // armour holds
        val response = match.play(match.legalActions<LegalAction.Block>().first())

        assertNoClaim(response)
    }

    // --- helpers -----------------------------------------------------------------------

    private fun assertClaimed(response: MatchActionResponse, title: ChallengeClaimCondition) {
        val claimed = response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>()
        assertThat(claimed).hasSize(1)
        assertThat(claimed.single().challengeTitle).isEqualTo(title.displayName)
        assertThat(claimed.single().pointsAwarded).isEqualTo(title.points)
    }

    private fun assertNoClaim(response: MatchActionResponse) {
        assertThat(response.events.map { it.event }.filterIsInstance<DomainEvent.ChallengeCardClaimed>())
            .isEmpty()
    }

    /** Build [CardState] with fixed actives and optional turn-start possession (Grab It!). */
    private fun MatchBuilder.cardsWithActives(
        titles: List<ChallengeClaimCondition>,
        ballsPossessedAtTurnStart: Set<BallId> = emptySet(),
    ) {
        val actives = ChallengeCardCatalog.cardsForTitles(titles)
        val used = actives.map { it.id }.toSet()
        cards(
            CardState(
                activeSlots = actives,
                deck = ChallengeCardCatalog.allCards().filter { it.id !in used },
                ballsPossessedAtTurnStart = ballsPossessedAtTurnStart,
            ),
        )
    }
}
