package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.ai.dto.AiLegalChoiceDto
import com.mhaefele.gridiron.api.ai.dto.AiLegalKind
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.ai.training.TrainingSolvableMoveSearch
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
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
        val forAi = actions.filterNot { it is LegalAction.CancelThrowTeammate }
            .filterNot { action ->
                // Ability keep-row already covers “don’t spend RT + don’t reroll”; DeclineBonus would
                // collide with Choose*Reroll(false) under declineOffer + empty free factors.
                action is LegalAction.DeclineBonus &&
                    actions.any { it is LegalAction.ChooseBlockReroll || it is LegalAction.ChooseThrowReroll }
            }
        check(actions.none { it is LegalAction.ChooseBlockFaceOrder }) { "ChooseBlockFaceOrder must not appear on AI seats(humans-only; HARNESS/COMPUTER auto-sort)" }
        // softMaskIds: null = search N/A (use heuristic); non-empty = those ids; empty = searched,
        // none solvable — still fall back to heuristic so soft-mask is not blanked.
        val solvableIds = solvableSoftMask.softMaskIds(state, forAi)
        val choices = forAi.map { action ->
            toAiLegalChoiceDto(
                action,
                perspectiveSeat,
                passingLegal = trainingScenarios.isPassingScenarioMove(action, state),
                softMaskLegal = when {
                    solvableIds == null      -> trainingScenarios.isSoftMaskScenarioMove(action, state)
                    solvableIds.isNotEmpty() -> action.id in solvableIds
                    else                     -> trainingScenarios.isSoftMaskScenarioMove(action, state)
                },
            )
        }
        // AttachedBonus is sugar — two geometry cards to the same free key collapse to one row.
        val deduped = preferUniqueHarnessKeys(choices)
        checkDistinctHarnessFactorKeys(deduped)
        return deduped
    }

    fun toAiLegalChoiceDto(
        action: LegalAction,
        perspectiveSeat: TeamSide,
        passingLegal: Boolean = false,
        softMaskLegal: Boolean = false,
    ): AiLegalChoiceDto {
        val kind = action.toAiLegalKind()
        val (free, sugar) = action.toAiFreeAndSugarFactors(perspectiveSeat)
        val sugarWithBonus = buildList {
            addAll(sugar)
            action.requiredBonusCard?.let { add(AiFactor.AttachedBonus(AttachedBonusEncoding.indexFor(it))) }
        }
        return AiLegalChoiceDto(
            kind = kind,
            kindIndex = kind.ordinal,
            factors = free,
            sugarFactors = sugarWithBonus,
            legalActionId = action.id,
            potentialChallengeClaims = action.potentialChallengeClaims,
            passingLegal = passingLegal,
            softMaskLegal = softMaskLegal,
        )
    }

    private fun LegalAction.toAiLegalKind(): AiLegalKind =
        when (this) {
            is LegalAction.Run                  -> AiLegalKind.run
            is LegalAction.Mark                 -> AiLegalKind.mark
            is LegalAction.Sidestep             -> AiLegalKind.sidestep
            is LegalAction.Throw                -> AiLegalKind.`throw`
            is LegalAction.ThrowTeammateSelect  -> AiLegalKind.throwTeammateSelect
            is LegalAction.ThrowTeammate        -> AiLegalKind.throwTeammate
            is LegalAction.CancelThrowTeammate  -> error("CancelThrowTeammate is excluded from AI legals")
            is LegalAction.Block                -> AiLegalKind.block
            is LegalAction.Foul                 -> AiLegalKind.foul
            is LegalAction.StandUp              -> AiLegalKind.standUp
            is LegalAction.Reserves             -> AiLegalKind.reserves
            is LegalAction.Skip                 -> AiLegalKind.declineOffer
            is LegalAction.DeclineBonus         -> AiLegalKind.declineOffer
            is LegalAction.ContinueBlock        -> AiLegalKind.declineOffer
            is LegalAction.PlaceLooseBall       -> AiLegalKind.placeLooseBall
            is LegalAction.ChooseBlockReroll    -> if (reroll) AiLegalKind.`reroll` else AiLegalKind.declineOffer
            is LegalAction.ChooseThrowReroll    -> if (reroll) AiLegalKind.`reroll` else AiLegalKind.declineOffer
            is LegalAction.ChooseBlockFaceOrder -> error("ChooseBlockFaceOrder is excluded from AI legals (humans-only)")
            is LegalAction.AdjustThrowByDoOver  -> AiLegalKind.adjustByDoOver
            is LegalAction.AdjustBlockByDoOver  -> AiLegalKind.adjustByDoOver
            is LegalAction.AdjustArmourByDoOver -> AiLegalKind.adjustByDoOver
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

    /** Free factors (MultiDiscrete) + sugar (pending-fixed / attached bonus; not matched). */
    private fun LegalAction.toAiFreeAndSugarFactors(
        perspectiveSeat: TeamSide,
    ): Pair<List<AiFactor>, List<AiFactor>> {
        fun actorOf(playerId: PlayerId) = AiFactor.Actor(gameState.aiPlayerIndex(playerId))
        fun pitchOf(coord: Coordinate) = AiFactor.BoardTarget(gameState.perspectivePitchIndex(coord, perspectiveSeat))

        return when (this) {
            is LegalAction.PathMovement         -> listOf(actorOf(playerId), pitchOf(path.squares.last())) to emptyList()
            is LegalAction.DeclareRun           -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Throw                -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.ThrowTeammateSelect  -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.ThrowTeammate        -> listOf(pitchOf(targetCoordinate)) to listOf(actorOf(playerId))
            is LegalAction.CancelThrowTeammate  -> error("CancelThrowTeammate is excluded from AI legals")
            is LegalAction.Block                -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Foul                 -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.StandUp              -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Reserves             -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Skip                 -> emptyList<AiFactor>() to listOf(actorOf(playerId))
            is LegalAction.DeclineBonus         -> emptyList<AiFactor>() to emptyList()
            is LegalAction.ContinueBlock        -> emptyList<AiFactor>() to listOf(actorOf(playerId), pitchOf(targetCoordinate))
            is LegalAction.PlaceLooseBall       -> listOf(pitchOf(to)) to listOf(actorOf(playerId))
            is LegalAction.ChooseBlockReroll    -> emptyList<AiFactor>() to listOf(actorOf(playerId))
            is LegalAction.ChooseThrowReroll    -> emptyList<AiFactor>() to listOf(actorOf(playerId))
            is LegalAction.ChooseBlockFaceOrder -> error("ChooseBlockFaceOrder is excluded from AI legals")
            is LegalAction.AdjustThrowByDoOver  -> emptyList<AiFactor>() to listOf(actorOf(playerId), pitchOf(destination))
            is LegalAction.AdjustBlockByDoOver  -> emptyList<AiFactor>() to listOf(actorOf(playerId), pitchOf(destination))
            is LegalAction.AdjustArmourByDoOver -> emptyList<AiFactor>() to listOf(actorOf(targetId))
            is LegalAction.QuickReleaseThrow    -> listOf(pitchOf(targetCoordinate)) to listOf(actorOf(playerId))
            is LegalAction.PlayBonusCard        -> emptyList<AiFactor>() to emptyList()
            is LegalAction.ClaimChallenge       -> listOf(AiFactor.ChallengeSlot(slotIndex)) to emptyList()
            is LegalAction.ReplaceChallenge     -> listOf(AiFactor.ChallengeSlot(slotIndex)) to emptyList()
        }
    }

    /**
     * When free keys collide (e.g. Sprint + Weave unlock the same dest), keep one row.
     * Prefer no AttachedBonus sugar, else lower [AttachedBonusEncoding] index.
     */
    private fun preferUniqueHarnessKeys(choices: List<AiLegalChoiceDto>): List<AiLegalChoiceDto> {
        val best = linkedMapOf<List<Int>, AiLegalChoiceDto>()
        for (choice in choices) {
            val key = AiLegalPacker.harnessFactorKey(choice).toList()
            val prior = best[key]
            if (prior == null || attachedBonusRank(choice) < attachedBonusRank(prior)) {
                best[key] = choice
            }
        }
        return best.values.toList()
    }

    private fun attachedBonusRank(choice: AiLegalChoiceDto): Int =
        choice.sugarFactors.filterIsInstance<AiFactor.AttachedBonus>().minOfOrNull { it.index } ?: -1

    /** Harness tuple after packing free factors — must be unique within one legal set. */
    private fun checkDistinctHarnessFactorKeys(choices: List<AiLegalChoiceDto>) {
        val seen = mutableMapOf<List<Int>, AiLegalChoiceDto>()
        for (choice in choices) {
            val key = AiLegalPacker.harnessFactorKey(choice).toList()
            val prior = seen.put(key, choice)
            if (prior != null) {
                error(
                    "Duplicate harness factor key $key for kinds ${prior.kind} and ${choice.kind} — " + "legalActionIds ${prior.legalActionId} vs ${choice.legalActionId}",)
            }
        }
    }
}
