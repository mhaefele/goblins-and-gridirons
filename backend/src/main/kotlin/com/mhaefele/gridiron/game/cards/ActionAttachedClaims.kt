package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.ClaimCertainty
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.PotentialChallengeClaim
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.pending.OfferUndying
import com.mhaefele.gridiron.model.TurnAction
import com.mhaefele.gridiron.model.TurnState
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange

/**
 * Pre-resolution claim hints on legal player actions (D1.F.4).
 * Uses optimistic facts where dice decide, then [ClaimEligibility.meets].
 */
object ActionAttachedClaims {
    fun activeClaimConditions(cards: CardState): List<ChallengeClaimCondition> =
        cards.activeChallengeClaims().distinct()

    fun annotatePotentialClaims(actions: List<LegalAction>, state: MatchState): List<LegalAction> {
        val turn = state.currentTurnState
        val claimsEnabled = !(state.progress.turnNumber == 1 && turn.activeCoach == state.config.coachA)
        if (!claimsEnabled) return actions
        val isFree = isFreePlayerActionContext(turn)
        return annotatePotentialClaims(
            actions = actions,
            board = state.board,
            turn = turn,
            cards = state.cards,
            coach = turn.activeCoach,
            activeClaims = activeClaimConditions(state.cards),
            isFreeAction = isFree,
        )
    }

    fun annotatePotentialClaims(
        actions: List<LegalAction>,
        board: Board,
        turn: TurnState,
        cards: CardState,
        coach: TeamSide,
        activeClaims: List<ChallengeClaimCondition>,
        isFreeAction: Boolean = false,
    ): List<LegalAction> {
        if (activeClaims.isEmpty()) return actions
        val claimMetas = activeClaims.map { ActiveClaimMeta(it, certaintyFor(it)) }
        val ownEndZoneY = if (ChallengeClaimCondition.PUSH_FORWARD in activeClaims) endZoneY(board, coach) else null
        // Paths to the same landing share projected geometry / TD / Move-the-Ball distance.
        val pathClaimCache = HashMap<PathClaimKey, List<PotentialChallengeClaim>>()
        return actions.map { action ->
            val claims = when (action) {
                is LegalAction.PathMovement -> {
                    val key = PathClaimKey(action.playerId, action.actionType, action.path.squares.last())
                    pathClaimCache.getOrPut(key) { potentialClaimsFor(action, board, turn, cards, coach, isFreeAction, claimMetas, ownEndZoneY) }
                }
                else                        -> potentialClaimsFor(action, board, turn, cards, coach, isFreeAction, claimMetas, ownEndZoneY)
            }
            withPotentialClaims(action, claims)
        }
    }

    fun withPotentialClaims(action: LegalAction, claims: List<PotentialChallengeClaim>): LegalAction {
        if (claims.isEmpty()) return action
        return when (action) {
            is LegalAction.Run                 -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.Mark                -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.Sidestep            -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.Throw               -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.ThrowTeammateSelect -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.ThrowTeammate       -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.Block               -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.Foul                -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.DeclareRun          -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.StandUp             -> action.copy(potentialChallengeClaims = claims)
            is LegalAction.Reserves            -> action.copy(potentialChallengeClaims = claims)
            else                               -> action
        }
    }

    private fun potentialClaimsFor(
        action: LegalAction,
        board: Board,
        turn: TurnState,
        cards: CardState,
        coach: TeamSide,
        isFreeAction: Boolean,
        claimMetas: List<ActiveClaimMeta>,
        ownEndZoneY: Int?,
    ): List<PotentialChallengeClaim> {
        if (action !is LegalAction.BasicPlayerAction) return emptyList()
        // Landing squares are numerous — claim hints stay on select / ball throws.
        if (action is LegalAction.ThrowTeammate) return emptyList()

        val applicable = claimMetas.filter { it.condition.mayApplyTo(action.actionType) }
        if (applicable.isEmpty()) return emptyList()

        val needsProjectedBoard = applicable.any { it.condition.needsProjectedBoard() }
        val needsTouchdown = applicable.any { it.condition.needsTouchdownPrediction() }
        val after = if (needsProjectedBoard || needsTouchdown) projectedBoard(action, board) else board
        // Same Open-in-opp-EZ gate as TouchdownChecker (Mark into EZ stays Marked → no TD / Play to the Stands).
        val touchdownScored = needsTouchdown && predictsTouchdown(action, board, after, coach)
        val predictedFacts = predictClaimStats(action, board, coach, isFreeAction, touchdownScored, ownEndZoneY)
        val turnStats = TurnClaimStats(
            actionsThisTurn = turn.actionsThisTurn + TurnAction(action.playerId, action.actionType, isFreeAction),
            ballsPossessedAtTurnStart = cards.ballsPossessedAtTurnStart,
        )
        val boardForMeets = if (needsProjectedBoard) after else board

        return applicable.mapNotNull { meta ->
            if (!ClaimEligibility.meets(meta.condition, predictedFacts, turnStats, boardForMeets)) return@mapNotNull null
            meta.toHint()
        }
    }

    private fun predictClaimStats(
        action: LegalAction.BasicPlayerAction,
        board: Board, coach: TeamSide,
        isFreeAction: Boolean,
        touchdownScored: Boolean,
        ownEndZoneY: Int?,
    ): ActionClaimFacts {
        val playerId = action.playerId
        val actionType = action.actionType
        val actingBefore = board.player(playerId)

        val endCoord = when (action) {
            is LegalAction.PathMovement                       -> action.path.squares.last()
            is LegalAction.Reserves                           -> action.placement
            is LegalAction.StandUp, is LegalAction.DeclareRun -> board.findSquare(playerId).coordinate
            is LegalAction.Block, is LegalAction.Foul, is LegalAction.Throw,
            is LegalAction.ThrowTeammateSelect, is LegalAction.ThrowTeammate,
                                                              ->
                board.findSquare(playerId).coordinate
        }

        val endedOnHazard = actingBefore.status == PlayerStatus.STANDING && runCatching { board.squareAt(endCoord).isTrapdoor() }.getOrDefault(false)

        // Paths stop on a loose ball, so pickup (if any) is only on the landing square.
        val ballsGained = if (action is LegalAction.PathMovement && action.ballEncounter == BallEncounter.PICK_UP) {
            setOf(board.squareAt(endCoord).looseBallId!!)
        } else {
            emptySet()
        }

        val foulOrBlockTarget = when (action) {
            is LegalAction.Block -> board.squareAt(action.destination).playerId?.let { board.players[it] }
            is LegalAction.Foul  -> board.squareAt(action.destination).playerId?.let { board.players[it] }
            else                 -> null
        }
        val oppositionBallsLost = foulOrBlockTarget
            ?.takeIf { it.team != coach && it.heldBallId != null }
            ?.heldBallId
            ?.let { setOf(it) }
            .orEmpty()

        val pushForwardDistance = if (ownEndZoneY == null) {
            null
        } else {
            ballMovePrediction(action, board, playerId, actingBefore.heldBallId, ownEndZoneY)
                .takeUnless { touchdownScored }
        }

        val throwTarget = when (action) {
            is LegalAction.Throw         -> action.target
            is LegalAction.ThrowTeammate -> action.target
            else                         -> null
        }
        val throwWasHandOff = throwTarget?.range == ThrowRange.HAND_OFF
        val throwSucceeded = (action is LegalAction.Throw || action is LegalAction.ThrowTeammate) && !throwWasHandOff
        // Optimistic negative modifier when a throw could still be "risky".
        val throwModifier = when {
            throwTarget == null || throwWasHandOff -> 0
            throwTarget.obstructed                 -> -1
            else                                   -> -1
        }

        return ActionClaimFacts(
            team = coach,
            actionType = actionType,
            actingPlayerId = playerId,
            isFreeAction = isFreeAction,
            ballsGained = ballsGained,
            oppositionBallsLost = oppositionBallsLost,
            pushForwardDistance = pushForwardDistance,
            knockDownOccurred = action is LegalAction.Block,
            injuryOccurred = action is LegalAction.Block || action is LegalAction.Foul,
            touchdownScored = touchdownScored,
            throwSucceeded = throwSucceeded,
            throwWasHandOff = throwWasHandOff,
            throwModifier = throwModifier,
            endedOnHazard = endedOnHazard,
        )
    }

    private fun predictsTouchdown(
        action: LegalAction.BasicPlayerAction,
        board: Board,
        after: Board,
        coach: TeamSide,
    ): Boolean {
        val player = board.player(action.playerId)
        if (!player.holdsBall() || player.status != PlayerStatus.STANDING) return false
        val endCoord = when (action) {
            is LegalAction.PathMovement -> action.path.squares.last()
            is LegalAction.Reserves     -> action.placement
            else                        -> return false
        }
        if (!after.squareAt(endCoord).isEndZone(coach.opposite())) return false
        return standingStatus(after.player(action.playerId), after) == StandingStatus.OPEN
    }

    private fun projectedBoard(action: LegalAction.BasicPlayerAction, board: Board): Board =
        when (action) {
            is LegalAction.PathMovement -> {
                val from = board.findSquare(action.playerId).coordinate
                val to = action.path.squares.last()
                if (from == to) board
                else BoardMutator(board).also { it.playerMoved(action.playerId, from, to) }.board
            }
            is LegalAction.Reserves     ->
                BoardMutator(board).also { it.playerDeployedFromReserves(action.playerId, action.placement) }.board
            is LegalAction.StandUp      ->
                BoardMutator(board).also { it.playerStoodUp(action.playerId) }.board
            else                        -> board
        }

    private fun ballMovePrediction(
        action: LegalAction,
        board: Board,
        playerId: PlayerId,
        heldAtStart: BallId?,
        ownEndZoneY: Int,
    ): Int? = when (action) {
        is LegalAction.Throw         -> {
            val from = board.findSquare(playerId).coordinate
            distanceGained(ownEndZoneY, from, action.target.destination)
        }
        is LegalAction.ThrowTeammate -> {
            val teammate = board.players.getValue(action.teammateId)
            if (!teammate.holdsBall()) null
            else distanceGained(ownEndZoneY, board.findSquare(action.teammateId).coordinate, action.target.destination)
        }
        is LegalAction.Run           -> {
            if (heldAtStart == null) null
            else distanceGained(ownEndZoneY, action.path.squares.first(), action.path.squares.last())
        }
        else                         -> null
    }

    private fun distanceGained(ownEndZoneY: Int, from: Coordinate, to: Coordinate): Int =
        kotlin.math.abs(to.y - ownEndZoneY) - kotlin.math.abs(from.y - ownEndZoneY)

    private fun endZoneY(board: Board, team: TeamSide): Int =
        board.allSquares().first { it.isEndZone(team) }.coordinate.y

    private fun certaintyFor(condition: ChallengeClaimCondition): ClaimCertainty =
        when (condition) {
            ChallengeClaimCondition.KEEP_RUNNING,
            ChallengeClaimCondition.HUDDLE_UP,
            ChallengeClaimCondition.FAN_OUT,
            ChallengeClaimCondition.LOCK_THEM_DOWN,
            ChallengeClaimCondition.HOLD_THE_PIT,
            ChallengeClaimCondition.ALL_HANDS,
            ChallengeClaimCondition.STAR_TURN,
            -> ClaimCertainty.CERTAIN

            ChallengeClaimCondition.GRAB_IT,
            ChallengeClaimCondition.PUSH_FORWARD,
            ChallengeClaimCondition.PLAY_TO_THE_STANDS,
            ChallengeClaimCondition.CONNECT,
            ChallengeClaimCondition.STRIP_IT,
            ChallengeClaimCondition.THREAD_THE_NEEDLE,
            ChallengeClaimCondition.DROP_THEM,
            ChallengeClaimCondition.SEND_THEM_OFF,
            -> ClaimCertainty.POTENTIAL
        }

    private fun isFreePlayerActionContext(turn: TurnState): Boolean {
        if (turn.currentPlayerAction?.origin == OpenPlayerAction.Origin.FREE) return true
        // Pre-turn Undying has no currentPlayerAction (standalone move) — explicit one-off.
        return turn.pendingDecision is OfferUndying
    }

    /** Geometry after the action — Huddle Up / Fan Out / Lock Them Down. */
    private fun ChallengeClaimCondition.needsProjectedBoard(): Boolean =
        when (this) {
            ChallengeClaimCondition.HUDDLE_UP,
            ChallengeClaimCondition.FAN_OUT,
            ChallengeClaimCondition.LOCK_THEM_DOWN,
                 -> true
            else -> false
        }

    /** Open-in-EZ TD prediction — Play to the Stands, and Push Forward (TD clears possession distance). */
    private fun ChallengeClaimCondition.needsTouchdownPrediction(): Boolean =
        this == ChallengeClaimCondition.PLAY_TO_THE_STANDS || this == ChallengeClaimCondition.PUSH_FORWARD

    /** Skip fact/meets work when this action type cannot contribute to the claim. */
    private fun ChallengeClaimCondition.mayApplyTo(actionType: ActionType): Boolean =
        when (this) {
            ChallengeClaimCondition.KEEP_RUNNING  -> actionType == ActionType.RUN
            ChallengeClaimCondition.DROP_THEM     -> actionType == ActionType.BLOCK
            ChallengeClaimCondition.SEND_THEM_OFF -> actionType == ActionType.BLOCK || actionType == ActionType.FOUL
            ChallengeClaimCondition.CONNECT,
            ChallengeClaimCondition.THREAD_THE_NEEDLE,
                                                           -> actionType == ActionType.THROW || actionType == ActionType.THROW_TEAMMATE
            ChallengeClaimCondition.LOCK_THEM_DOWN     -> actionType == ActionType.MARK
            ChallengeClaimCondition.HUDDLE_UP          -> actionType == ActionType.RUN || actionType == ActionType.MARK || actionType == ActionType.SIDESTEP
            ChallengeClaimCondition.FAN_OUT            -> actionType == ActionType.RUN || actionType == ActionType.SIDESTEP
            ChallengeClaimCondition.STRIP_IT           -> actionType == ActionType.BLOCK || actionType == ActionType.FOUL
            ChallengeClaimCondition.GRAB_IT            -> actionType == ActionType.RUN || actionType == ActionType.MARK || actionType == ActionType.SIDESTEP || actionType == ActionType.RESERVES
            ChallengeClaimCondition.PUSH_FORWARD       -> actionType == ActionType.RUN || actionType == ActionType.THROW || actionType == ActionType.THROW_TEAMMATE
            ChallengeClaimCondition.PLAY_TO_THE_STANDS -> actionType == ActionType.RUN || actionType == ActionType.MARK || actionType == ActionType.SIDESTEP || actionType == ActionType.RESERVES
            ChallengeClaimCondition.HOLD_THE_PIT,
            ChallengeClaimCondition.ALL_HANDS,
            ChallengeClaimCondition.STAR_TURN,
                                                           -> true
        }

    private data class ActiveClaimMeta(
        val condition: ChallengeClaimCondition,
        val certainty: ClaimCertainty,
    ) {
        fun toHint(): PotentialChallengeClaim =
            PotentialChallengeClaim(
                claimCondition = condition,
                challengeTitle = condition.displayName,
                points = condition.points,
                certainty = certainty,
            )
    }

    private data class PathClaimKey(
        val playerId: PlayerId,
        val actionType: ActionType,
        val end: Coordinate,
    )
}
