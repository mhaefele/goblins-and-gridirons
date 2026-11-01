package com.mhaefele.gridiron.game.opponent.onnx.schema15

/**
 * Grep tag for the deletable schema-15 ONNX fork: **SCHEMA15_DELETE**
 *
 * When every classpath `ai-opponent/{id}/manifest.json` is schema 16+ (canary test fails):
 * 1. Delete this whole package: `game/opponent/onnx/schema15/`
 * 2. Delete tests under `src/test/.../opponent/onnx/schema15/`
 * 3. Grep **SCHEMA15_DELETE** and strip dual-path wiring in:
 *    - [com.mhaefele.gridiron.game.opponent.onnx.OnnxOpponentFactory]
 *    - [com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession]
 *    - [com.mhaefele.gridiron.api.ComputerOpponentController] (catalog KDoc)
 * 4. Keep schema-16 legal-row ONNX only.
 *
 * Do not reorder [Schema15LegalKind] while this package lives.
 */
@Suppress("unused")
object Schema15Delete {
    const val GREP_TAG = "SCHEMA15_DELETE"
}
