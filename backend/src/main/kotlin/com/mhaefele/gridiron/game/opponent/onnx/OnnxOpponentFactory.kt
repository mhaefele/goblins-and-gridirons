package com.mhaefele.gridiron.game.opponent.onnx

import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.mapping.AiGameStateMapper
import com.mhaefele.gridiron.api.ai.mapping.AiLegalChoiceMapper
import com.mhaefele.gridiron.api.ai.mapping.AiLegalPacker
import com.mhaefele.gridiron.api.ai.mapping.AiObsPacker
import com.mhaefele.gridiron.api.ai.mapping.AiStepMapper
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.dto.bestEnforcerOrder
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.onnx.schema15.ConditionalFactorPicker
import com.mhaefele.gridiron.game.opponent.onnx.schema15.Schema15Layout
import com.mhaefele.gridiron.game.opponent.onnx.schema15.Schema15LegalChoiceMapper
import com.mhaefele.gridiron.game.opponent.onnx.schema15.Schema15LegalPacker
import com.mhaefele.gridiron.game.opponent.onnx.schema15.Schema15OnnxSession
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import org.springframework.stereotype.Component

/**
 * Builds per-profile [OpponentStrategy] instances over classpath ``ai-opponent/{profile}/``.
 *
 * Dispatches by manifest `schemaVersion`: 15 → factor-logit side package; 16 → legal-row path.
 *
 * SCHEMA15_DELETE: drop schema15 ctor deps, imports, [Schema15ProfileStrategy], and the
 * `Schema15Layout.SCHEMA_VERSION` arm — keep schema-16 only. Grep SCHEMA15_DELETE.
 */
@Component
class OnnxOpponentFactory(
    private val steps: AiStepMapper,
    private val legalChoices: AiLegalChoiceMapper,
    private val legalPacker: AiLegalPacker,
    private val obsPacker: AiObsPacker,
    private val gameState: AiGameStateMapper, // SCHEMA15_DELETE: only used by Schema15ProfileStrategy
    private val policy: OnnxPolicySession,
    private val schema15: Schema15OnnxSession, // SCHEMA15_DELETE
    private val schema15Choices: Schema15LegalChoiceMapper, // SCHEMA15_DELETE
    private val factorPicker: ConditionalFactorPicker, // SCHEMA15_DELETE
) {
    fun forProfile(profile: OnnxOpponentProfile): OpponentStrategy {
        val version = policy.manifestSchemaVersion(profile)
            ?: error("ONNX profile '${profile.id}' artifacts missing")
        return when (version) {
            // SCHEMA15_DELETE: remove this arm
            Schema15Layout.SCHEMA_VERSION -> {
                schema15.ensureLoaded(profile)
                Schema15ProfileStrategy(profile)
            }
            AiObsLayout.SCHEMA_VERSION    -> {
                policy.ensureLoaded(profile)
                Schema16ProfileStrategy(profile)
            }
            else                          ->
                error(
                    "ONNX opponent '${profile.id}' file is an old or unsupported version (schemaVersion=$version) and cannot be used",
                )
        }
    }

    /** Wire / request id → enum; unknown ids fail fast. */
    fun forProfile(profileId: String): OpponentStrategy {
        val profile = OnnxOpponentProfile.fromId(profileId) ?: error("Unknown onnx profile '$profileId'")
        return forProfile(profile)
    }

    private inner class Schema16ProfileStrategy(
        private val profile: OnnxOpponentProfile,
    ) : OpponentStrategy {
        override fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction {
            require(legal.isNotEmpty()) { "No legal actions to choose from" }
            settleEnforcerOrder(legal)?.let { return it }
            val perspective = state.actingSeat()
            steps.checkLiveAiLegals(state, legal)
            val choices = legalChoices.toLegalChoices(legal, perspective, state)
            check(choices.isNotEmpty()) { "AI legal choices empty after filter (gui legal=${legal.size})" }
            val packedMoves = legalPacker.legalMoves(choices, state, perspective).map { it.toIntArray() }
            val obs = obsPacker.pack(steps.toAiStepDto(state, legal, perspective).policy.obs)
            val row = policy.chooseRow(profile, obs, packedMoves)
            val chosenId = choices[row].action.id
            return legal.firstOrNull { it.id == chosenId } ?: error("Chosen legalActionId=$chosenId not in GUI legal set")
        }
    }

    /** SCHEMA15_DELETE: remove this strategy class. */
    private inner class Schema15ProfileStrategy(
        private val profile: OnnxOpponentProfile,
    ) : OpponentStrategy {
        override fun chooseAction(state: MatchState, legal: List<LegalAction>): LegalAction {
            require(legal.isNotEmpty()) { "No legal actions to choose from" }
            settleEnforcerOrder(legal)?.let { return it }
            val perspective = state.actingSeat()
            steps.checkLiveAiLegals(state, legal)
            val choices = schema15Choices.toLegalChoices(legal, perspective)
            check(choices.isNotEmpty()) { "schema-15 AI legal choices empty after filter (gui legal=${legal.size})" }
            val packedMoves = Schema15LegalPacker.legalMoves(choices)
            val obs = obsPacker.pack(gameState.toAiNestedObsDto(state, perspective, legal))
            val logits = schema15.factorLogits(profile, obs)
            val factors = factorPicker.pickDeterministic(packedMoves, logits)
            val row = factorPicker.resolveRowIndex(packedMoves, factors)
            val chosenId = choices[row].legalActionId
            return legal.firstOrNull { it.id == chosenId } ?: error("Chosen legalActionId=$chosenId not in GUI legal set")
        }
    }

    /** Pre-settle Enforcer order (schema 15 has no face-order kind; shared convenience for 16). */
    private fun settleEnforcerOrder(legal: List<LegalAction>): LegalAction? {
        val orders = legal.filterIsInstance<LegalAction.ChooseBlockFaceOrder>()
        if (orders.isEmpty()) return null
        val preferred = bestEnforcerOrder(orders.first().orderedFaces)
        return orders.firstOrNull { it.orderedFaces == preferred } ?: orders.first()
    }
}
