package com.mhaefele.gridiron.game.opponent.lookahead

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import kotlin.math.abs

/**
 * Shallow challenge pursuit for the scripted bot (D1.H).
 * Scores / filters legal moves toward high-point actives — not a planner.
 *
 * [LookaheadOpponentStrategy] does **not** short-circuit on [biasedLegal]; search uses
 * [com.mhaefele.gridiron.game.cards.ActionAttachedClaims] annotations instead.
 */
object ChallengePursuit {
    /** Prefer playing a useful own-turn bonus; null → caller may Skip. */
    fun pickPlayBonus(legal: List<LegalAction>): LegalAction.PlayBonusCard? {
        val plays = legal.filterIsInstance<LegalAction.PlayBonusCard>()
        if (plays.isEmpty()) return null
        val preferred = listOf(
            BonusPlay.`Gang Up`,
            BonusPlay.`Hold the Line`,
            BonusPlay.`Extra Effort`,
            BonusPlay.Sprint,
            BonusPlay.Weave,
            BonusPlay.Press,
            BonusPlay.Underdog,
            BonusPlay.Momentum,
            BonusPlay.`All-In Tackle`,
            BonusPlay.`Spiked Gauntlets`,
            BonusPlay.`Back on Your Feet`,
            BonusPlay.`Fresh Legs`,
            BonusPlay.`Ice Cold`,
            BonusPlay.`Dead Eye`,
            BonusPlay.`Hail Mary Pass`,
        )
        for (bonus in preferred) {
            plays.firstOrNull { it.bonusPlay == bonus }?.let { return it }
        }
        // Interrupts / unknown — decline (caller Skips).
        return null
    }

    /** Do-Over held-dice adjust — prefer over Skip when offered. */
    fun pickDoOverAdjust(legal: List<LegalAction>): LegalAction? =
        legal.filterIsInstance<LegalAction.AdjustThrowByDoOver>().firstOrNull()
            ?: legal.filterIsInstance<LegalAction.AdjustBlockByDoOver>().firstOrNull()
            ?: legal.filterIsInstance<LegalAction.AdjustArmourByDoOver>().firstOrNull()

    /** OS / EP keep-vs-reroll on a held check — null when not a reroll window. */
    fun pickHeldCheckReroll(state: MatchState, legal: List<LegalAction>): LegalAction? {
        legal.filterIsInstance<LegalAction.ChooseBlockReroll>().firstOrNull()?.let { sample ->
            val shouldReroll = DoOverRoll.mayImproveBlock(state.board, sample.playerId, sample.faces)
            return legal.filterIsInstance<LegalAction.ChooseBlockReroll>().firstOrNull { it.reroll == shouldReroll }
        }
        legal.filterIsInstance<LegalAction.ChooseThrowReroll>().firstOrNull { it.reroll }?.let { return it }
        return null
    }

    /** Highest-point eligible claim; ties → lowest slot (old auto-pick order). */
    fun pickClaimChallenge(legal: List<LegalAction>): LegalAction.ClaimChallenge? =
        legal.filterIsInstance<LegalAction.ClaimChallenge>()
            .maxWithOrNull(compareBy<LegalAction.ClaimChallenge> { it.points }.thenBy { -it.slotIndex })

    /** Forced replace — discard lowest-point active (ties → lowest slot). */
    fun pickReplaceChallenge(legal: List<LegalAction>): LegalAction.ReplaceChallenge? =
        legal.filterIsInstance<LegalAction.ReplaceChallenge>()
            .minWithOrNull(
                compareBy<LegalAction.ReplaceChallenge> { it.claimCondition.points }
                    .thenBy { it.slotIndex },
            )

    /**
     * When an active challenge suggests a clear action family, return that subset of [legal].
     * Null means fall through to ball-chase heuristics.
     */
    fun biasedLegal(state: MatchState, legal: List<LegalAction>): List<LegalAction>? {
        val actives = activeConditionsByPoints(state)
        if (actives.isEmpty()) return null

        for ((kind, _) in actives) {
            when (kind) {
                ChallengeClaimCondition.KEEP_RUNNING -> {
                    val used = state.currentTurnState.actionsThisTurn.map { it.playerId }.toSet()
                    val runs = legal.filter {
                        (it is LegalAction.Run || it is LegalAction.DeclareRun) && (it as LegalAction.PlayerLegalAction).playerId !in used
                    }
                    if (runs.isNotEmpty()) return runs
                }
                ChallengeClaimCondition.CONNECT,
                ChallengeClaimCondition.THREAD_THE_NEEDLE,
                                                         -> {
                    val throws = legal.filterIsInstance<LegalAction.Throw>().filter { claimsKind(it, kind) }
                    if (throws.isNotEmpty()) return throws
                    val anyThrows = legal.filterIsInstance<LegalAction.Throw>()
                    if (anyThrows.isNotEmpty()) return anyThrows
                }
                ChallengeClaimCondition.DROP_THEM,
                ChallengeClaimCondition.STRIP_IT,
                                                         -> {
                    val blocks = legal.filterIsInstance<LegalAction.Block>()
                    val annotatedBlocks = blocks.filter { claimsKind(it, kind) }
                    if (annotatedBlocks.isNotEmpty()) return annotatedBlocks
                    val fouls = legal.filterIsInstance<LegalAction.Foul>().filter { claimsKind(it, kind) }
                    if (fouls.isNotEmpty()) return fouls
                    if (blocks.isNotEmpty()) return blocks
                }
                ChallengeClaimCondition.SEND_THEM_OFF -> {
                    val fouls = legal.filterIsInstance<LegalAction.Foul>()
                    val annotatedFouls = fouls.filter { claimsKind(it, kind) }
                    if (annotatedFouls.isNotEmpty()) return annotatedFouls
                    if (fouls.isNotEmpty()) return fouls
                    val blocks = legal.filterIsInstance<LegalAction.Block>()
                    val annotatedBlocks = blocks.filter { claimsKind(it, kind) }
                    if (annotatedBlocks.isNotEmpty()) return annotatedBlocks
                    if (blocks.isNotEmpty()) return blocks
                }
                ChallengeClaimCondition.GRAB_IT -> {
                    val pickup = legal.filterIsInstance<LegalAction.Run>().filter { it.ballEncounter == BallEncounter.PICK_UP }
                    val annotatedPickup = pickup.filter { claimsKind(it, kind) }
                    if (annotatedPickup.isNotEmpty()) return listOf(annotatedPickup.minBy { it.path.steps })
                    if (pickup.isNotEmpty()) return pickup
                    val towardBall = legal.filter {
                        it is LegalAction.PathMovement ||
                            it is LegalAction.DeclareRun ||
                            it is LegalAction.Block ||
                            it is LegalAction.Mark ||
                            it is LegalAction.StandUp ||
                            it is LegalAction.Throw
                    }
                    if (towardBall.isNotEmpty()) return towardBall
                }
                ChallengeClaimCondition.LOCK_THEM_DOWN -> {
                    val marks = legal.filterIsInstance<LegalAction.Mark>()
                    val annotated = marks.filter { claimsKind(it, kind) }
                    if (annotated.isNotEmpty()) return annotated
                    if (marks.isNotEmpty()) return marks
                }
                ChallengeClaimCondition.HUDDLE_UP,
                ChallengeClaimCondition.FAN_OUT,
                ChallengeClaimCondition.HOLD_THE_PIT,
                ChallengeClaimCondition.PUSH_FORWARD,
                ChallengeClaimCondition.PLAY_TO_THE_STANDS,
                                                               -> {
                    val annotated = legal.filter { claimsKind(it, kind) }
                    if (annotated.isNotEmpty()) return annotated
                }
                ChallengeClaimCondition.ALL_HANDS         -> {
                    val log = state.currentTurnState.actionsThisTurn
                    val typesDone = log.map { it.actionType }.toSet()
                    val playersDone = log.map { it.playerId }.toSet()
                    val fresh = legal.filter { action ->
                        val player = (action as? LegalAction.PlayerLegalAction)?.playerId ?: return@filter false
                        if (player in playersDone) return@filter false
                        when (action) {
                            is LegalAction.Mark  -> ActionType.MARK !in typesDone
                            is LegalAction.Block -> ActionType.BLOCK !in typesDone
                            is LegalAction.Run   -> ActionType.RUN !in typesDone
                            else                 -> false
                        }
                    }
                    if (fresh.isNotEmpty()) return fresh
                }
                ChallengeClaimCondition.STAR_TURN -> {
                    val log = state.currentTurnState.actionsThisTurn.filter { !it.isFree }
                    val configuredStar = state.trainingEpisode?.starTurnStarId
                    val starId = configuredStar ?: log.map { it.playerId }.distinct().singleOrNull()
                    val typesDone = if (starId != null) log.filter { it.playerId == starId }.map { it.actionType }.toSet() else emptySet()
                    if (log.isEmpty()) {
                        configuredStar?.let { star ->
                            val opening = legal.filterIsInstance<LegalAction.PlayerLegalAction>().filter { it.playerId == star }
                            starTurnFresh(opening, typesDone, state)?.let { return it }
                        }
                        val byPlayer = legal.filterIsInstance<LegalAction.PlayerLegalAction>().groupBy { it.playerId }
                        val trioStar = byPlayer.filter { (_, moves) ->
                            val types = moves.map { it.actionType }.toSet()
                            ActionType.RUN in types && ActionType.MARK in types && ActionType.BLOCK in types
                        }
                        if (trioStar.size == 1) {
                            val opening = trioStar.values.first()
                            if (opening.isNotEmpty()) return opening
                        }
                        val candidates = byPlayer.maxByOrNull { (_, moves) -> moves.map { it.actionType }.distinct().size }
                        candidates?.let { (openingStar, _) ->
                            val opening = legal.filterIsInstance<LegalAction.PlayerLegalAction>().filter { it.playerId == openingStar }
                            if (opening.isNotEmpty()) return opening
                        }
                    } else if (starId != null) {
                        val fresh = legal.filterIsInstance<LegalAction.PlayerLegalAction>().filter { action ->
                            action.playerId == starId && action.actionType in STAR_TURN_ACTION_TYPES && action.actionType !in typesDone
                        }
                        starTurnFresh(fresh, typesDone, state)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun claimsKind(action: LegalAction, kind: ChallengeClaimCondition): Boolean =
        action.potentialChallengeClaims.any { it.claimCondition == kind }

    private fun starTurnFresh(fresh: List<LegalAction.PlayerLegalAction>, typesDone: Set<ActionType>, state: MatchState): List<LegalAction>? {
        if (fresh.isEmpty()) return null
        val nextType = STAR_TURN_PREFERRED_ORDER.firstOrNull { it !in typesDone && fresh.any { action -> action.actionType == it } }
        val picked = if (nextType != null) fresh.filter { it.actionType == nextType } else fresh
        if (nextType != ActionType.RUN) return picked
        val runs = picked.filterIsInstance<LegalAction.Run>()
        if (runs.size <= 1) return picked
        val team = state.currentTurnState.activeCoach
        val opponentCoords = state.board.players.values
            .filter { it.team != team && it.status == PlayerStatus.STANDING }
            .map { state.board.findSquare(it.id).coordinate }
        if (opponentCoords.isEmpty()) return picked
        val best = runs.minByOrNull { run ->
            val end = run.path.squares.last()
            opponentCoords.minOf { opp -> abs(end.x - opp.x) + abs(end.y - opp.y) }
        } ?: return picked
        return listOf(best)
    }

    private fun activeConditionsByPoints(state: MatchState): List<Pair<ChallengeClaimCondition, Int>> =
        state.cards.activeCards()
            .map { it.challenge to it.points }
            .sortedByDescending { it.second }

    private val STAR_TURN_ACTION_TYPES = setOf(
        ActionType.RUN,
        ActionType.MARK,
        ActionType.BLOCK,
        ActionType.STAND_UP,
        ActionType.THROW,
        ActionType.THROW_TEAMMATE,
        ActionType.SIDESTEP,
        ActionType.FOUL,
    )

    /** Block/Mark before Run so adjacency to the target is not spent first. */
    private val STAR_TURN_PREFERRED_ORDER = listOf(
        ActionType.BLOCK,
        ActionType.MARK,
        ActionType.RUN,
        ActionType.STAND_UP,
        ActionType.SIDESTEP,
        ActionType.FOUL,
        ActionType.THROW,
        ActionType.THROW_TEAMMATE,
    )
}
