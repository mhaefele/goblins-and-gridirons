package com.mhaefele.gridiron.api.ai.export

import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import java.nio.file.Path

/** CLI knobs for an in-process bot dump. `null` team ids mean cycle the catalog. */
data class ExpertExportRequest(
    val games: Int,
    val threads: Int,
    val out: Path,
    val homeKind: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD,
    val awayKind: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD,
    val homeTeamId: TeamCatalogId? = null,
    val awayTeamId: TeamCatalogId? = null,
    val maxActions: Int = 400,
) {
    init {
        require(games >= 1) { "games must be ≥ 1, was $games" }
        require(threads >= 1) { "threads must be ≥ 1, was $threads" }
        require(maxActions >= 1) { "maxActions must be ≥ 1, was $maxActions" }
    }
}
