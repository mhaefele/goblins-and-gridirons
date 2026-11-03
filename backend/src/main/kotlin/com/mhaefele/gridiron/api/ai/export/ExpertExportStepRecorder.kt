package com.mhaefele.gridiron.api.ai.export

import com.mhaefele.gridiron.api.ai.dto.packed.ExpertStepRecord
import com.mhaefele.gridiron.api.ai.mapping.AiPackedMapper
import com.mhaefele.gridiron.api.ai.mapping.AiStepMapper
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

@Component
class ExpertExportStepRecorder(
    private val steps: AiStepMapper,
    private val packed: AiPackedMapper,
) : ExportStepRecorder<ExpertStepRecord> {
    override fun openWriter(request: ExpertExportRequest): BufferedWriter {
        val gzip = GZIPOutputStream(Files.newOutputStream(request.out))
        return BufferedWriter(OutputStreamWriter(gzip, StandardCharsets.UTF_8), 256 * 1024)
    }

    override fun recordStep(state: MatchState, legal: List<LegalAction>, chosen: LegalAction, acting: TeamSide, events: List<StoredDomainEvent>): ExpertStepRecord {
        val step = steps.toAiStepDto(state, legal, perspectiveSeat = acting)
        val chosenIndex = step.harness.legalRows.indexOfFirst { it.action.id == chosen.id }
        check(chosenIndex >= 0) { "chosen ${chosen.id.value} missing from legalRows" }
        return ExpertStepRecord(
            policy = packed.pack(step).policy,
            chosenLegalActionId = chosen.id,
            chosenFactors = step.policy.legalMoves[chosenIndex],
        )
    }
}
