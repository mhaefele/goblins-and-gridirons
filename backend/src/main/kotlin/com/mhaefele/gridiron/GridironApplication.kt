package com.mhaefele.gridiron

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GridironApplication

fun main(args: Array<String>) {
    val expertExport = args.any { it == "--export-expert" || it.startsWith("--export-expert=") } ||
        args.any { it.startsWith("--gridiron.expert-export.enabled=") && it.substringAfter('=').equals("true", ignoreCase = true) }
    val integrationExport = args.any { it == "--export-integration" || it.startsWith("--export-integration=") } ||
        args.any { it.startsWith("--gridiron.integration-export.enabled=") && it.substringAfter('=').equals("true", ignoreCase = true) }
    val headless = expertExport || integrationExport
    val forwarded = buildList {
        addAll(args.filter { it != "--export-expert" && it != "--export-integration" })
        if (expertExport && none { it.startsWith("--gridiron.expert-export.enabled=") }) add("--gridiron.expert-export.enabled=true")
        if (integrationExport && none { it.startsWith("--gridiron.integration-export.enabled=") }) add("--gridiron.integration-export.enabled=true")
        if (headless && none { it.startsWith("--spring.main.web-application-type=") }) add("--spring.main.web-application-type=none")
    }.toTypedArray()
    runApplication<GridironApplication>(*forwarded)
}
