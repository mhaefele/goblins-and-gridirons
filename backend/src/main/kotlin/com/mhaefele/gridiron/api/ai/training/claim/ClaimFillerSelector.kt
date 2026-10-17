package com.mhaefele.gridiron.api.ai.training.claim

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.cards.ActionAttachedClaims
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.catalog.ChallengeCardCatalog
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition

/** Draw non-leaking challenge cards into empty tableau slots after the opening board exists. */
internal object ClaimFillerSelector {
    val MULTI_MOVE = setOf(
        ChallengeClaimCondition.KEEP_RUNNING,
        ChallengeClaimCondition.ALL_HANDS,
        ChallengeClaimCondition.STAR_TURN,
    )

    val OPENING_PROBE_CONDITIONS: List<ChallengeClaimCondition> =
        ChallengeClaimCondition.entries.filter { it !in MULTI_MOVE }

    private val BLOCK_FILLERS_WHEN_MARK_OPENING = setOf(
        ChallengeClaimCondition.DROP_THEM,
        ChallengeClaimCondition.SEND_THEM_OFF,
    )

    /** Never bait Grab It — a far loose ball (claim episode ball-in-play) would make it leak. */
    private val NEVER_BAIT = setOf(ChallengeClaimCondition.GRAB_IT)

    fun attachClaimHints(state: MatchState, openingMoves: List<LegalAction>): List<LegalAction> {
        val turn = state.currentTurnState
        return ActionAttachedClaims.annotatePotentialClaims(
            actions = openingMoves,
            board = state.board,
            turn = turn,
            cards = state.cards,
            coach = turn.activeCoach,
            activeClaims = ActionAttachedClaims.activeClaimConditions(state.cards),
        )
    }

    /**
     * Focus + three non-leaking baits: two baits share the tableau with [focus], one bait is deck-top.
     * Forced replace must discard a bait (never [focus]); drawing the third bait keeps the lesson card.
     *
     * Baits are lowest-point first: the two cheapest sit in the tableau (with [focus], one shuffle for
     * slot order); the pricier third bait is deck-top so dump-lowest prefers baits over the lesson
     * when the focus is worth more points than the baits.
     */
    fun assembleFocusReplaceKeepSet(
        state: MatchState,
        focus: ChallengeClaimCondition,
        dice: DiceRoller,
        openingMoves: List<LegalAction>,
    ): MatchState {
        val focusCard = state.cards.activeCards().singleOrNull { it.challenge == focus } ?: error("claim replace keep-set: focus $focus missing from ${state.cards.activeChallengeClaims()}")
        val baits = pickNonLeakingBaits(
            openingMoves = openingMoves,
            state = state,
            exclude = setOf(focus),
            from = ChallengeCardCatalog.allCards().filter { it.id != focusCard.id },
            count = 3,
            label = "claim replace keep-set for $focus",
        )
        return withReplaceLayout(
            state = state,
            tableau = dice.shuffle(listOf(focusCard, baits[0], baits[1])),
            deckTop = baits[2],
        )
    }

    /**
     * Clean Sweep keep-set: two of [trio] + one bait in the tableau; the missing trio title on deck-top.
     * Discard the bait → draw completes the trio.
     */
    fun assembleCleanSweepReplaceKeepSet(
        state: MatchState,
        trio: List<ChallengeClaimCondition>,
        dice: DiceRoller,
        openingMoves: List<LegalAction>,
    ): MatchState {
        require(trio.size == 3) { "clean-sweep replace keep-set expects 3 trio titles, was $trio" }
        val bait = pickNonLeakingBaits(
            openingMoves = openingMoves,
            state = state,
            exclude = trio.toSet(),
            from = ChallengeCardCatalog.allCards(),
            count = 1,
            label = "clean-sweep replace keep-set",
        ).single()
        val keepTwo = dice.shuffle(trio).take(2)
        val onDeckTitle = trio.single { it !in keepTwo }
        return withReplaceLayout(
            state = state,
            tableau = dice.shuffle(ChallengeCardCatalog.cardsForTitles(keepTwo) + bait),
            deckTop = ChallengeCardCatalog.cardsForTitles(listOf(onDeckTitle)).single(),
        )
    }

    /** Non-opening claimable probe conditions, plus [focus] (always eligible for a deck copy). */
    fun allowedFillerConditions(
        openingMoves: List<LegalAction>,
        state: MatchState,
        focus: ChallengeClaimCondition,
    ): Set<ChallengeClaimCondition> {
        val claimableOnOpening = openingClaimableConditions(openingMoves, state)
        return OPENING_PROBE_CONDITIONS.toSet() - claimableOnOpening - markExcludedFillers(openingMoves, state.board) + focus
    }

    /** Block fillers leak mark→block chains; Free Up leaks when a Mark can park beside the carrier. */
    internal fun markExcludedFillers(openingMoves: List<LegalAction>, board: Board): Set<ChallengeClaimCondition> {
        val marks = openingMoves.filterIsInstance<LegalAction.Mark>()
        if (marks.isEmpty()) return emptySet()
        val excluded = BLOCK_FILLERS_WHEN_MARK_OPENING.toMutableSet()
        val carrierSquares = board.players.values.filter { it.holdsBall() }.map { board.findSquare(it.id) }
        if (carrierSquares.isNotEmpty() && marks.any { mark -> markEndsAdjacentToAnyCarrier(mark, board, carrierSquares) }) { excluded += ChallengeClaimCondition.STRIP_IT }
        return excluded
    }

    private fun markEndsAdjacentToAnyCarrier(
        mark: LegalAction.Mark,
        board: Board,
        carrierSquares: List<BoardSquare>,
    ): Boolean {
        val destination = board.squareAt(mark.path.squares.last())
        return carrierSquares.any { carrier -> neighbors(carrier, board).any { it.coordinate == destination.coordinate } }
    }

    private fun pickNonLeakingBaits(
        openingMoves: List<LegalAction>,
        state: MatchState,
        exclude: Set<ChallengeClaimCondition>,
        from: List<ChallengeCardDefinition>,
        count: Int,
        label: String,
    ): List<ChallengeCardDefinition> {
        val conditions = nonLeakingBaitConditions(openingMoves, state, exclude)
        val baits = from.filter { it.challenge in conditions }
            .sortedBy { it.points }
            .take(count)
        check(baits.size == count) { "$label: need $count baits, allowed=$conditions, got=${baits.size}" }
        return baits
    }

    private fun nonLeakingBaitConditions(
        openingMoves: List<LegalAction>,
        state: MatchState,
        exclude: Set<ChallengeClaimCondition>,
    ): Set<ChallengeClaimCondition> =
        OPENING_PROBE_CONDITIONS.toSet() -
            openingClaimableConditions(openingMoves, state) -
            markExcludedFillers(openingMoves, state.board) -
            exclude -
            NEVER_BAIT

    /** Persist keep-set: ids only at the CardState boundary. */
    private fun withReplaceLayout(
        state: MatchState,
        tableau: List<ChallengeCardDefinition>,
        deckTop: ChallengeCardDefinition,
    ): MatchState {
        require(tableau.size == 3) { "replace keep-set tableau must have 3 cards, was ${tableau.size}" }
        val used = tableau.map { it.id }.toSet() + deckTop.id
        return state.copy(
            cards = state.cards.copy(
                activeSlots = tableau,
                deck = listOf(deckTop) + ChallengeCardCatalog.allCards().filter { it.id !in used },
            ),
        )
    }

    internal fun openingClaimableConditions(
        openingMoves: List<LegalAction>,
        state: MatchState,
    ): Set<ChallengeClaimCondition> {
        val turn = state.currentTurnState
        return ActionAttachedClaims.annotatePotentialClaims(
            actions = openingMoves,
            board = state.board,
            turn = turn,
            cards = state.cards,
            coach = turn.activeCoach,
            activeClaims = OPENING_PROBE_CONDITIONS,
        ).flatMap { it.potentialChallengeClaims }
            .map { it.claimCondition }
            .toSet()
    }
}
