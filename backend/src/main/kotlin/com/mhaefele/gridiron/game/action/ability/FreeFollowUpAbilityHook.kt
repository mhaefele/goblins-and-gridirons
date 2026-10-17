package com.mhaefele.gridiron.game.action.ability

import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.legalmoves.adjacentProneAllies
import com.mhaefele.gridiron.game.legalmoves.hasMarkableBlockTarget
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.OfferUndying
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/** Query result for mid-action ability follow-ups — not a [PendingDecision]; Ongoing* stages consume this. */
data class AbilityFollowUpOffer(
    val playerId: PlayerId,
    val ability: PlayerAbility,
)

/**
 * Offers free follow-up grants after regular player actions (and Undying at pre-turn).
 * Mid-action offers are [AbilityFollowUpOffer] DTOs folded into Mark/Block/Run stages.
 * Only [OfferUndying] is queued as a pending (with remaining players on it).
 */
@Component
class FreeFollowUpAbilityHook {

    /**
     * @param effectiveActionType logical action completed (may differ from the submitted command,
     * e.g. Skip on RunContinuation still counts as RUN for Pass on the Run).
     */
    fun offersAfterResolved(playerId: PlayerId, effectiveActionType: ActionType, state: MatchState): List<AbilityFollowUpOffer> {
        val board = state.board
        val player = board.player(playerId)
        val grants = mutableListOf<AbilityFollowUpOffer>()

        when (effectiveActionType) {
            ActionType.MARK  -> {
                if (player.status == PlayerStatus.STANDING && player.hasAbility(PlayerAbility.`Relentless After Mark`) && hasMarkableBlockTarget(player, board)) {
                    grants.add(AbilityFollowUpOffer(player.id, PlayerAbility.`Relentless After Mark`))
                }
                if (player.status == PlayerStatus.STANDING && player.hasAbility(PlayerAbility.Charge) && hasMarkableBlockTarget(player, board)) {
                    val sequence = state.currentTurnState.actionSequenceFor(player.id)
                    // Mid-Mark offer: MARK is not in the sequence yet (recorded at COMPLETE). After COMPLETE, last is MARK.
                    val runThenThisMark = when (sequence.lastOrNull()) {
                        ActionType.RUN  -> true
                        ActionType.MARK -> sequence.getOrNull(sequence.lastIndex - 1) == ActionType.RUN
                        else            -> false
                    }
                    if (runThenThisMark) {
                        grants.add(AbilityFollowUpOffer(player.id, PlayerAbility.Charge))
                    }
                }
            }
            ActionType.BLOCK -> {
                if (player.hasAbility(PlayerAbility.`Relentless After Block`) && hasMarkableBlockTarget(player, board)
                    && player.status == PlayerStatus.STANDING) {
                    grants.add(AbilityFollowUpOffer(player.id, PlayerAbility.`Relentless After Block`))
                }
                if (player.hasAbility(PlayerAbility.`Strike and Fade`) && player.status == PlayerStatus.STANDING) {
                    grants.add(AbilityFollowUpOffer(player.id, PlayerAbility.`Strike and Fade`))
                }
            }
            ActionType.RUN   -> {
                if (player.hasAbility(PlayerAbility.`Pass on the Run`) && player.holdsBall()) {
                    grants.add(AbilityFollowUpOffer(player.id, PlayerAbility.`Pass on the Run`))
                }
            }
            ActionType.THROW -> {
                board.players.values.forEach { catcher ->
                    if (catcher.team == player.team && catcher.hasAbility(PlayerAbility.`Catch and Go`) && catcher.holdsBall() && standingStatus(catcher, board) == StandingStatus.OPEN) {
                        grants.add(AbilityFollowUpOffer(catcher.id, PlayerAbility.`Catch and Go`))
                    }
                }
            }
            else             -> Unit
        }

        if (player.hasAbility(PlayerAbility.Rally) && player.status == PlayerStatus.STANDING) {
            for (ally in adjacentProneAllies(player, board)) {
                grants.add(AbilityFollowUpOffer(ally.id, PlayerAbility.Rally))
            }
        }

        return grants
    }

    /**
     * One [OfferUndying] covering every eligible prone Undying player this activation.
     * [OfferUndying.remainingPlayerIds] holds the rest; the pending stays until that list drains.
     */
    fun pendingAtPreTurn(state: MatchState, team: TeamSide): OfferUndying? {
        val eligible = eligiblePreTurnUndying(state, team)
        if (eligible.isEmpty()) return null
        return OfferUndying(
            playerId = eligible.first(),
            remainingPlayerIds = eligible.drop(1),
        )
    }

    /**
     * After resolving [current], return the next still-eligible grant or null when the list is drained.
     * Eligibility is re-checked here (board status / ability) — not in MatchMutator.
     */
    fun nextPreTurnUndying(state: MatchState, current: OfferUndying): OfferUndying? {
        val kept = current.remainingPlayerIds.filter { playerId ->
            val player = state.board.players[playerId] ?: return@filter false
            player.status == PlayerStatus.PRONE && player.hasAbility(PlayerAbility.Undying)
        }
        if (kept.isEmpty()) return null
        return OfferUndying(
            playerId = kept.first(),
            remainingPlayerIds = kept.drop(1),
        )
    }

    private fun eligiblePreTurnUndying(state: MatchState, team: TeamSide): List<PlayerId> =
        state.board.players.values
            .filter { it.team == team && it.status == PlayerStatus.PRONE && it.hasAbility(PlayerAbility.Undying) }
            .map { it.id }
}
