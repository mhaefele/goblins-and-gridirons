package com.mhaefele.gridiron.shared

/**
 * Product ONNX opponent profiles — folder name under ``ai-opponent/{id}/``.
 * Catalog only lists entries whose classpath artifacts exist (see [com.mhaefele.gridiron.api.ComputerOpponentController]).
 */
enum class OnnxOpponentProfile(
    /** Classpath folder id — must match ``ai-opponent/{id}/``. */
    val id: String,
    val title: String,
    val description: String,
) {
    BASELINE(
        id = "baseline",
        title = "Baseline",
        description =
            "Unshaped control: step reward = Δscore-margin + terminal W/L/D only (RewardShaping " + "all zeros). Same MaskablePPO trunk / obs packing as the specialists — no ball, " + "block, coverage, or TD micro-shaping during training. Uses the post-split " + "scenario_b long-running checkpoint(summary \"baseline\" row).",),
    GENERAL(
        id = "general",
        title = "General",
        description =
            "Shared light shaping on margin+terminal: idle-turn penalty, ball possession / " + "approach / us-gained-ball, uncertain-challenge, them-lost-ball, any-block + " + "block-carrier, ball-carrier-action, empty scoring-endzone penalty. Default " + "all-round specialist.",),
    SPREAD(
        id = "spread",
        title = "Spread",
        description =
            "General’s ball/challenge/block base plus covering_radius_delta_bonus — rewards " + "shrinking max-over-pitch of min Manhattan distance to any US player(wider field " + "coverage), signed so peel-then-walk-back nets ~0.",),
    TD(
        id = "td",
        title = "Touchdown",
        description =
            "TD farmer: loud but sub-scoreboard shaping — touchdown_bonus 3.0, stronger ball " + "hold(possession 0.4 with Δendzone proximity while carrying), approach_ball 0.2, " + "quieter any_block. Critic still tracks margin; eval runs shaping-off.",),
    DEFENSE(
        id = "defense",
        title = "Defense",
        description =
            "Strip / punish carrier: them_gained_ball_penalty, them_holds_ball_step_penalty, " + "them_lost_ball, opponent_dugout_delta, heavy block_ball_carrier(+ any_block), " + "block_non_carrier_penalty when they hold. Same idle/approach/us-gained base as general.",),
    ;

    companion object {
        fun fromId(id: String): OnnxOpponentProfile? = entries.find { it.id == id }
    }
}
