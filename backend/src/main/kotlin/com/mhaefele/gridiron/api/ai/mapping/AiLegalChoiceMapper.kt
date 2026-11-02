package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.ai.dto.AiLegalChoiceDto
import com.mhaefele.gridiron.api.ai.dto.AiLegalKind
import com.mhaefele.gridiron.api.ai.dto.AiPitchSquare
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.ai.training.TrainingSolvableMoveSearch
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

@Component
class AiLegalChoiceMapper(
    private val trainingScenarios: TrainingScenarios,
    private val solvableSoftMask: TrainingSolvableMoveSearch,
    private val gameState: AiGameStateMapper,
) {
    fun toLegalChoices(
        actions: List<LegalAction>,
        perspectiveSeat: TeamSide,
        state: MatchState,
    ): List<AiLegalChoiceDto> {
        // softMaskIds: null = search N/A (use heuristic); non-empty = those ids; empty = searched,
        // none solvable — still fall back to heuristic so soft-mask is not blanked.
        val solvableIds = solvableSoftMask.softMaskIds(state, actions)
        val choices = actions.map { action ->
            toAiLegalChoiceDto(
                action,
                perspectiveSeat,
                state,
                passingLegal = trainingScenarios.isPassingScenarioMove(action, state),
                softMaskLegal = when {
                    solvableIds == null      -> trainingScenarios.isSoftMaskScenarioMove(action, state)
                    solvableIds.isNotEmpty() -> action.id in solvableIds
                    else                     -> trainingScenarios.isSoftMaskScenarioMove(action, state)
                },
            )
        }
        checkDistinctHarnessFactorKeys(choices)
        // Shuffle so join-score / soft-mask cannot latch onto catalog order. Packed legalMoves,
        // harness legalRows, soft-mask flags, and the chosen row index all share this order —
        // resolve by index into the shuffled menu (ONNX + Python env).
        return choices.shuffled()
    }

    fun toAiLegalChoiceDto(
        action: LegalAction,
        perspectiveSeat: TeamSide,
        state: MatchState,
        passingLegal: Boolean = false,
        softMaskLegal: Boolean = false,
    ): AiLegalChoiceDto {
        val kind = action.toAiLegalKind()
        val parts = action.toAiRowParts(perspectiveSeat)
        val sugar = buildList {
            addAll(parts.sugar)
            action.requiredBonusCard?.let { add(AiFactor.AttachedBonus(AttachedBonusEncoding.indexFor(it))) }
        }
        val source = playerIdOrNull(action)?.let { playerId ->
            when (state.board.player(playerId).status) {
                PlayerStatus.STANDING, PlayerStatus.PRONE -> gameState.perspectivePitchXy0(state.board.findSquare(playerId).coordinate, perspectiveSeat)
                PlayerStatus.RESERVES                     -> null
            }
        }
        return AiLegalChoiceDto(
            kind = kind,
            kindIndex = kind.ordinal,
            factors = parts.free,
            sugarFactors = sugar,
            target = parts.target,
            source = source,
            legalActionId = action.id,
            potentialChallengeClaims = action.potentialChallengeClaims,
            passingLegal = passingLegal,
            softMaskLegal = softMaskLegal,
        )
    }

    private fun playerIdOrNull(action: LegalAction): PlayerId? =
        when (action) {
            is LegalAction.PlayerLegalAction -> action.playerId
            else                             -> null
        }

    private fun LegalAction.toAiLegalKind(): AiLegalKind =
        when (this) {
            is LegalAction.Run                 -> AiLegalKind.run
            is LegalAction.Mark                -> AiLegalKind.mark
            is LegalAction.Sidestep            -> AiLegalKind.sidestep
            is LegalAction.Throw               -> AiLegalKind.`throw`
            is LegalAction.ThrowTeammateSelect -> AiLegalKind.throwTeammateSelect
            is LegalAction.ThrowTeammate       -> AiLegalKind.throwTeammate
            is LegalAction.CancelThrowTeammate -> AiLegalKind.cancelThrowTeammate
            is LegalAction.Block               -> AiLegalKind.block
            is LegalAction.Foul                -> AiLegalKind.foul
            is LegalAction.StandUp             -> AiLegalKind.standUp
            is LegalAction.Reserves            -> AiLegalKind.reserves
            is LegalAction.Skip                -> AiLegalKind.skip
            is LegalAction.DeclineBonus        -> AiLegalKind.declineBonus
            is LegalAction.ContinueBlock       -> AiLegalKind.continueBlock
            is LegalAction.PlaceLooseBall      -> AiLegalKind.placeLooseBall
            // TODO(AI2.19): Choose*Reroll(reroll: Boolean) is two choices stuffed in one subtype —
            // only arms that branch on a field. Prefer distinct Keep / Reroll legal actions (or a
            // shared wire kind with no boolean) so mapping is 1:1 like every other arm.
            is LegalAction.ChooseBlockReroll    -> if (reroll) AiLegalKind.`reroll` else AiLegalKind.keepOffer
            is LegalAction.ChooseThrowReroll    -> if (reroll) AiLegalKind.`reroll` else AiLegalKind.keepOffer
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

    /** Free / sugar non-pitch factors + optional dest [AiPitchSquare]. */
    private fun LegalAction.toAiRowParts(perspectiveSeat: TeamSide): RowParts {
        fun actorOf(playerId: PlayerId) = AiFactor.Actor(gameState.aiPlayerIndex(playerId))
        fun pitchOf(coord: Coordinate) = gameState.perspectivePitchXy0(coord, perspectiveSeat)

        return when (this) {
            is LegalAction.PathMovement         -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(path.squares.last()))
            is LegalAction.DeclareRun           -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(targetCoordinate))
            is LegalAction.Throw                -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(targetCoordinate))
            is LegalAction.ThrowTeammateSelect  -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(targetCoordinate))
            is LegalAction.ThrowTeammate        -> RowParts(emptyList(), listOf(actorOf(playerId)), pitchOf(targetCoordinate))
            is LegalAction.CancelThrowTeammate  -> RowParts(emptyList(), listOf(actorOf(playerId)))
            is LegalAction.Block                -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(targetCoordinate))
            is LegalAction.Foul                 -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(targetCoordinate))
            is LegalAction.StandUp              -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(targetCoordinate))
            is LegalAction.Reserves             -> RowParts(listOf(actorOf(playerId)), emptyList(), pitchOf(targetCoordinate))
            is LegalAction.Skip                 -> RowParts(emptyList(), listOf(actorOf(playerId)))
            is LegalAction.DeclineBonus         -> RowParts(emptyList(), emptyList())
            is LegalAction.ContinueBlock        -> RowParts(emptyList(), listOf(actorOf(playerId)), pitchOf(targetCoordinate))
            is LegalAction.PlaceLooseBall       -> RowParts(emptyList(), listOf(actorOf(playerId)), pitchOf(to))
            is LegalAction.ChooseBlockReroll    -> RowParts(emptyList(), listOf(actorOf(playerId)))
            is LegalAction.ChooseThrowReroll    -> RowParts(emptyList(), listOf(actorOf(playerId)))
            is LegalAction.ChooseBlockFaceOrder ->
                RowParts(listOf(AiFactor.FaceOrder(orderedFaces.map { it.ordinal + 1 })), listOf(actorOf(playerId)))
            is LegalAction.AdjustThrowByDoOver  -> RowParts(emptyList(), listOf(actorOf(playerId)), pitchOf(destination))
            is LegalAction.AdjustBlockByDoOver  -> RowParts(emptyList(), listOf(actorOf(playerId)), pitchOf(destination))
            is LegalAction.AdjustArmourByDoOver -> RowParts(emptyList(), listOf(actorOf(targetId)))
            is LegalAction.QuickReleaseThrow    -> RowParts(emptyList(), listOf(actorOf(playerId)), pitchOf(targetCoordinate))
            is LegalAction.PlayBonusCard        -> RowParts(emptyList(), emptyList())
            is LegalAction.ClaimChallenge       -> RowParts(listOf(AiFactor.ChallengeSlot(slotIndex)), emptyList())
            is LegalAction.ReplaceChallenge     -> RowParts(listOf(AiFactor.ChallengeSlot(slotIndex)), emptyList())
        }
    }

    /** Packed legal-row ints must be unique within one legal set (submit identity via legalActionId). */
    private fun checkDistinctHarnessFactorKeys(choices: List<AiLegalChoiceDto>) {
        val seen = mutableMapOf<List<Int>, AiLegalChoiceDto>()
        for (choice in choices) {
            val key = AiLegalPacker.harnessFactorKey(choice)
            val prior = seen.put(key, choice)
            check(prior == null) { "Duplicate legal-row pack $key for ${prior!!.kind}/${prior.legalActionId} and ${choice.kind}/${choice.legalActionId}" }
        }
    }

    private data class RowParts(
        val free: List<AiFactor>,
        val sugar: List<AiFactor>,
        val target: AiPitchSquare? = null,
    )
}
