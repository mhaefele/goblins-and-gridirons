package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiLegalChoiceDto
import com.mhaefele.gridiron.api.ai.dto.AiLegalKind
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.AiPitchSquare
import com.mhaefele.gridiron.api.ai.dto.internal.AiObsEncoding
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.throwSuccessChancePercentFromNeeded
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Policy legal-row int matrix from harness rows — matches Python gym ``legal_moves``.
 *
 * Perspective + 0-based pitch packing stay here (not on the harness wire).
 * Pack order: kind, actorHasBall, sourceX/Y, actor, targetHasBall, targetX/Y,
 * blockDiceCount, throwSuccessChancePercent, challengeSlot, attachedBonus, face0..2.
 * Absent axes: nullable → [AiObsEncoding.toObsCode] → [AiObsLayout.SENTINEL].
 */
@Component
class AiLegalPacker(
    private val gameState: AiGameStateMapper,
) {
    init {
        check(BonusPlay.entries.size == AiObsLayout.BONUS_PLAY_COUNT) { "BonusPlay.entries=${BonusPlay.entries.size} != AiObsLayout.BONUS_PLAY_COUNT=${AiObsLayout.BONUS_PLAY_COUNT}" }
    }

    fun legalMoves(
        choices: List<AiLegalChoiceDto>,
        state: MatchState,
        perspectiveSeat: TeamSide,
    ): List<List<Int>> =
        choices.map { harnessFactorKey(it.action, state, perspectiveSeat) }

    fun harnessFactorKey(
        action: LegalAction,
        state: MatchState,
        perspectiveSeat: TeamSide,
    ): List<Int> = AiObsEncoding.run {
        val kind = packKind(action)
        val actorId = actorPlayerId(action)
        val dest = destCoordinate(action)
        val target = dest?.let { gameState.perspectivePitchXy0(it, perspectiveSeat) }
        val source = sourcePitch(actorId, state, perspectiveSeat)
        val challengeSlot = when (action) {
            is LegalAction.ClaimChallenge   -> action.slotIndex
            is LegalAction.ReplaceChallenge -> action.slotIndex
            else                            -> null
        }
        val orderedFaces = (action as? LegalAction.ChooseBlockFaceOrder)?.orderedFaces
        if (orderedFaces != null) {
            require(orderedFaces.size <= AiObsLayout.MAX_HELD_BLOCK_FACES) { "FaceOrder length ${orderedFaces.size} > ${AiObsLayout.MAX_HELD_BLOCK_FACES}" }
        }
        val actorHasBall = actorId != null && state.board.player(actorId).holdsBall()
        val targetHasBall = dest != null && squareHasBall(state.board.squareAt(dest), state)
        listOf(
            kind.ordinal,
            if (actorHasBall) 1 else 0,
            source?.x.toObsCode(),
            source?.y.toObsCode(),
            actorId?.let { gameState.aiPlayerIndex(it) }.toObsCode(),
            if (targetHasBall) 1 else 0,
            target?.x.toObsCode(),
            target?.y.toObsCode(),
            blockDiceCount(action).toObsCode(),
            // Continuous 0..100 (0 = non-throw) — not SENTINEL; feature is /100 only.
            throwSuccessChancePercent(action),
            challengeSlot.toObsCode(),
            action.requiredBonusCard.toObsCode(),
            orderedFaces?.getOrNull(0).toObsCode(),
            orderedFaces?.getOrNull(1).toObsCode(),
            orderedFaces?.getOrNull(2).toObsCode(),
        )
    }

    /** From [ThrowTarget.throwCheckNeeded] — Kotlin owns P(success); Python only `/100`. `0` = non-throw. */
    private fun throwSuccessChancePercent(action: LegalAction): Int =
        when (action) {
            is LegalAction.Throw             -> throwSuccessChancePercentFromNeeded(action.target.throwCheckNeeded)
            is LegalAction.ThrowTeammate     -> throwSuccessChancePercentFromNeeded(action.target.throwCheckNeeded)
            is LegalAction.QuickReleaseThrow -> throwSuccessChancePercentFromNeeded(action.target.throwCheckNeeded)
            else                             -> 0
        }

    private fun blockDiceCount(action: LegalAction): Int? =
        when (action) {
            is LegalAction.Block -> action.blockDiceCount
            else                 -> null
        }

    private fun squareHasBall(square: BoardSquare, state: MatchState): Boolean =
        square.looseBallId != null || state.board.playerAt(square.coordinate)?.holdsBall() == true

    private fun sourcePitch(
        actorId: PlayerId?,
        state: MatchState,
        perspectiveSeat: TeamSide,
    ): AiPitchSquare? {
        if (actorId == null) return null
        return when (state.board.player(actorId).status) {
            PlayerStatus.STANDING, PlayerStatus.PRONE -> gameState.perspectivePitchXy0(state.board.findSquare(actorId).coordinate, perspectiveSeat)
            PlayerStatus.RESERVES                     -> null
        }
    }

    companion object {
        fun actorPlayerId(action: LegalAction): PlayerId? =
            when (action) {
                // Free actor (packed when free; sugar/packed for pending-fixed rows too — who-id).
                is LegalAction.PathMovement,
                is LegalAction.DeclareRun,
                is LegalAction.Throw,
                is LegalAction.ThrowTeammateSelect,
                is LegalAction.Block,
                is LegalAction.Foul,
                is LegalAction.StandUp,
                is LegalAction.Reserves,
                is LegalAction.ThrowTeammate,
                is LegalAction.CancelThrowTeammate,
                is LegalAction.Skip,
                is LegalAction.ContinueBlock,
                is LegalAction.PlaceLooseBall,
                is LegalAction.ChooseBlockReroll,
                is LegalAction.ChooseThrowReroll,
                is LegalAction.ChooseBlockFaceOrder,
                is LegalAction.AdjustThrowByDoOver,
                is LegalAction.AdjustBlockByDoOver,
                is LegalAction.QuickReleaseThrow    -> (action as LegalAction.PlayerLegalAction).playerId
                is LegalAction.AdjustArmourByDoOver -> action.targetId
                else                                -> null
            }

        fun destCoordinate(action: LegalAction): Coordinate? =
            when (action) {
                is LegalAction.BoardTargetedLegalAction -> action.targetCoordinate
                is LegalAction.AdjustThrowByDoOver      -> action.destination
                is LegalAction.AdjustBlockByDoOver      -> action.destination
                else                                    -> null
            }

        /**
         * Pack-kind vocabulary — keepOffer/reroll split Choose*Reroll; not 1:1 with Jackson `kind`.
         * TODO(AI2.19): shared wire kinds so this collapses.
         */
        fun packKind(action: LegalAction): AiLegalKind =
            when (action) {
                is LegalAction.Run                  -> AiLegalKind.run
                is LegalAction.Mark                 -> AiLegalKind.mark
                is LegalAction.Sidestep             -> AiLegalKind.sidestep
                is LegalAction.Throw                -> AiLegalKind.`throw`
                is LegalAction.ThrowTeammateSelect  -> AiLegalKind.throwTeammateSelect
                is LegalAction.ThrowTeammate        -> AiLegalKind.throwTeammate
                is LegalAction.CancelThrowTeammate  -> AiLegalKind.cancelThrowTeammate
                is LegalAction.Block                -> AiLegalKind.block
                is LegalAction.Foul                 -> AiLegalKind.foul
                is LegalAction.StandUp              -> AiLegalKind.standUp
                is LegalAction.Reserves             -> AiLegalKind.reserves
                is LegalAction.Skip                 -> AiLegalKind.skip
                is LegalAction.DeclineBonus         -> AiLegalKind.declineBonus
                is LegalAction.ContinueBlock        -> AiLegalKind.continueBlock
                is LegalAction.PlaceLooseBall       -> AiLegalKind.placeLooseBall
                is LegalAction.ChooseBlockReroll    -> if (action.reroll) AiLegalKind.`reroll` else AiLegalKind.keepOffer
                is LegalAction.ChooseThrowReroll    -> if (action.reroll) AiLegalKind.`reroll` else AiLegalKind.keepOffer
                is LegalAction.ChooseBlockFaceOrder -> AiLegalKind.chooseBlockFaceOrder
                is LegalAction.AdjustThrowByDoOver  -> AiLegalKind.adjustThrowByDoOver
                is LegalAction.AdjustBlockByDoOver  -> AiLegalKind.adjustBlockByDoOver
                is LegalAction.AdjustArmourByDoOver -> AiLegalKind.adjustArmourByDoOver
                is LegalAction.QuickReleaseThrow    -> AiLegalKind.quickReleaseThrow
                is LegalAction.DeclareRun           -> AiLegalKind.declareRun
                is LegalAction.PlayGangUp           -> AiLegalKind.playGangUp
                is LegalAction.PlayHoldTheLine      -> AiLegalKind.playHoldTheLine
                is LegalAction.PlayExtraEffort      -> AiLegalKind.playExtraEffort
                is LegalAction.PlayMomentum         -> AiLegalKind.playMomentum
                is LegalAction.PlayAllInTackle      -> AiLegalKind.playAllInTackle
                is LegalAction.PlaySlipAway         -> AiLegalKind.playSlipAway
                is LegalAction.PlayCutOff           -> AiLegalKind.playCutOff
                is LegalAction.PlayStickClose       -> AiLegalKind.playStickClose
                is LegalAction.PlayBackOnYourFeet   -> AiLegalKind.playBackOnYourFeet
                is LegalAction.PlayFreshLegs        -> AiLegalKind.playFreshLegs
                is LegalAction.PlayBrace            -> AiLegalKind.playBrace
                is LegalAction.PlayUnderdog         -> AiLegalKind.playUnderdog
                is LegalAction.PlaySpikedGauntlets  -> AiLegalKind.playSpikedGauntlets
                is LegalAction.PlayDeadEye          -> AiLegalKind.playDeadEye
                is LegalAction.PlayHailMary         -> AiLegalKind.playHailMary
                is LegalAction.PlaySprint           -> AiLegalKind.playSprint
                is LegalAction.ClaimChallenge       -> AiLegalKind.claimChallenge
                is LegalAction.ReplaceChallenge     -> AiLegalKind.replaceChallenge
            }
    }
}
