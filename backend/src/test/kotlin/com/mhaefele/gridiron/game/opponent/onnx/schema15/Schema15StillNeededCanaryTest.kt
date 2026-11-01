package com.mhaefele.gridiron.game.opponent.onnx.schema15

import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.shared.OnnxOpponentProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * SCHEMA15_DELETE — delete this test with the schema15 package (see Schema15Delete; grep SCHEMA15_DELETE).
 *
 * Intentionally fails once every classpath ONNX has moved off schema 15 — that is the cue to
 * delete the fork.
 */
class Schema15StillNeededCanaryTest : BaseApiTest() {
    @Autowired
    private lateinit var policy: OnnxPolicySession

    @Test
    fun `classpath still has at least one schema-15 ONNX — otherwise delete SCHEMA15_DELETE fork`() {
        val present = OnnxOpponentProfile.entries.filter { policy.artifactsPresent(it) }
        assumeTrue(present.isNotEmpty()) {
            "no classpath ai-opponent artifacts — skip canary (run ai/export_opponent.sh)"
        }
        val schema15 = present.filter { policy.manifestSchemaVersion(it) == Schema15Layout.SCHEMA_VERSION }
        assertThat(schema15)
            .withFailMessage(
                """
                SCHEMA15_DELETE: no classpath ONNX remains on schemaVersion=15 (all re-exported).
                Delete the schema-15 fork now:
                  1. rm -rf backend/src/main/kotlin/.../opponent/onnx/schema15/
                  2. rm -rf backend/src/test/kotlin/.../opponent/onnx/schema15/
                  3. grep -n SCHEMA15_DELETE backend/ and strip dual-path wiring
                     (OnnxOpponentFactory, OnnxPolicySession, ComputerOpponentController KDoc)
                  4. Keep schema-16 legal-row ONNX only.
                See Schema15Delete.kt for the checklist.
                """.trimIndent(),
            )
            .isNotEmpty()
    }
}
