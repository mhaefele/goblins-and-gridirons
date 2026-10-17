package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.api.dto.response.MatchResultKind
import com.mhaefele.gridiron.shared.TeamSide

/** Harness slice of one step — rewards, episode meta, legal sidecars. */
data class AiHarnessStepDto(
    val actingSeat: TeamSide,
    val perspectiveSeat: TeamSide,
    val resultKind: MatchResultKind?,
    val resultWinnerRel: AiTeamRel?,
    val trainingEpisodeEnded: Boolean = false,
    val trainingOutcome: AiTrainingOutcome? = null,
    val rewards: HarnessRewardSnapshotDto,
    val legalRows: List<AiLegalChoiceDto>,
) {
    init {
        if (trainingEpisodeEnded) {
            require(resultKind == null) { "trainingEpisodeEnded must not combine with match resultKind=$resultKind" }
            require(trainingOutcome != null) { "trainingEpisodeEnded requires trainingOutcome" }
        }
    }

    fun isGameEnded(): Boolean = resultKind != null

    fun isEpisodeEnded(): Boolean = isGameEnded() || trainingEpisodeEnded
}
