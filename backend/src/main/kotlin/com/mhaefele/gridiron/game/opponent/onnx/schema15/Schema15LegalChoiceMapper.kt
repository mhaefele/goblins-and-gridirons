package com.mhaefele.gridiron.game.opponent.onnx.schema15

/** SCHEMA15_DELETE — delete this file with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE). */

import com.mhaefele.gridiron.api.ai.mapping.AiGameStateMapper
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Maps live [LegalAction] → frozen schema-15 rows for product ONNX.
 *
 * Soft-mask / training flags are unused (deterministic factor pick has no soft bias).
 * Callers must pre-settle [LegalAction.ChooseBlockFaceOrder] before invoking.
 */
@Component
class Schema15LegalChoiceMapper(
    private val gameState: AiGameStateMapper,
) {
    fun toLegalChoices(
        actions: List<LegalAction>,
        perspectiveSeat: TeamSide,
    ): List<Schema15LegalChoice> {
        check(actions.none { it is LegalAction.ChooseBlockFaceOrder }) { "ChooseBlockFaceOrder must be pre-settled before schema-15 ONNX mapping" }
        val forAi = actions.filterNot { it is LegalAction.CancelThrowTeammate }
            .filterNot { action ->
                // Ability keep-row already covers “don’t spend RT + don’t reroll”; DeclineBonus would
                // collide with Choose*Reroll(false) under declineOffer + empty free factors.
                action is LegalAction.DeclineBonus &&
                    actions.any { it is LegalAction.ChooseBlockReroll || it is LegalAction.ChooseThrowReroll }
            }
        val choices = forAi.map { toChoice(it, perspectiveSeat) }
        val deduped = preferUniqueHarnessKeys(choices)
        checkDistinctHarnessFactorKeys(deduped)
        return deduped
    }

    private fun toChoice(action: LegalAction, perspectiveSeat: TeamSide): Schema15LegalChoice {
        val kind = action.toSchema15Kind()
        val (free, sugar) = action.toFreeAndSugar(perspectiveSeat)
        val sugarWithBonus = buildList {
            addAll(sugar)
            action.requiredBonusCard?.let { add(Schema15Factor.AttachedBonus(Schema15AttachedBonusEncoding.indexFor(it))) }
        }
        return Schema15LegalChoice(
            kind = kind,
            kindIndex = kind.ordinal,
            factors = free,
            sugarFactors = sugarWithBonus,
            legalActionId = action.id,
        )
    }

    private fun LegalAction.toSchema15Kind(): Schema15LegalKind =
        when (this) {
            is LegalAction.Run                  -> Schema15LegalKind.run
            is LegalAction.Mark                 -> Schema15LegalKind.mark
            is LegalAction.Sidestep             -> Schema15LegalKind.sidestep
            is LegalAction.Throw                -> Schema15LegalKind.`throw`
            is LegalAction.ThrowTeammateSelect  -> Schema15LegalKind.throwTeammateSelect
            is LegalAction.ThrowTeammate        -> Schema15LegalKind.throwTeammate
            is LegalAction.CancelThrowTeammate  -> error("CancelThrowTeammate is excluded from schema-15 AI legals")
            is LegalAction.Block                -> Schema15LegalKind.block
            is LegalAction.Foul                 -> Schema15LegalKind.foul
            is LegalAction.StandUp              -> Schema15LegalKind.standUp
            is LegalAction.Reserves             -> Schema15LegalKind.reserves
            is LegalAction.Skip                 -> Schema15LegalKind.declineOffer
            is LegalAction.DeclineBonus         -> Schema15LegalKind.declineOffer
            is LegalAction.ContinueBlock        -> Schema15LegalKind.declineOffer
            is LegalAction.PlaceLooseBall       -> Schema15LegalKind.placeLooseBall
            is LegalAction.ChooseBlockReroll    -> if (reroll) Schema15LegalKind.`reroll` else Schema15LegalKind.declineOffer
            is LegalAction.ChooseThrowReroll    -> if (reroll) Schema15LegalKind.`reroll` else Schema15LegalKind.declineOffer
            is LegalAction.ChooseBlockFaceOrder -> error("ChooseBlockFaceOrder must be pre-settled")
            is LegalAction.AdjustThrowByDoOver  -> Schema15LegalKind.adjustByDoOver
            is LegalAction.AdjustBlockByDoOver  -> Schema15LegalKind.adjustByDoOver
            is LegalAction.AdjustArmourByDoOver -> Schema15LegalKind.adjustByDoOver
            is LegalAction.QuickReleaseThrow    -> Schema15LegalKind.quickReleaseThrow
            is LegalAction.DeclareRun           -> Schema15LegalKind.declareRun
            is LegalAction.PlayGangUp           -> Schema15LegalKind.playGangUp
            is LegalAction.PlayHoldTheLine      -> Schema15LegalKind.playHoldTheLine
            is LegalAction.PlayExtraEffort      -> Schema15LegalKind.playExtraEffort
            is LegalAction.PlayMomentum         -> Schema15LegalKind.playMomentum
            is LegalAction.PlayAllInTackle      -> Schema15LegalKind.playAllInTackle
            is LegalAction.PlaySlipAway         -> Schema15LegalKind.playSlipAway
            is LegalAction.PlayCutOff           -> Schema15LegalKind.playCutOff
            is LegalAction.PlayStickClose       -> Schema15LegalKind.playStickClose
            is LegalAction.PlayBackOnYourFeet   -> Schema15LegalKind.playBackOnYourFeet
            is LegalAction.PlayFreshLegs        -> Schema15LegalKind.playFreshLegs
            is LegalAction.PlayBrace            -> Schema15LegalKind.playBrace
            is LegalAction.PlayUnderdog         -> Schema15LegalKind.playUnderdog
            is LegalAction.PlaySpikedGauntlets  -> Schema15LegalKind.playSpikedGauntlets
            is LegalAction.PlayDeadEye          -> Schema15LegalKind.playDeadEye
            is LegalAction.PlayHailMary         -> Schema15LegalKind.playHailMary
            is LegalAction.PlaySprint           -> Schema15LegalKind.playSprint
            is LegalAction.ClaimChallenge       -> Schema15LegalKind.claimChallenge
            is LegalAction.ReplaceChallenge     -> Schema15LegalKind.replaceChallenge
        }

    private fun LegalAction.toFreeAndSugar(
        perspectiveSeat: TeamSide,
    ): Pair<List<Schema15Factor>, List<Schema15Factor>> {
        fun actorOf(playerId: PlayerId) = Schema15Factor.Actor(gameState.aiPlayerIndex(playerId))
        fun pitchOf(coord: Coordinate) = Schema15Factor.BoardTarget(gameState.perspectivePitchIndex(coord, perspectiveSeat))

        return when (this) {
            is LegalAction.PathMovement         -> listOf(actorOf(playerId), pitchOf(path.squares.last())) to emptyList()
            is LegalAction.DeclareRun           -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Throw                -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.ThrowTeammateSelect  -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.ThrowTeammate        -> listOf(pitchOf(targetCoordinate)) to listOf(actorOf(playerId))
            is LegalAction.CancelThrowTeammate  -> error("CancelThrowTeammate is excluded from schema-15 AI legals")
            is LegalAction.Block                -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Foul                 -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.StandUp              -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Reserves             -> listOf(actorOf(playerId), pitchOf(targetCoordinate)) to emptyList()
            is LegalAction.Skip                 -> emptyList<Schema15Factor>() to listOf(actorOf(playerId))
            is LegalAction.DeclineBonus         -> emptyList<Schema15Factor>() to emptyList()
            is LegalAction.ContinueBlock        -> emptyList<Schema15Factor>() to listOf(actorOf(playerId), pitchOf(targetCoordinate))
            is LegalAction.PlaceLooseBall       -> listOf(pitchOf(to)) to listOf(actorOf(playerId))
            is LegalAction.ChooseBlockReroll    -> emptyList<Schema15Factor>() to listOf(actorOf(playerId))
            is LegalAction.ChooseThrowReroll    -> emptyList<Schema15Factor>() to listOf(actorOf(playerId))
            is LegalAction.ChooseBlockFaceOrder -> error("ChooseBlockFaceOrder must be pre-settled")
            is LegalAction.AdjustThrowByDoOver  -> emptyList<Schema15Factor>() to listOf(actorOf(playerId), pitchOf(destination))
            is LegalAction.AdjustBlockByDoOver  -> emptyList<Schema15Factor>() to listOf(actorOf(playerId), pitchOf(destination))
            is LegalAction.AdjustArmourByDoOver -> emptyList<Schema15Factor>() to listOf(actorOf(targetId))
            is LegalAction.QuickReleaseThrow    -> listOf(pitchOf(targetCoordinate)) to listOf(actorOf(playerId))
            is LegalAction.PlayBonusCard        -> emptyList<Schema15Factor>() to emptyList()
            is LegalAction.ClaimChallenge       -> listOf(Schema15Factor.ChallengeSlot(slotIndex)) to emptyList()
            is LegalAction.ReplaceChallenge     -> listOf(Schema15Factor.ChallengeSlot(slotIndex)) to emptyList()
        }
    }

    private fun preferUniqueHarnessKeys(choices: List<Schema15LegalChoice>): List<Schema15LegalChoice> {
        val best = linkedMapOf<List<Int>, Schema15LegalChoice>()
        for (choice in choices) {
            val key = Schema15LegalPacker.harnessFactorKey(choice)
            val prior = best[key]
            if (prior == null || attachedBonusRank(choice) < attachedBonusRank(prior)) {
                best[key] = choice
            }
        }
        return best.values.toList()
    }

    private fun attachedBonusRank(choice: Schema15LegalChoice): Int =
        choice.sugarFactors.filterIsInstance<Schema15Factor.AttachedBonus>().minOfOrNull { it.index } ?: -1

    private fun checkDistinctHarnessFactorKeys(choices: List<Schema15LegalChoice>) {
        val seen = mutableMapOf<List<Int>, Schema15LegalChoice>()
        for (choice in choices) {
            val key = Schema15LegalPacker.harnessFactorKey(choice)
            val prior = seen.put(key, choice)
            if (prior != null) {
                error(
                    "Duplicate schema-15 harness factor key $key for kinds ${prior.kind} and ${choice.kind} — " + "legalActionIds ${prior.legalActionId} vs ${choice.legalActionId}",)
            }
        }
    }
}
