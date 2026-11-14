package com.mhaefele.gridiron.game.opponent.scripted

import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.dice.dto.bestEnforcerOrder
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.ChallengePursuit
import org.springframework.stereotype.Component

/**
 * Heuristic [OpponentStrategy]: run with the ball, hand off when it helps, chase loose balls, mark/block carriers.
 * If the closest teammate to the ball is prone, stand them up first.
 *
 * Uses one board scan / one BFS per branch — never pathfinds per candidate move.
 */
@Component
class ScriptedOpponentStrategy : OpponentStrategy {

    override fun chooseAction(state: MatchState, legal: List<LegalAction>) =
        choose(state, legal)

    fun choose(state: MatchState, legal: List<LegalAction>): LegalAction {
        require(legal.isNotEmpty()) { "No legal actions to choose from" }

        // Bonus / Do-Over: play a useful own-turn card, otherwise DeclineBonus (interrupts / mid-dice).
        if (legal.any { it is LegalAction.PlayBonusCard || it is LegalAction.AdjustThrowByDoOver || it is LegalAction.AdjustBlockByDoOver || it is LegalAction.AdjustArmourByDoOver }
        ) {
            ChallengePursuit.pickDoOverAdjust(legal)?.let { return it }
            ChallengePursuit.pickPlayBonus(legal)?.let { return it }
            legal.filterIsInstance<LegalAction.DeclineBonus>().firstOrNull()?.let { return it }
        }

        // Claim / replace prompts — pick highest points (claim) or lowest slot (replace = old auto).
        ChallengePursuit.pickClaimChallenge(legal)?.let { return it }
        ChallengePursuit.pickReplaceChallenge(legal)?.let { return it }
        ChallengePursuit.pickHeldCheckReroll(state, legal)?.let { return it }
        legal.filterIsInstance<LegalAction.ChooseBlockFaceOrder>().let { orders ->
            if (orders.isNotEmpty()) {
                val preferred = bestEnforcerOrder(orders.first().orderedFaces)
                return orders.firstOrNull { it.orderedFaces == preferred } ?: orders.first()
            }
        }

        // Throw Team-mate two-step: prefer landing (not cancel); prefer selecting a teammate with the ball.
        legal.filterIsInstance<LegalAction.ThrowTeammate>().maxByOrNull { action ->
            val teammateHolds = state.board.players[action.teammateId]?.heldBallId != null
            val empty = state.board.squareAt(action.target.destination).playerId == null
            (if (teammateHolds) 100 else 0) + (if (empty) 10 else 0) - action.target.destination.y
        }?.let { return it }
        legal.filterIsInstance<LegalAction.ThrowTeammateSelect>().maxByOrNull { action ->
            if (state.board.players[action.teammateId]?.heldBallId != null) 1 else 0
        }?.let { return it }

        val board = state.board
        val team = state.currentTurnState.activeCoach
        val challengeBias = ChallengePursuit.biasedLegal(state, legal)
        val options = (challengeBias ?: narrow(state, legal)).ifEmpty { legal }
        val buckets = bucket(options)

        ballCoordinate(board)?.let { ballAt ->
            standUpClosestIfProne(buckets, board, team, ballAt)?.let { return it }
        }

        looseBallSquare(board)?.let { ball ->
            return chooseLooseBall(state, buckets, board, ball)
        }

        teamBallCarrier(board, team)?.let { carrier ->
            return chooseOffense(buckets, board, team, carrier)
        }

        opponentBallCarrier(board, team)?.let { carrier ->
            return chooseDefense(buckets, board, carrier) ?: fallback(buckets, board)
        }

        return fallback(buckets, board)
    }

    private fun narrow(state: MatchState, legal: List<LegalAction>): List<LegalAction> {
        val board = state.board
        val team = state.currentTurnState.activeCoach

        return if (looseBallSquare(board) != null) {
            legal.filter {
                it is LegalAction.PathMovement ||
                    it is LegalAction.DeclareRun ||
                    (isLastAction(state) && it is LegalAction.Block) ||
                    it is LegalAction.StandUp ||
                    it is LegalAction.Reserves
            }
        } else if (opponentBallCarrier(board, team) != null) {
            legal.filter {
                it is LegalAction.Block ||
                    it is LegalAction.Mark ||
                    it is LegalAction.Run ||
                    it is LegalAction.DeclareRun ||
                    it is LegalAction.StandUp ||
                    it is LegalAction.Reserves
            }
        } else {
            legal
        }
    }

    private fun chooseLooseBall(
        state: MatchState,
        buckets: Buckets,
        board: Board,
        ballSquare: BoardSquare,
    ): LegalAction {
        val pathMoves = buckets.pathMoves
        val team = state.currentTurnState.activeCoach
        val distFromBall = distancesFrom(ballSquare, board)

        blockClosestOpponentToBall(buckets.blocks, board, distFromBall, team)?.let { return it }

        if (isLastAction(state) && !hasPathOntoBall(pathMoves, board)) {
            blockClosestOpponentToBall(buckets.blocks, board, distFromBall, team)?.let { return it }
        }

        pathMoves.filter { picksUpLooseBall(it, board) }
            .minWithOrNull(compareBy<LegalAction.PathMovement> { it.path.steps })
            ?.let { return it }

        pathMoves.filter { bouncesLooseBall(it, board) }
            .minWithOrNull(compareBy<LegalAction.PathMovement> { it.path.steps })
            ?.let { return it }

        pathMoves.filter { movesCloser(it.playerId, it.path.squares.last(), board, distFromBall) }
            .minWithOrNull(
                compareBy<LegalAction.PathMovement> { distFromBall[it.path.squares.last()] ?: Int.MAX_VALUE }
                    .thenBy { it.path.steps },
            )
            ?.let { return it }

        return if (isLastAction(state)) {
            fallback(buckets, board)
        } else {
            fallback(buckets.withoutBlocks(), board)
        }
    }

    private fun chooseOffense(
        buckets: Buckets,
        board: Board,
        team: TeamSide,
        carrier: Player,
    ): LegalAction {
        buckets.claimAnnotated()?.let { return it }

        val carrierId = carrier.id
        val carrierProgress = forwardProgress(team, board.findSquare(carrierId).coordinate)

        val carrierRuns = buckets.runs.filter { it.playerId == carrierId }
        val bestRun = carrierRuns.maxByOrNull { forwardProgress(team, it.path.squares.last()) }
        val bestRunProgress = bestRun?.let { forwardProgress(team, it.path.squares.last()) } ?: carrierProgress

        val bestThrow = buckets.throws.filter { it.playerId == carrierId }
            .filter { isTowardScoringEndZone(team, board.findSquare(carrierId).coordinate, it.target.destination) }
            .maxWithOrNull(
                compareBy<LegalAction.Throw> { forwardProgress(team, it.target.destination) }
                    .thenBy { -it.target.throwCheckNeeded },
            )

        if (bestThrow != null) {
            val throwProgress = forwardProgress(team, bestThrow.target.destination)
            if (throwProgress > bestRunProgress) {
                return bestThrow
            }
        }

        bestRun?.let { return it }

        buckets.sidesteps.filter { it.playerId == carrierId }
            .maxByOrNull { forwardProgress(team, it.path.squares.last()) }
            ?.let { return it }

        buckets.blocks.firstOrNull { it.playerId == carrierId }?.let { return it }

        return fallback(buckets, board)
    }

    private fun chooseDefense(
        buckets: Buckets,
        board: Board,
        carrier: Player,
    ): LegalAction? {
        val carrierAt = board.findSquare(carrier.id).coordinate
        val distFromCarrier = distancesFrom(board.findSquare(carrier.id), board)

        buckets.blocks.filter { it.destination == carrierAt }
            .maxByOrNull { it.blockDiceCount }
            ?.let { return it }

        buckets.marks.filter { isAdjacent(it.path.squares.last(), carrierAt, distFromCarrier) }
            .minByOrNull { it.path.steps }
            ?.let { return it }

        return buckets.runs.minByOrNull { distFromCarrier[it.path.squares.last()] ?: Int.MAX_VALUE }
    }

    private fun isLastAction(state: MatchState): Boolean =
        state.currentTurnState.actionsRemaining == 1

    private fun hasPathOntoBall(pathMoves: List<LegalAction.PathMovement>, board: Board): Boolean =
        pathMoves.any { board.squareAt(it.path.squares.last()).looseBallId != null }

    private fun blockClosestOpponentToBall(
        blocks: List<LegalAction.Block>,
        board: Board,
        distFromBall: Map<Coordinate, Int>,
        team: TeamSide,
    ): LegalAction? {
        val closestOpponentAt = board.players.values
            .asSequence()
            .filter { it.team == team.opposite() && it.status == PlayerStatus.STANDING }
            .map { board.findSquare(it.id).coordinate }
            .minByOrNull { distFromBall[it] ?: Int.MAX_VALUE }
            ?: return null
        return blocks.firstOrNull { it.destination == closestOpponentAt }
    }

    private fun picksUpLooseBall(action: LegalAction.PathMovement, board: Board): Boolean {
        if (action.ballEncounter != null) return action.ballEncounter == BallEncounter.PICK_UP
        if (board.squareAt(action.path.squares.last()).looseBallId == null) return false
        val player = board.players.getValue(action.playerId)
        return com.mhaefele.gridiron.game.action.ballEncounterOnLooseBallEntry(player, action.actionType) ==
            BallEncounter.PICK_UP
    }

    private fun bouncesLooseBall(action: LegalAction.PathMovement, board: Board): Boolean {
        if (action.ballEncounter != null) return action.ballEncounter == BallEncounter.BOUNCE
        if (board.squareAt(action.path.squares.last()).looseBallId == null) return false
        return !picksUpLooseBall(action, board)
    }

    private fun movesCloser(
        playerId: PlayerId,
        landing: Coordinate,
        board: Board,
        distFromBall: Map<Coordinate, Int>,
    ): Boolean {
        val fromDist = distFromBall[board.findSquare(playerId).coordinate] ?: return false
        val toDist = distFromBall[landing] ?: return false
        return toDist < fromDist
    }

    private fun fallback(buckets: Buckets, board: Board): LegalAction {
        // Prefer actions that bank an active challenge over dumping Reserves onto an empty pitch.
        buckets.claimAnnotated()?.let { return it }
        assistedBlock(buckets.blocks)?.let { return it }
        return buckets.standUps.firstOrNull()
            ?: buckets.marks.firstOrNull()
            ?: buckets.blocks.firstOrNull()
            ?: buckets.fouls.firstOrNull()
            ?: buckets.runs.firstOrNull()
            ?: buckets.reserves.firstOrNull()
            ?: buckets.declareRuns.firstOrNull()
            ?: buckets.any
    }

    /** Prefer multi-die Blocks when both multi-die and 1-die targets exist (ASSISTED_BLOCK drill). */
    private fun assistedBlock(blocks: List<LegalAction.Block>): LegalAction? {
        val multi = blocks.filter { it.blockDiceCount > 1 }
        if (multi.isEmpty()) return null
        return if (blocks.any { it.blockDiceCount == 1 }) multi.first() else null
    }

    /** Loose ball square, else carrier square — null when no ball is on the pitch. */
    private fun ballCoordinate(board: Board): Coordinate? {
        looseBallSquare(board)?.coordinate?.let { return it }
        val carrier = board.players.values.firstOrNull { it.heldBallId != null } ?: return null
        return board.findSquare(carrier.id).coordinate
    }

    /**
     * Among on-pitch teammates nearest the ball, stand one up when any of them is prone.
     * Skips when that StandUp is not in the (narrowed) legal set.
     */
    private fun standUpClosestIfProne(
        buckets: Buckets,
        board: Board,
        team: TeamSide,
        ballAt: Coordinate,
    ): LegalAction? {
        val teammates = board.players.values.filter { it.team == team && it.status in setOf(PlayerStatus.STANDING, PlayerStatus.PRONE) }
        if (teammates.isEmpty()) return null
        val distFromBall = distancesFrom(board.squareAt(ballAt), board)
        val closestDist = teammates.minOf { distFromBall[board.findSquare(it.id).coordinate] ?: Int.MAX_VALUE }
        val proneClosest = teammates.firstOrNull { player ->
            player.status == PlayerStatus.PRONE && (distFromBall[board.findSquare(player.id).coordinate] ?: Int.MAX_VALUE) == closestDist
        } ?: return null
        return buckets.standUps.firstOrNull { it.playerId == proneClosest.id }
    }

    private fun looseBallSquare(board: Board): BoardSquare? =
        board.allSquares().firstOrNull { it.looseBallId != null }

    private fun teamBallCarrier(board: Board, team: TeamSide): Player? =
        board.players.values.firstOrNull { it.team == team && it.heldBallId != null }

    private fun opponentBallCarrier(board: Board, team: TeamSide): Player? =
        board.players.values.firstOrNull { it.team == team.opposite() && it.heldBallId != null }

    private fun isAdjacent(landing: Coordinate, other: Coordinate, distFromOther: Map<Coordinate, Int>): Boolean =
        distFromOther[landing] == 1

    private fun forwardProgress(team: TeamSide, coord: Coordinate): Int =
        when (team) {
            TeamSide.HOME -> coord.y
            TeamSide.AWAY -> -coord.y
        }

    private fun isTowardScoringEndZone(team: TeamSide, from: Coordinate, to: Coordinate): Boolean =
        when (team) {
            TeamSide.HOME -> to.y > from.y
            TeamSide.AWAY -> to.y < from.y
        }

    /** One BFS from [origin]; reused for every candidate landing square in a branch. */
    private fun distancesFrom(origin: BoardSquare, board: Board): Map<Coordinate, Int> {
        val distances = mutableMapOf(origin.coordinate to 0)
        var visited = setOf(origin.coordinate)
        var frontier = listOf(origin)
        while (frontier.isNotEmpty()) {
            val nextFrontier = mutableListOf<BoardSquare>()
            for (square in frontier) {
                val step = distances.getValue(square.coordinate) + 1
                for (neighbor in neighbors(square, board)) {
                    if (neighbor is OutOfBoundsSquare || neighbor.coordinate in visited) continue
                    visited = visited + neighbor.coordinate
                    distances[neighbor.coordinate] = step
                    nextFrontier.add(neighbor)
                }
            }
            frontier = nextFrontier
        }
        return distances
    }

    private fun bucket(legal: List<LegalAction>): Buckets {
        require(legal.isNotEmpty()) { "Cannot bucket empty legal list" }
        val pathMoves = ArrayList<LegalAction.PathMovement>(legal.size)
        val runs = ArrayList<LegalAction.Run>()
        val marks = ArrayList<LegalAction.Mark>()
        val sidesteps = ArrayList<LegalAction.Sidestep>()
        val throws = ArrayList<LegalAction.Throw>()
        val blocks = ArrayList<LegalAction.Block>()
        val fouls = ArrayList<LegalAction.Foul>()
        val standUps = ArrayList<LegalAction.StandUp>()
        val reserves = ArrayList<LegalAction.Reserves>()
        val skips = ArrayList<LegalAction.Skip>()
        val declareRuns = ArrayList<LegalAction.DeclareRun>()
        for (action in legal) {
            when (action) {
                is LegalAction.Run                 -> {
                    runs.add(action)
                    pathMoves.add(action)
                }
                is LegalAction.Mark                -> {
                    marks.add(action)
                    pathMoves.add(action)
                }
                is LegalAction.Sidestep            -> {
                    sidesteps.add(action)
                    pathMoves.add(action)
                }
                is LegalAction.Throw               -> throws.add(action)
                is LegalAction.ThrowTeammateSelect -> Unit // mid two-step; landing handled when pending
                is LegalAction.ThrowTeammate       -> Unit
                is LegalAction.CancelThrowTeammate -> Unit
                is LegalAction.Block               -> blocks.add(action)
                is LegalAction.Foul                -> fouls.add(action)
                is LegalAction.StandUp             -> standUps.add(action)
                is LegalAction.Reserves            -> reserves.add(action)
                is LegalAction.Skip                -> skips.add(action)
                is LegalAction.DeclineBonus        -> Unit // bonus window handled in choose() via ChallengePursuit
                is LegalAction.DeclareRun          -> declareRuns.add(action)
                is LegalAction.PlaceLooseBall,
                is LegalAction.ChooseBlockReroll,
                is LegalAction.ChooseBlockFaceOrder,
                is LegalAction.ChooseThrowReroll,
                is LegalAction.ContinueBlock,
                is LegalAction.QuickReleaseThrow,
                is LegalAction.PlayBonusCard, // handled in choose() via ChallengePursuit
                is LegalAction.AdjustThrowByDoOver,
                is LegalAction.AdjustBlockByDoOver,
                is LegalAction.AdjustArmourByDoOver,
                is LegalAction.ClaimChallenge,
                is LegalAction.ReplaceChallenge,
                -> Unit
            }
        }
        return Buckets(pathMoves, runs, marks, sidesteps, throws, blocks, fouls, standUps, reserves, skips, declareRuns, legal.first())
    }

    private data class Buckets(
        val pathMoves: List<LegalAction.PathMovement>,
        val runs: List<LegalAction.Run>,
        val marks: List<LegalAction.Mark>,
        val sidesteps: List<LegalAction.Sidestep>,
        val throws: List<LegalAction.Throw>,
        val blocks: List<LegalAction.Block>,
        val fouls: List<LegalAction.Foul>,
        val standUps: List<LegalAction.StandUp>,
        val reserves: List<LegalAction.Reserves>,
        val skips: List<LegalAction.Skip>,
        val declareRuns: List<LegalAction.DeclareRun>,
        val any: LegalAction,
    ) {
        fun withoutBlocks(): Buckets = copy(blocks = emptyList())

        fun claimAnnotated(): LegalAction? =
            sequenceOf(fouls, blocks, marks, runs, throws, pathMoves, standUps)
                .flatten()
                .filter { it.potentialChallengeClaims.isNotEmpty() }
                .maxByOrNull { it.potentialChallengeClaims.maxOf { claim -> claim.points } }
    }
}
