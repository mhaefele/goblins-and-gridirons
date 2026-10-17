package com.mhaefele.gridiron.api.ai.export

import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Headless dump: `--export-expert` (or `gridiron.expert-export.enabled=true`).
 *
 * Short flags: `--games`, `--threads`, `--out`, `--home`, `--away`, `--home-team`, `--away-team`.
 * Bean is off unless the property is true so `mvn test` never [exitProcess].
 */
@Component
@ConditionalOnProperty(name = ["gridiron.expert-export.enabled"], havingValue = "true")
class ExpertExportCommand(
    private val exporter: ExpertGameExporter,
    private val recorder: ExpertExportStepRecorder,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        var code = 1
        try {
            exporter.export(parse(args), recorder)
            code = 0
        } catch (ex: Exception) {
            log.error("expert-export failed", ex)
        } finally {
            exitProcess(SpringApplication.exit(context, ExitCodeGenerator { code }))
        }
    }

    private fun parse(args: ApplicationArguments): ExpertExportRequest {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        return ExpertExportRequest(
            games = option(args, "games")?.toInt() ?: 240,
            threads = option(args, "threads")?.toInt() ?: cpus,
            out = Path.of(option(args, "out") ?: "../ai/output/expert/lookahead.jsonl.gz"),
            homeKind = ComputerOpponentKind.valueOf((option(args, "home") ?: "LOOKAHEAD").uppercase()),
            awayKind = ComputerOpponentKind.valueOf((option(args, "away") ?: "LOOKAHEAD").uppercase()),
            homeTeamId = option(args, "home-team")?.let(::TeamCatalogId),
            awayTeamId = option(args, "away-team")?.let(::TeamCatalogId),
        )
    }

    private fun option(args: ApplicationArguments, name: String): String? = args.getOptionValues(name)?.last()

    private companion object {
        private val log = LoggerFactory.getLogger(ExpertExportCommand::class.java)
    }
}
