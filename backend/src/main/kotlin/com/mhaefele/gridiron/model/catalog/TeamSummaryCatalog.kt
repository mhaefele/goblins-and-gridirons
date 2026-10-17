package com.mhaefele.gridiron.model.catalog

import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.module.SimpleModule
import tools.jackson.module.kotlin.jacksonTypeRef
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** Playstyle blurbs keyed by [TeamCard.id] — ingested from classpath JSON. */
object TeamSummaryCatalog {
    private val byId: Map<TeamCatalogId, TeamSummary> = load()

    fun summary(teamId: TeamCatalogId): TeamSummary =
        checkNotNull(byId[teamId]) { "Missing team summary for '${teamId.value}'" }

    fun allSummaries(): List<TeamSummary> = byId.values.toList()

    private fun load(): Map<TeamCatalogId, TeamSummary> {
        val stream = checkNotNull(TeamSummaryCatalog::class.java.getResourceAsStream("/catalog/team-summaries.json")) { "Missing classpath resource /catalog/team-summaries.json" }
        return stream.use { input ->
            teamSummaryMapper()
                .readValue(input, jacksonTypeRef<List<TeamSummary>>())
                .associateBy { it.id }
        }
    }

    /** Classpath JSON uses plain string ids; Spring wire shape uses [JacksonConfiguration]. */
    private fun teamSummaryMapper() =
        jsonMapper {
            addModule(kotlinModule())
            addModule(
                SimpleModule().addDeserializer(
                    TeamCatalogId::class.java,
                    object : StdDeserializer<TeamCatalogId>(TeamCatalogId::class.java) {
                        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): TeamCatalogId =
                            TeamCatalogId(p.valueAsString)
                    },
                ),
            )
        }
}
