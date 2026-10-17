package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.PendingDecision
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.model.pending.ClaimChallenge
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.pending.OfferDanceStep
import com.mhaefele.gridiron.model.pending.OfferHoldTheLine
import com.mhaefele.gridiron.model.pending.OfferGangUp
import com.mhaefele.gridiron.model.pending.ReservesContinuation
import com.mhaefele.gridiron.model.pending.StandUpContinuation
import com.mhaefele.gridiron.model.pending.FoulContinuation
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.pending.BallGuardContinuation
import com.mhaefele.gridiron.model.pending.KnockDownContinuation
import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.model.pending.SidestepContinuation
import com.mhaefele.gridiron.model.pending.MarkContinuation
import com.mhaefele.gridiron.model.pending.RunContinuation
import com.mhaefele.gridiron.model.pending.ThrowTeammateContinuation
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import com.mhaefele.gridiron.model.pending.ActionContinuation
import com.mhaefele.gridiron.model.pending.OfferUndying
import com.mhaefele.gridiron.model.pending.EmergencyReserves
import com.mhaefele.gridiron.model.pending.InitialPlayerPlacement
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.board.Ball
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardDefinition
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.PlayerActionStep
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.PreTurnStep
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.shared.TurnStartStep
import java.util.UUID

/**
 * Fluent mid-game match fixture — override only what the test cares about.
 *
 * Defaults: empty pitch, empty challenge tableau, turn 3 (claims enabled), full action budget.
 */
class MatchBuilder {
    private var boardScenario = GameScenario.board()
    private var challengeClaimConditions: List<ChallengeClaimCondition> = emptyList()
    private var homeHand: List<ChallengeCardDefinition> = emptyList()
    private var awayHand: List<ChallengeCardDefinition> = emptyList()
    private var cardsOverride: CardState? = null
    private var turnNumber: Int = 3
    private var actionsRemaining: Int = 3
    private var phase: TurnPhase = TurnPhase.PLAYER_ACTION
    private var pendingDecision: PendingDecision? = null
    private var pendingDecisionSet: Boolean = false
    private var actionsUsedThisTurn: Map<PlayerId, Set<ActionType>> = emptyMap()
    private var activeCoach: TeamSide = TeamSide.HOME
    private var homeScore: Int = 0
    private var awayScore: Int = 0
    private var pointsVictoryTurnsRemaining: Int? = null
    private var matchId: MatchId = MatchId(UUID.randomUUID().toString())

    val board: BoardScenario get() = boardScenario

    /** Start from both teams deployed instead of an empty pitch. */
    fun bothTeamsDeployed(): MatchBuilder = apply { boardScenario = GameScenario.board(GameScenario.bothTeamsDeployed()) }

    /** Replace the pitch with an already-built [Board] (e.g. after BoardMutator setup). */
    fun fromBoard(board: Board): MatchBuilder = apply { boardScenario = GameScenario.board(board) }

    /** Empty pitch from a custom [BoardDefinition]. */
    fun emptyPitch(definition: BoardDefinition): MatchBuilder = apply { boardScenario = GameScenario.board(GameScenario.emptyBoard(definition)) }

    /** Purpose-built empty pitch (no default pillars) — geometry / ability tests. */
    fun tinyBoard(
        width: Int = 8,
        height: Int = 3,
        blocked: Set<Coordinate> = emptySet(),
        trapdoors: Set<Coordinate> = emptySet(),
    ): MatchBuilder = apply { boardScenario = GameScenario.tinyBoard(width, height, blocked, trapdoors) }

    /** Active tableau slots for these challenge titles (catalog order; duplicates allowed for multi-copy titles). */
    fun challengeCards(vararg titles: ChallengeClaimCondition): MatchBuilder = apply {
        require(titles.size <= 3) { "At most 3 active challenge cards, was ${titles.size}" }
        challengeClaimConditions = titles.toList()
        cardsOverride = null
    }

    fun homeHand(vararg cards: ChallengeCardDefinition): MatchBuilder = apply {
        homeHand = cards.toList()
        cardsOverride = null
    }

    fun homeHand(cards: List<ChallengeCardDefinition>): MatchBuilder = apply {
        homeHand = cards
        cardsOverride = null
    }

    fun awayHand(vararg cards: ChallengeCardDefinition): MatchBuilder = apply {
        awayHand = cards.toList()
        cardsOverride = null
    }

    fun awayHand(cards: List<ChallengeCardDefinition>): MatchBuilder = apply {
        awayHand = cards
        cardsOverride = null
    }

    /** Put challenge cards with these bonus plays in the home hand (filler actives if none set). */
    fun homeBonus(vararg bonuses: BonusPlay): MatchBuilder = apply {
        homeHand = bonuses.map { bonusCard(it) }
        cardsOverride = null
    }

    /** Put challenge cards with these bonus plays in the away hand (filler actives if none set). */
    fun awayBonus(vararg bonuses: BonusPlay): MatchBuilder = apply {
        awayHand = bonuses.map { bonusCard(it) }
        cardsOverride = null
    }

    /** Full [CardState] override — skips title/hand builders. */
    fun cards(state: CardState): MatchBuilder = apply { cardsOverride = state }

    fun turnNumber(n: Int): MatchBuilder = apply { turnNumber = n }

    fun actionsRemaining(n: Int): MatchBuilder = apply { actionsRemaining = n }

    fun phase(p: TurnPhase): MatchBuilder = apply { phase = p }

    fun activeCoach(team: TeamSide): MatchBuilder = apply { activeCoach = team }

    fun pendingDecision(decision: PendingDecision): MatchBuilder = apply {
        // *Continuations are not top-level — always park under selecting RPA.
        pendingDecision = when (decision) {
            is ActionContinuation -> RegularPlayerAction(delegate = decision)
            else                  -> decision
        }
        pendingDecisionSet = true
        phase = pendingDecision!!.phase
    }

    fun actionsUsed(actionsUsedThisTurn: Map<PlayerId, Set<ActionType>>): MatchBuilder = apply { this.actionsUsedThisTurn = actionsUsedThisTurn }

    fun scores(home: Int, away: Int): MatchBuilder = apply {
        homeScore = home
        awayScore = away
    }

    fun pointsVictoryTurnsRemaining(n: Int): MatchBuilder = apply { pointsVictoryTurnsRemaining = n }

    fun addPlayer(
        x: Int,
        y: Int,
        status: PlayerStatus = PlayerStatus.STANDING,
        team: TeamSide = TeamSide.HOME,
        profile: PositionProfile = TestProfiles.lineman(),
    ): PlacedPlayer = boardScenario.addPlayer(x, y, status, team, profile)

    fun addPlayer(
        at: Coordinate,
        status: PlayerStatus = PlayerStatus.STANDING,
        team: TeamSide = TeamSide.HOME,
        profile: PositionProfile = TestProfiles.lineman(),
    ): PlacedPlayer = boardScenario.addPlayer(at, status, team, profile)

    fun addReservesPlayer(
        team: TeamSide = TeamSide.HOME,
        profile: PositionProfile = TestProfiles.lineman(),
    ): PlacedPlayer = boardScenario.addReservesPlayer(team, profile)

    fun holdingBall(placed: PlacedPlayer): PlacedPlayer = boardScenario.holdingBall(placed)

    fun addLooseBall(at: Coordinate): Ball = boardScenario.addLooseBall(at)

    fun addLooseBall(x: Int, y: Int): Ball = boardScenario.addLooseBall(x, y)

    fun removeAllBalls(): MatchBuilder = apply { boardScenario.removeAllBalls() }

    fun toMatchState(): MatchState {
        val cards = cardsOverride ?: buildCards()
        var state = matchState(
            board = boardScenario.board,
            id = matchId,
            config = defaultMatchConfig(),
            activeCoach = activeCoach,
            actionsRemaining = actionsRemaining,
            phase = phase,
            cards = cards,
        ).withTurnNumber(turnNumber)
        state = state.copy(
            progress = state.progress.copy(
                pointsVictoryTurnsRemaining = pointsVictoryTurnsRemaining
                    ?: state.progress.pointsVictoryTurnsRemaining,
            ),
        )
        if (homeScore != 0 || awayScore != 0) {
            state = state.withScores(homeScore, awayScore)
        }
        if (actionsUsedThisTurn.isNotEmpty()) {
            state = state.withActionsUsed(actionsUsedThisTurn)
        }
        if (!pendingDecisionSet) return state
        val decision = pendingDecision
        return state.copy(
            currentTurnState = state.currentTurnState.copy(
                pendingDecision = decision,
                regularActionSlotsRemaining = actionsRemaining,
                // TODO(7.13): Prefer playing into the window/grant (or an explicit step setter)
                // over dumping mid-FSM pending state into MatchBuilder.
                step = when (decision) {
                    is ReplaceChallenge   -> PreTurnStep.REPLACE_CHALLENGE
                    // Engine queues ER then advances to BONUS_CARDS — park there so clear does not re-queue.
                    is EmergencyReserves  -> PreTurnStep.BONUS_CARDS
                    is OfferGangUp  -> TurnStartStep.AFTER_GANG_UP
                    is OfferHoldTheLine -> PreTurnStep.AFTER_BONUS_CARDS
                    is ClaimChallenge     -> PlayerActionStep.CLAIM
                    is OfferUndying    -> PreTurnStep.PLAYER_ABILITIES
                    is OfferExtraEffort   -> when {
                        decision.delegate == null          -> PlayerActionStep.OFFER_EXTRA_EFFORT
                        decision.delegate.delegate != null -> PlayerActionStep.IN_ACTION
                        else                               -> PlayerActionStep.OFFER_EXTRA_EFFORT
                    }
                    is OfferDanceStep      -> when {
                        decision.delegate != null -> PlayerActionStep.IN_ACTION
                        else                      -> PlayerActionStep.OFFER_DANCE_STEP
                    }
                    is MarkContinuation,
                    is BlockContinuation,
                    is RunContinuation,
                    is SidestepContinuation,
                    is BallGuardContinuation,
                    is ThrowContinuation,
                    is ThrowTeammateContinuation,
                    is FoulContinuation,
                    is StandUpContinuation,
                    is ReservesContinuation,
                    is KnockDownContinuation,
                    is ArmourCheckContinuation,
                        -> PlayerActionStep.IN_ACTION
                    null                      -> if (phase != TurnPhase.PLAYER_ACTION) null
                        else if (actionsRemaining == 0) PlayerActionStep.QUEUE_NEXT
                        else PlayerActionStep.SEED
                    is RegularPlayerAction    -> if(decision.delegate != null) PlayerActionStep.IN_ACTION else PlayerActionStep.SELECT
                    is InitialPlayerPlacement -> null
                },
            ),
        )
    }

    private fun buildCards(): CardState {
        val actives = ChallengeCardCatalog.cardsForTitles(challengeClaimConditions)
        val used = (actives + homeHand + awayHand).map { it.id }.toSet()
        val activeSlots = when {
            challengeClaimConditions.isNotEmpty()          ->
                (actives.map { it as ChallengeCardDefinition? } + List(3) { null }).take(3)
            homeHand.isNotEmpty() || awayHand.isNotEmpty() ->
                // Filler actives so hands aren't also in the tableau.
                ChallengeCardCatalog.allCards().filter { it.id !in used }.take(3)
            else                                           -> listOf(null, null, null)
        }
        val usedWithActives = used + activeSlots.filterNotNull().map { it.id }.toSet()
        return CardState(
            activeSlots = activeSlots,
            deck = ChallengeCardCatalog.allCards().filter { it.id !in usedWithActives },
            homeHand = homeHand,
            awayHand = awayHand,
        )
    }

    private fun bonusCard(bonus: BonusPlay): ChallengeCardDefinition =
        ChallengeCardCatalog.allCards().first { it.bonusPlay == bonus }
}
