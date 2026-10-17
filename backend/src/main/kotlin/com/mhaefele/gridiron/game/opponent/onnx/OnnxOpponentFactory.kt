package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.ai.mapping.AiLegalChoiceMapper
import com.mhaefele.gridiron.api.ai.mapping.AiLegalPacker
import com.mhaefele.gridiron.api.ai.mapping.AiObsPacker
import com.mhaefele.gridiron.api.ai.mapping.AiStepMapper
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import org.springframework.stereotype.Component

/**
 * Builds per-profile [OpponentStrategy] instances over classpath ``ai-opponent/{profile}/``.
 */
@Component
class OnnxOpponentFactory(
    private val steps: AiStepMapper,
    private val legalChoices: AiLegalChoiceMapper,
    private val legalPacker: AiLegalPacker,
    private val obsPacker: AiObsPacker,
    private val policy: OnnxPolicySession,
    private val factorPicker: ConditionalFactorPicker,
) {
    fun forProfile(profile: OnnxOpponentProfile): OpponentStrategy {
        policy.ensureLoaded(profile)
        return ProfileStrategy(profile)
    }

    /** Wire / request id → enum; unknown ids fail fast. */
    fun forProfile(profileId: String): OpponentStrategy {
        val profile = OnnxOpponentProfile.fromId(profileId) ?: error("Unknown onnx profile '$profileId'")
        return forProfile(profile)
    }

    private inner class ProfileStrategy(
        private val profile: OnnxOpponentProfile,
    ) : OpponentStrategy {
        override fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction {
            require(legal.isNotEmpty()) { "No legal actions to choose from" }
            val perspective = state.actingSeat()
            steps.checkLiveAiLegals(state, legal)
            val choices = legalChoices.toLegalChoices(legal, perspective, state)
            check(choices.isNotEmpty()) { "AI legal choices empty after filter (gui legal=${legal.size})" }
            val packedMoves = legalPacker.legalMoves(choices).map { it.toIntArray() }
            val obs = obsPacker.pack(steps.toAiStepDto(state, legal, perspective).policy.obs)
            val logits = policy.factorLogits(profile, obs)
            val factors = factorPicker.pickDeterministic(packedMoves, logits)
            val row = factorPicker.resolveRowIndex(packedMoves, factors)
            val chosenId = choices[row].legalActionId
            return legal.firstOrNull { it.id == chosenId } ?: error("Chosen legalActionId=$chosenId not in GUI legal set")
        }
    }
}
