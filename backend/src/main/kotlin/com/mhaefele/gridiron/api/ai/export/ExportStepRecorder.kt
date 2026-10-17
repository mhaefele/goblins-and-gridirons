package com.mhaefele.gridiron.api.ai.export

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide
import java.io.BufferedWriter

/** JSONL sink + one row shape for a headless bot dump. */
interface ExportStepRecorder<T> {
    fun openWriter(request: ExpertExportRequest): BufferedWriter

    fun recordStep(state: MatchState, legal: List<LegalAction>, chosen: LegalAction, acting: TeamSide, events: List<StoredDomainEvent>): T
}
