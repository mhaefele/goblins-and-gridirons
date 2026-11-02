package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.internal.AiObsEncoding
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.nested.AiCellObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiCellPlayerObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiChallengeSlotObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiHeldCheckObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedObsDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiPlayerAffordance
import org.springframework.stereotype.Component

/**
 * Flattens [AiNestedObsDto] into the learning float vector (length [AiObsLayout.OBSERVATION_VECTOR_LENGTH]).
 *
 * Concat order matches `ai/gridiron_ai/constants.py` / Python `obs_flat_to_dict` field count:
 * scalars → challenges → deck → hand → heldCheck → cells[0..184]
 * (each terrain, hasBall, player block, pitchX, pitchY, distToBall).
 */
@Component
class AiObsPacker {
    fun pack(obs: AiNestedObsDto): FloatArray {
        val out = FloatArray(AiObsLayout.OBSERVATION_VECTOR_LENGTH)
        var i = 0
        fun put(v: Int) {
            out[i++] = v.toFloat()
        }
        put(obs.turnNumber)
        put(AiObsEncoding.run { obs.phase.toObsCode() })
        put(obs.actionsRemaining)
        put(obs.scoreUs)
        put(obs.scoreThem)
        put(obs.touchdownsUs)
        put(obs.touchdownsThem)
        put(obs.challengeCardsUs)
        put(obs.challengeCardsThem)
        put(AiObsEncoding.run { obs.pointsVictoryTurnsRemaining.toObsCode() })
        put(obs.emergencyReservesUs)
        put(obs.emergencyReservesThem)
        put(obs.claimsThisTurn)
        put(obs.oppHandSize)
        put(AiObsEncoding.run { obs.modHoldTheLine.toObsCode() })
        put(AiObsEncoding.run { obs.modGangUp.toObsCode() })
        put(AiObsEncoding.run { obs.modAllInTackleProneAfterBlock.toObsCode() })
        check(i == AiObsLayout.SCALAR_COUNT) { "scalar pack width $i != ${AiObsLayout.SCALAR_COUNT}" }

        for (slot in obs.challenges) {
            packChallenge(slot) { put(it) }
        }
        put(AiObsEncoding.run { obs.deckTopCondition.toObsCode() })
        put(obs.deckSize)
        put(obs.discardSize)
        for (card in obs.ownBonusPlay) {
            put(AiObsEncoding.run { card.toObsCode() })
        }
        packHeldCheck(obs.heldCheck) { put(it) }
        for (cell in obs.cells) {
            packCell(cell) { put(it) }
        }
        check(i == AiObsLayout.OBSERVATION_VECTOR_LENGTH) { "packed obs length $i != ${AiObsLayout.OBSERVATION_VECTOR_LENGTH}" }
        return out
    }

    private inline fun packChallenge(slot: AiChallengeSlotObs, put: (Int) -> Unit) {
        put(AiObsEncoding.run { slot.condition.toObsCode() })
        put(slot.points)
    }

    private inline fun packHeldCheck(held: AiHeldCheckObs, put: (Int) -> Unit) {
        put(AiObsEncoding.run { held.kind.toObsCode() })
        put(held.d6 ?: 0)
        for (face in held.blockFaces) {
            put(AiObsEncoding.run { face.toObsCode() })
        }
    }

    private inline fun packCell(cell: AiCellObs, put: (Int) -> Unit) {
        put(AiObsEncoding.run { cell.terrain.toObsCode() })
        put(AiObsEncoding.run { cell.hasBall.toObsCode() })
        val player = cell.player
        if (player == null) {
            packAbsentPlayer(put)
        } else {
            packPresentPlayer(player, put)
        }
        put(cell.pitchX)
        put(cell.pitchY)
        put(cell.distToBall)
    }

    private inline fun packAbsentPlayer(put: (Int) -> Unit) {
        put(AiObsLayout.UNUSED_PLAYER_INDEX)
        put(0) // teamRel
        put(0) // status
        put(AiObsLayout.SENTINEL) // turnEnded
        put(AiObsLayout.SENTINEL) // move
        put(AiObsLayout.SENTINEL) // blockDice
        put(AiObsLayout.SENTINEL) // throwStat
        put(AiObsLayout.SENTINEL) // armour
        put(0) // ability
        put(AiObsLayout.SENTINEL) // canThrowTeammate
        packAbsentAffordance(put)
    }

    private inline fun packPresentPlayer(player: AiCellPlayerObs, put: (Int) -> Unit) {
        put(player.playerIndex)
        put(AiObsEncoding.run { player.teamRel.toObsCode() })
        put(AiObsEncoding.run { player.status.toObsCode() })
        put(AiObsEncoding.run { player.turnEnded.toObsCode() })
        put(player.move)
        put(player.blockDice)
        put(player.throwStat)
        put(player.armour)
        put(AiObsEncoding.run { player.ability.toObsCode() })
        put(AiObsEncoding.run { player.canThrowTeammate.toObsCode() })
        packAffordance(player.affordance, put)
    }

    private inline fun packAffordance(affordance: AiPlayerAffordance, put: (Int) -> Unit) {
        put(AiObsEncoding.run { affordance.canScoreTouchdown.toObsCode() })
        put(AiObsEncoding.run { affordance.mightClaimChallenge.toObsCode() })
        put(AiObsEncoding.run { affordance.certainClaimChallenge.toObsCode() })
        put(AiObsEncoding.run { affordance.canReachBallSquare.toObsCode() })
        put(AiObsEncoding.run { affordance.canPickupBall.toObsCode() })
        put(affordance.reachMinX)
        put(affordance.reachMaxX)
        put(affordance.reachMinY)
        put(affordance.reachMaxY)
    }

    private inline fun packAbsentAffordance(put: (Int) -> Unit) {
        put(AiObsLayout.SENTINEL) // canScoreTouchdown
        put(AiObsLayout.SENTINEL) // mightClaimChallenge
        put(AiObsLayout.SENTINEL) // certainClaimChallenge
        put(AiObsLayout.SENTINEL) // canReachBallSquare
        put(AiObsLayout.SENTINEL) // canPickupBall
        put(AiObsLayout.SENTINEL) // reachMinX
        put(AiObsLayout.SENTINEL) // reachMaxX
        put(AiObsLayout.SENTINEL) // reachMinY
        put(AiObsLayout.SENTINEL) // reachMaxY
    }
}
