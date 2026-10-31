package com.mhaefele.gridiron.shared

/**
 * Which in-process [com.mhaefele.gridiron.game.opponent.OpponentStrategy] to use: spawn for
 * [CoachControl.COMPUTER] seats at create. Not stored on MatchConfig.
 *
 * [title] / [description] are product copy for MatchupSplash / [com.mhaefele.gridiron.api.ComputerOpponentController].
 */
enum class ComputerOpponentKind(
    val title: String,
    val description: String,
) {
    LOOKAHEAD(
        title = "Lookahead",
        description =
            "Shallow beam search (width 6, depth 2) over mostly-deterministic legals — path/mark/" + "handoff/claim — dry-run on a private ScriptedDiceRoller. Dicey blocks/bombs and " + "bonus/do-over prompts fall through to the heuristic. Think budget ~300ms vs human, " + "~100ms computer-vs-computer.",),
    HEURISTIC(
        title = "Heuristic",
        description =
            "Hand-authored priority script: claim/bonus first, then ball-chase, carrier run/" + "handoff, mark/block, stand prone. One board scan + BFS per branch — no per-candidate " + "pathfinding, no search tree. Cheapest CPU of the three.",),
    ONNX(
        title = "Trained model",
        description =
            "MaskablePPO actor exported to ONNX: packed obs + legal-move matrix → row scores, " + "then Kotlin picks among currently legal rows (same join-score contract as training). " + "Profiles differ only by the reward shaping used while training that checkpoint — " + "pick one below. Eval-time shaping is off; the policy weights carry the bias.",),
}
