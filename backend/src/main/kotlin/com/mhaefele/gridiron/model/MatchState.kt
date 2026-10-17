package com.mhaefele.gridiron.model

import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Root persisted match aggregate — board layout, frozen config, cumulative progress, and current turn.
 * Turn transitions live in [com.mhaefele.gridiron.game.turn.TurnEngine] (Phase 5).
 */
data class MatchState(
    val id: MatchId,
    val board: Board,
    val config: MatchConfig,
    val progress: MatchProgress,
    val currentTurnState: TurnState,
    /** Challenge tableau, deck, discard, and bonus hands (D1). */
    val cards: CardState,
    /** Curriculum episode metadata — null on regular matches. */
    val trainingEpisode: TrainingEpisodeConfig? = null,
    /** Highest stored `domainId` for this match; `0` when no events have been persisted yet. */
    val lastEventId: Int = 0,
) {
    init {
        if (currentTurnState.phase == TurnPhase.GAME_OVER) {
            require(progress.result != null) { "GAME_OVER requires a MatchResult" }
        }
    }

    /** True when [MatchProgress.result] is set — the match is finished. */
    fun isGameEnded(): Boolean = progress.result != null

    /**
     * Seat that must choose next — pending [com.mhaefele.gridiron.model.pending.PendingDecision.respondingCoach]
     * when set, else [TurnState.activeCoach].
     */
    fun actingSeat(): TeamSide =
        currentTurnState.pendingDecision?.respondingCoach
            ?: currentTurnState.activeCoach
}
