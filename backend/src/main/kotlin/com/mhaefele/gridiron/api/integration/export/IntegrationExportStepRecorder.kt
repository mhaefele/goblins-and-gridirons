package com.mhaefele.gridiron.api.integration.export

import com.mhaefele.gridiron.api.ai.export.ExpertExportRequest
import com.mhaefele.gridiron.api.ai.export.ExportStepRecorder
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.integration.dto.IntegrationMatchStateDto
import com.mhaefele.gridiron.api.integration.mapping.toIntegrationMatchSnapshotDto
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@Component
class IntegrationExportStepRecorder : ExportStepRecorder<IntegrationMatchStateDto> {
    override fun openWriter(request: ExpertExportRequest): BufferedWriter =
        Files.newBufferedWriter(request.out, StandardCharsets.UTF_8)

    override fun recordStep(state: MatchState, legal: List<LegalAction>, chosen: LegalAction, acting: TeamSide, events: List<StoredDomainEvent>): IntegrationMatchStateDto =
        IntegrationMatchStateDto(
            snapshot = toIntegrationMatchSnapshotDto(state, acting),
            legalActions = legal,
            actingSeat = acting,
            chosenLegalActionId = chosen.id,
            events = events,
        )
}
