package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.HarnessPlayerDto
import com.mhaefele.gridiron.api.ai.dto.HarnessRewardSnapshotDto
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.ai.dto.AiPitchSquare
import com.mhaefele.gridiron.api.ai.dto.AiTeamRel
import com.mhaefele.gridiron.api.ai.dto.AiTerrain
import com.mhaefele.gridiron.api.ai.dto.nested.AiCellObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiCellPlayerObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiChallengeSlotObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiHeldCheckObs
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedObsDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiPlayerAffordance
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.game.action.pending.armourcheck.OngoingArmourCheck
import com.mhaefele.gridiron.game.action.pending.block.OngoingBlock
import com.mhaefele.gridiron.game.action.pending.throwball.OngoingThrow
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchResult
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.model.pending.PendingDecision
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import com.mhaefele.gridiron.model.board.BlockedSquare
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.EndZoneSquare
import com.mhaefele.gridiron.model.board.FieldSquare
import com.mhaefele.gridiron.model.board.OutOfBoundsSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.board.TrapdoorSquare
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

import org.springframework.stereotype.Component

@Component
class AiGameStateMapper {
    fun matchResultForHarness(state: MatchState, perspectiveSeat: TeamSide): Pair<MatchResultKind?, AiTeamRel?> =
        toAiResult(state.progress.result, perspectiveSeat)

    /** Harness JSON snapshot — [MatchState] directly; not derived from policy [obs]. */
    fun toHarnessRewardSnapshot(state: MatchState, perspectiveSeat: TeamSide): HarnessRewardSnapshotDto {
        val (scoreUs, scoreThem) = scoresRelative(state, perspectiveSeat)
        val (touchdownsUs, touchdownsThem) = touchdownsRelative(state, perspectiveSeat)
        val (challengeCardsUs, challengeCardsThem) = challengeCardsRelative(state, perspectiveSeat)
        return HarnessRewardSnapshotDto(
            turnNumber = state.progress.turnNumber,
            scoreUs = scoreUs,
            scoreThem = scoreThem,
            touchdownsUs = touchdownsUs,
            touchdownsThem = touchdownsThem,
            challengeCardsUs = challengeCardsUs,
            challengeCardsThem = challengeCardsThem,
            players = toHarnessPlayers(state.board, perspectiveSeat),
            ballCoordinate = ballCoordinate(state.board),
        )
    }

    fun toAiNestedObsDto(
        state: MatchState,
        perspectiveSeat: TeamSide,
        legalActions: List<LegalAction> = emptyList(),
    ): AiNestedObsDto {
        requirePitchMatchesAiLayout(state)
        val opp = perspectiveSeat.opposite()
        val (scoreUs, scoreThem) = scoresRelative(state, perspectiveSeat)
        val (touchdownsUs, touchdownsThem) = touchdownsRelative(state, perspectiveSeat)
        val (challengeCardsUs, challengeCardsThem) = challengeCardsRelative(state, perspectiveSeat)
        val (erUs, erThem) = emergencyReservesRelative(state, perspectiveSeat)
        val affordances = AiPlayerAffordanceAggregator.byPlayerIndex(
            state = state,
            legalActions = legalActions,
            perspectiveSeat = perspectiveSeat,
            playerIndexOf = ::aiPlayerIndex,
        )
        return AiNestedObsDto(
            turnNumber = state.progress.turnNumber,
            phase = state.currentTurnState.phase,
            actionsRemaining = state.currentTurnState.actionsRemaining,
            scoreUs = scoreUs,
            scoreThem = scoreThem,
            touchdownsUs = touchdownsUs,
            touchdownsThem = touchdownsThem,
            challengeCardsUs = challengeCardsUs,
            challengeCardsThem = challengeCardsThem,
            pointsVictoryTurnsRemaining = state.progress.pointsVictoryTurnsRemaining,
            emergencyReservesUs = erUs,
            emergencyReservesThem = erThem,
            claimsThisTurn = state.cards.claimsThisTurn,
            oppHandSize = state.cards.handCards(opp).size,
            modHoldTheLine = state.currentTurnState.bonusModifiers.holdTheLine,
            modGangUp = state.currentTurnState.bonusModifiers.gangUp,
            modAllInTackleProneAfterBlock = allInTackleProneAfterBlock(state),
            challenges = toAiChallengeSlots(state.cards),
            deckTopCondition = state.cards.deckTop?.challenge,
            deckSize = state.cards.deck.size,
            discardSize = state.cards.discard.size,
            ownBonusPlay = toOwnBonusPlay(state.cards, perspectiveSeat),
            heldCheck = toAiHeldCheckObs(state),
            cells = toAiCells(state.board, perspectiveSeat, state.currentTurnState.turnEndedPlayers, affordances),
        )
    }

    /** Pitch or dugout index in perspective-relative [AiObsLayout] space for [playerId]. */
    fun perspectiveBoardIndexOf(board: Board, playerId: PlayerId, perspectiveSeat: TeamSide): Int =
        AiObsLayout.toPerspectiveIndex(boardIndexOf(board, playerId), perspectiveSeat)

    /**
     * Roster player index for AI actor / cell obs (`1 .. MAX_PLAYERS_PER_TEAM`).
     * Setup ids are `h0` / `a3` (trailing digits = 0-based roster slot); wire uses slot + 1
     * so `0` stays the unused / empty pad.
     */
    fun aiPlayerIndex(playerId: PlayerId): Int {
        val digits = playerId.value.takeLastWhile { it.isDigit() }
        require(digits.isNotEmpty()) { "PlayerId $playerId has no trailing roster index digits" }
        val slot = digits.toInt()
        require(slot in 0 until AiObsLayout.MAX_PLAYERS_PER_TEAM) { "PlayerId $playerId roster slot $slot out of range 0..${AiObsLayout.MAX_PLAYERS_PER_TEAM - 1}" }
        return slot + 1
    }

    /** Absolute pitch or dugout index for [playerId] (domain / test helpers). */
    fun boardIndexOf(board: Board, playerId: PlayerId): Int {
        val player = board.player(playerId)
        return when (player.status) {
            PlayerStatus.RESERVES                     -> {
                val slot = board.reserves.getValue(player.team).playerIds.indexOf(playerId)
                require(slot >= 0) { "Reserves player $playerId missing from dugout list" }
                require(slot < AiObsLayout.BENCH_SLOTS) { "Bench slot $slot out of AI layout range" }
                when (player.team) {
                    TeamSide.HOME -> AiObsLayout.homeBenchIndex(slot)
                    TeamSide.AWAY -> AiObsLayout.awayBenchIndex(slot)
                }
            }
            PlayerStatus.STANDING, PlayerStatus.PRONE -> pitchIndex(board.findSquare(playerId).coordinate)
        }
    }

    fun pitchIndex(coord: Coordinate): Int = AiObsLayout.squareIndex(coord.x, coord.y)

    fun perspectivePitchIndex(coord: Coordinate, perspectiveSeat: TeamSide): Int =
        AiObsLayout.toPerspectiveIndex(pitchIndex(coord), perspectiveSeat)

    /** Perspective-relative [AiPitchSquare] for packed dest / source columns. */
    fun perspectivePitchXy0(coord: Coordinate, perspectiveSeat: TeamSide): AiPitchSquare =
        AiObsLayout.toPerspectivePitchXy0(coord, perspectiveSeat)

    private fun requirePitchMatchesAiLayout(state: MatchState) {
        val def = PitchCatalog.definition(state.config.pitchDefinitionId)
        require(def.playWidth == AiObsLayout.PLAY_WIDTH && def.playHeight == AiObsLayout.PLAY_HEIGHT) { "AI obs layout expects ${AiObsLayout.PLAY_WIDTH}x${AiObsLayout.PLAY_HEIGHT}, pitch ${state.config.pitchDefinitionId} is ${def.playWidth}x${def.playHeight}" }
    }

    private fun scoresRelative(state: MatchState, perspective: TeamSide): Pair<Int, Int> =
        when (perspective) {
            TeamSide.HOME -> state.progress.homeScore.points to state.progress.awayScore.points
            TeamSide.AWAY -> state.progress.awayScore.points to state.progress.homeScore.points
        }

    private fun touchdownsRelative(state: MatchState, perspective: TeamSide): Pair<Int, Int> =
        when (perspective) {
            TeamSide.HOME -> state.progress.homeTouchdowns to state.progress.awayTouchdowns
            TeamSide.AWAY -> state.progress.awayTouchdowns to state.progress.homeTouchdowns
        }

    private fun challengeCardsRelative(state: MatchState, perspective: TeamSide): Pair<Int, Int> =
        when (perspective) {
            TeamSide.HOME -> state.progress.homeChallengeCards to state.progress.awayChallengeCards
            TeamSide.AWAY -> state.progress.awayChallengeCards to state.progress.homeChallengeCards
        }

    private fun emergencyReservesRelative(state: MatchState, perspective: TeamSide): Pair<Int, Int> =
        when (perspective) {
            TeamSide.HOME -> state.config.homeEmergencyReserves to state.config.awayEmergencyReserves
            TeamSide.AWAY -> state.config.awayEmergencyReserves to state.config.homeEmergencyReserves
        }

    private fun toAiResult(result: MatchResult?, perspective: TeamSide): Pair<MatchResultKind?, AiTeamRel?> =
        when (result) {
            null                         -> null to null
            is MatchResult.SuddenDeath   -> MatchResultKind.suddenDeath to teamRel(result.winner, perspective)
            is MatchResult.PointsVictory -> MatchResultKind.pointsVictory to result.winner?.let { teamRel(it, perspective) }
        }

    private fun teamRel(team: TeamSide, perspective: TeamSide): AiTeamRel =
        if (team == perspective) AiTeamRel.US else AiTeamRel.THEM

    // TODO(pending): BlockContinuation / held-check obs should own this — don't peek pending type here.
    private fun allInTackleProneAfterBlock(state: MatchState): Boolean {
        val pending = state.currentTurnState.pendingDecision as? BlockContinuation ?: return false
        return pending.ongoingBlock.allInTackleProneAfterBlock
    }

    private fun toAiChallengeSlots(cards: CardState): List<AiChallengeSlotObs> =
        cards.activeSlots.map { card ->
            if (card == null) AiChallengeSlotObs(condition = null, points = AiObsLayout.SENTINEL)
            else AiChallengeSlotObs(condition = card.challenge, points = card.points)
        }

    private fun toOwnBonusPlay(cards: CardState, perspective: TeamSide): List<BonusPlay?> {
        val hand = cards.handCards(perspective)
        return List(AiObsLayout.BONUS_HAND_SLOTS) { i ->
            hand.getOrNull(i)?.bonusPlay
        }
    }

    /** Held OS / EP / Do-Over / Enforcer roll visible to the coach on this decision step. */
    fun toAiHeldCheckObs(state: MatchState): AiHeldCheckObs =
        // TODO(pending): giant pending-type when for held checks — BlockContinuation/ThrowContinuation
        // (or Ongoing*) should expose held-check obs without AiGameStateMapper type-switching.
        when (val pending = state.currentTurnState.pendingDecision) {
            is BlockContinuation -> {
                val ongoing = pending.ongoingBlock
                when (ongoing.stage) {
                    OngoingBlock.BlockStage.HANDLE_POST_POOL,
                    OngoingBlock.BlockStage.HANDLE_ENFORCER_ORDER,
                                                             -> ongoing.faces?.let { AiHeldCheckObs.blockFaces(it) } ?: AiHeldCheckObs.empty()
                    else                                     -> armourHeldFromDelegate(pending)
                }
            }
            is ThrowContinuation -> {
                val ongoing = pending.ongoingThrow
                when (ongoing.stage) {
                    OngoingThrow.ThrowStage.APPLY_POST_ROLL_ABILITIES ->
                        ongoing.naturalRoll?.let { AiHeldCheckObs.throwD6(it) } ?: AiHeldCheckObs.empty()
                    else                                              -> AiHeldCheckObs.empty()
                }
            }
            else                 -> armourHeldFromDelegate(pending)
        }

    private fun armourHeldFromDelegate(pending: PendingDecision?): AiHeldCheckObs {
        var cur = pending
        while (cur != null) {
            if (cur is ArmourCheckContinuation) {
                val ongoing = cur.ongoingArmourCheck
                if (ongoing.stage == OngoingArmourCheck.ArmourCheckStage.HANDLE_ARMOUR_DO_OVER) {
                    return ongoing.naturalRoll?.let { AiHeldCheckObs.armourD6(it) } ?: AiHeldCheckObs.empty()
                }
            }
            cur = cur.delegate
        }
        return AiHeldCheckObs.empty()
    }

    /** Pitch + dugout roster for harness joins (absolute coords; null coord = dugout). */
    private fun toHarnessPlayers(board: Board, perspective: TeamSide): List<HarnessPlayerDto> =
        board.players.values.map { player ->
            val coordinate = when (player.status) {
                PlayerStatus.STANDING, PlayerStatus.PRONE -> board.findSquare(player.id).coordinate
                PlayerStatus.RESERVES                     -> null
            }
            HarnessPlayerDto(
                playerId = player.id.value,
                playerIndex = aiPlayerIndex(player.id),
                teamRel = teamRel(player.team, perspective),
                coordinate = coordinate,
                status = player.status,
            )
        }

    /**
     * Absolute pitch square of the single in-play ball (held or loose).
     * Null when no ball is on a playable square (e.g. removed after a TD).
     * Harness contract is one ball — fails if multiple on-pitch locations exist.
     */
    private fun ballCoordinate(board: Board): Coordinate? {
        val heldOnPitch = board.players.values.mapNotNull { player ->
            if (!player.holdsBall()) return@mapNotNull null
            when (player.status) {
                PlayerStatus.STANDING, PlayerStatus.PRONE -> board.findSquare(player.id).coordinate
                PlayerStatus.RESERVES                     -> null
            }
        }
        val loose = board.allSquares().mapNotNull { square ->
            square.coordinate.takeIf { square.looseBallId != null }
        }
        val onPitch = heldOnPitch + loose
        require(onPitch.size <= 1) { "Harness expects at most one on-pitch ball; found ${onPitch.size} at $onPitch" }
        return onPitch.singleOrNull()
    }

    private fun toAiCells(
        board: Board,
        perspective: TeamSide,
        turnEnded: Set<PlayerId>,
        affordances: Map<Int, AiPlayerAffordance>,
    ): List<AiCellObs> {
        val ball = ballCoordinate(board)
        val cells = MutableList(AiObsLayout.BOARD_SLOT_COUNT) { AiCellObs.emptyBench() }
        for (y in 1..AiObsLayout.PLAY_HEIGHT) {
            for (x in 1..AiObsLayout.PLAY_WIDTH) {
                val coord = Coordinate(x, y)
                val obsIndex = AiObsLayout.toPerspectiveIndex(AiObsLayout.squareIndex(x, y), perspective)
                cells[obsIndex] = toAiCellObs(
                    board.squareAt(coord),
                    board,
                    perspective,
                    turnEnded,
                    affordances,
                    ball,
                )
            }
        }
        for (team in TeamSide.entries) {
            val ids = board.reserves.getValue(team).playerIds
            require(ids.size <= AiObsLayout.BENCH_SLOTS) { "${team} dugout size ${ids.size} exceeds AI bench slots" }
            ids.forEachIndexed { slot, playerId ->
                val absoluteIndex = when (team) {
                    TeamSide.HOME -> AiObsLayout.homeBenchIndex(slot)
                    TeamSide.AWAY -> AiObsLayout.awayBenchIndex(slot)
                }
                val obsIndex = AiObsLayout.toPerspectiveIndex(absoluteIndex, perspective)
                cells[obsIndex] = AiCellObs(
                    terrain = AiTerrain.BENCH,
                    hasBall = false,
                    player = toAiCellPlayerObs(board.player(playerId), perspective, turnEnded, affordances),
                )
            }
        }
        return cells
    }

    private fun toAiCellObs(
        square: BoardSquare,
        board: Board,
        perspective: TeamSide,
        turnEnded: Set<PlayerId>,
        affordances: Map<Int, AiPlayerAffordance>,
        ball: Coordinate?,
    ): AiCellObs {
        val occupant = square.playerId?.let { board.player(it) }
        val coord = square.coordinate
        return AiCellObs(
            terrain = toAiTerrain(square, perspective),
            hasBall = square.looseBallId != null || occupant?.holdsBall() == true,
            player = occupant?.let { toAiCellPlayerObs(it, perspective, turnEnded, affordances) },
            pitchX = AiObsLayout.pitchX0(coord, perspective),
            pitchY = AiObsLayout.pitchY0(coord, perspective),
            distToBall = AiObsLayout.distToBall(coord, ball),
        )
    }

    /** End zones follow [perspective] — same as pitch index flip — so “our” / “their” codes match HOME training. */
    private fun toAiTerrain(square: BoardSquare, perspective: TeamSide): AiTerrain =
        when (square) {
            is FieldSquare       -> AiTerrain.FIELD
            is EndZoneSquare     -> if (square.team == perspective) AiTerrain.END_ZONE_HOME else AiTerrain.END_ZONE_AWAY
            is TrapdoorSquare    -> AiTerrain.TRAPDOOR
            is BlockedSquare     -> AiTerrain.BLOCKED
            is OutOfBoundsSquare -> error("Out-of-bounds square ${square.coordinate} is not part of AI pitch indices")
        }

    private fun toAiCellPlayerObs(
        player: Player,
        perspective: TeamSide,
        turnEnded: Set<PlayerId>,
        affordances: Map<Int, AiPlayerAffordance>,
    ): AiCellPlayerObs {
        val stats = player.profile.stats
        val playerIndex = aiPlayerIndex(player.id)
        return AiCellPlayerObs(
            playerIndex = playerIndex,
            teamRel = teamRel(player.team, perspective),
            status = player.status,
            turnEnded = player.id in turnEnded,
            move = packMove(stats.move),
            blockDice = stats.blockDiceCount,
            throwStat = packThrow(stats.throwStat),
            armour = packArmour(stats.armour),
            ability = player.profile.ability,
            canThrowTeammate = player.profile.canThrowTeammate,
            affordance = affordances[playerIndex] ?: AiPlayerAffordance.NONE,
        )
    }

    private fun packMove(move: MoveValue): Int =
        when (move) {
            is MoveValue.Fixed -> move.squares
            MoveValue.RandomD6 -> AiObsLayout.MOVE_RANDOM_D6
        }

    private fun packThrow(throwStat: ThrowStat): Int =
        when (throwStat) {
            is ThrowStat.Threshold -> throwStat.minimumRoll
            ThrowStat.Unthrowable  -> AiObsLayout.THROW_UNTHROWABLE
        }

    private fun packArmour(armour: ArmourStat): Int =
        when (armour) {
            is ArmourStat.Threshold -> armour.minimumRoll
            ArmourStat.NoArmour     -> AiObsLayout.ARMOUR_NONE
        }

    private fun TeamSide.opposite(): TeamSide =
        when (this) {
            TeamSide.HOME -> TeamSide.AWAY
            TeamSide.AWAY -> TeamSide.HOME
        }
}
