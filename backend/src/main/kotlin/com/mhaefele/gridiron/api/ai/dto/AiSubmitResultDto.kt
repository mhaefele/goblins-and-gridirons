package com.mhaefele.gridiron.api.ai.dto

import com.mhaefele.gridiron.shared.LegalActionId

/** Submit extras plus [next] — [Obs] matches the step route ([nested] vs [packed]). */
data class AiSubmitResultDto<Obs>(
    val schemaVersion: Int = AiObsLayout.SCHEMA_VERSION,
    val chosenLegalActionId: LegalActionId,
    val scoring: AiScoringDeltaDto = AiScoringDeltaDto(),
    val afterSubmitRewards: HarnessRewardSnapshotDto?,
    val next: AiStepDto<Obs>,
) {
    init {
        require(schemaVersion == AiObsLayout.SCHEMA_VERSION) { "Unsupported AI submit schemaVersion=$schemaVersion" }
        require(next.schemaVersion == schemaVersion)
    }

    fun isEpisodeEnded(): Boolean = next.isEpisodeEnded()
}
