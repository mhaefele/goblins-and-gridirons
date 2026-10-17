package com.mhaefele.gridiron.integration.playthrough.arena.blindspot

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.MatchProgress
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.ChallengeCardDefinition
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.TeamSide

/** One model-vs-advisor disagreement with shared-dice forks through own turn (+ optional opp turn). */
data class BlindSpotFinding(
    val gameIndex: Int,
    val liveTurnNumber: Int,
    val focal: TeamSide,
    val splitIndexInTurn: Int,
    /** Active challenge tableau at the split (slot order; `—` = empty). */
    val activeChallenges: String,
    val modelAction: String,
    val advisorAction: String,
    val modelOwnTurnActions: List<String>,
    val advisorOwnTurnActions: List<String>,
    val modelPointsOwnTurn: Int,
    val advisorPointsOwnTurn: Int,
    val modelDiffOwnTurn: Int,
    val advisorDiffOwnTurn: Int,
    val modelDiffAfterOppTurn: Int,
    val advisorDiffAfterOppTurn: Int,
    /** Scoreboard events on the model fork through end of opponent turn (claims / TD / Clean Sweep). */
    val modelScoringThroughOpp: String,
    /** Scoreboard events on the advisor fork through end of opponent turn. */
    val advisorScoringThroughOpp: String,
    val diceSeed: Long,
) {
    val advisorBetterOwnTurn: Boolean
        get() = advisorPointsOwnTurn > modelPointsOwnTurn || advisorDiffOwnTurn > modelDiffOwnTurn

    val advisorBetterAfterOppTurn: Boolean
        get() = advisorDiffAfterOppTurn > modelDiffAfterOppTurn

    /** Worth logging as a blind-spot candidate. */
    val interesting: Boolean
        get() = advisorBetterOwnTurn || advisorBetterAfterOppTurn

    /** Coarse failure tags — see `ai/BLINDSPOTS.md`. */
    val tags: List<String>
        get() = classifyBlindSpot(this)
}

data class BlindSpotGameReport(
    val gameIndex: Int,
    val homeTeamId: TeamCatalogId,
    val awayTeamId: TeamCatalogId,
    val modelSeat: TeamSide,
    val disagreements: Int,
    val interesting: List<BlindSpotFinding>,
    val homeScore: Int,
    val awayScore: Int,
    val actionsPlayed: Int,
)

data class BlindSpotSeriesReport(
    val games: Int,
    val disagreements: Int,
    val interesting: Int,
    val findings: List<BlindSpotFinding>,
    val gamesDetail: List<BlindSpotGameReport>,
)

internal fun pointsFor(progress: MatchProgress, side: TeamSide): Int =
    when (side) {
        TeamSide.HOME -> progress.homeScore.points
        TeamSide.AWAY -> progress.awayScore.points
    }

internal fun touchdownsFor(progress: MatchProgress, side: TeamSide): Int =
    when (side) {
        TeamSide.HOME -> progress.homeTouchdowns
        TeamSide.AWAY -> progress.awayTouchdowns
    }

internal fun differential(progress: MatchProgress, side: TeamSide): Int =
    pointsFor(progress, side) - pointsFor(progress, side.opponent())

internal fun TeamSide.opponent(): TeamSide =
    when (this) {
        TeamSide.HOME -> TeamSide.AWAY
        TeamSide.AWAY -> TeamSide.HOME
    }

/** Compact action label — prefer id token; claims/replaces use the printed title. */
internal fun LegalAction.brief(): String =
    when (this) {
        is LegalAction.ClaimChallenge   -> "Claim(\"$challengeTitle\",+$points)"
        is LegalAction.ReplaceChallenge -> "Replace(\"$challengeTitle\")"
        else                            -> id.value
    }

/** Slot-order active tableau labels at a fork split (`—` for empty slots). */
internal fun formatActiveChallenges(cards: CardState): String =
    cards.activeSlots.joinToString(", ") { slot ->
        if (slot == null) "—"
        else "\"${slot.challenge.displayName}\"(+${slot.points})"
    }

/** Cards that entered [team]'s hand between two snapshots (auto-claim or coach ClaimChallenge). */
internal fun newlyClaimedCards(before: CardState, after: CardState, team: TeamSide): List<ChallengeCardDefinition> {
    val remaining = before.handCards(team).toMutableList()
    val newly = ArrayList<ChallengeCardDefinition>()
    for (card in after.handCards(team)) {
        if (!remaining.remove(card)) newly.add(card)
    }
    return newly
}

/**
 * Focal-perspective labels for scoreboard changes on one apply.
 * Challenge titles come from hand diffs — single-eligible cards auto-claim inside the
 * completing action (no separate [LegalAction.ClaimChallenge]).
 */
internal fun scoringNotes(
    before: MatchState,
    after: MatchState,
    focal: TeamSide,
): List<String> {
    val deltaUs = pointsFor(after.progress, focal) - pointsFor(before.progress, focal)
    val deltaThem = pointsFor(after.progress, focal.opponent()) - pointsFor(before.progress, focal.opponent())
    if (deltaUs == 0 && deltaThem == 0) return emptyList()

    val notes = ArrayList<String>(4)
    fun note(who: String, label: String, pts: Int) {
        if (pts == 0) return
        val signed = if (pts > 0) "+$pts" else "$pts"
        notes.add("$signed$who $label")
    }

    var remUs = deltaUs
    var remThem = deltaThem

    for (card in newlyClaimedCards(before.cards, after.cards, focal)) {
        note("us", "\"${card.challenge.displayName}\"", card.points)
        remUs -= card.points
    }
    for (card in newlyClaimedCards(before.cards, after.cards, focal.opponent())) {
        note("them", "\"${card.challenge.displayName}\"", card.points)
        remThem -= card.points
    }

    val tdUs = touchdownsFor(after.progress, focal) - touchdownsFor(before.progress, focal)
    val tdThem = touchdownsFor(after.progress, focal.opponent()) - touchdownsFor(before.progress, focal.opponent())
    if (tdUs > 0) {
        note("us", "Touchdown", 4 * tdUs)
        remUs -= 4 * tdUs
    }
    if (tdThem > 0) {
        note("them", "Touchdown", 4 * tdThem)
        remThem -= 4 * tdThem
    }

    when {
        remUs == 2 -> note("us", "Clean Sweep", 2)
        remUs != 0 -> note("us", "points", remUs)
    }
    when {
        remThem == 2 -> note("them", "Clean Sweep", 2)
        remThem != 0 -> note("them", "points", remThem)
    }
    return notes
}

/**
 * Loose-ball pickups on one apply (not throw/hand-off catches — those already had a carrier).
 * Shows on step paren notes; filtered out of [TurnForkPlayer.PathResult.scoringSummaryThroughOpp].
 */
internal fun possessionNotes(before: MatchState, after: MatchState, focal: TeamSide): List<String> {
    val notes = ArrayList<String>(2)
    for (player in after.board.players.values) {
        val ballId = player.heldBallId ?: continue
        if (before.board.players[player.id]?.heldBallId == ballId) continue
        if (before.board.players.values.any { it.heldBallId == ballId }) continue
        val label = if (player.team == focal) "pickup" else "them pickup"
        notes.add("$label ${player.id.value}")
    }
    return notes
}

/** Scoreboard-shaped notes (`+4us Touchdown`, …) — excludes beat notes like `pickup h4`. */
internal fun String.isScoringNote(): Boolean =
    startsWith("+") || startsWith("-") || firstOrNull()?.isDigit() == true

/** Parse `+Nus …` fragments from a scoring summary. */
internal fun usScoring(summary: String): List<String> =
    if (summary == "(none)") emptyList()
    else summary.split(", ").map { it.trim() }.filter { it.contains("us ") }

/** Parse `+Nthem …` fragments from a scoring summary. */
internal fun themScoring(summary: String): List<String> =
    if (summary == "(none)") emptyList()
    else summary.split(", ").map { it.trim() }.filter { it.contains("them ") }

/**
 * Tag interesting findings for `ai/BLINDSPOTS.md` taxonomy.
 * Heuristic from scoring summaries + opening action kinds — not a full causal diagnosis.
 *
 * Family tags (always when a leaf matches):
 * - `offense-miss` — we scored less (TD / claim / Clean Sweep) than the advisor fork
 * - `defense-leak` — they scored more (TD / claim) on our fork than on the advisor fork
 * Structural tags (`tableau-replace`, `bonus-choice`) are orthogonal.
 */
internal fun classifyBlindSpot(f: BlindSpotFinding): List<String> {
    val tags = LinkedHashSet<String>()
    val modelUs = usScoring(f.modelScoringThroughOpp)
    val advisorUs = usScoring(f.advisorScoringThroughOpp)
    val modelThem = themScoring(f.modelScoringThroughOpp)
    val advisorThem = themScoring(f.advisorScoringThroughOpp)

    val modelTdUs = modelUs.count { "Touchdown" in it }
    val advisorTdUs = advisorUs.count { "Touchdown" in it }
    if (advisorTdUs > modelTdUs) tags.add("missed-td")

    val modelTdThem = modelThem.count { "Touchdown" in it }
    val advisorTdThem = advisorThem.count { "Touchdown" in it }
    if (modelTdThem > advisorTdThem) tags.add("left-td-open")

    val modelClaims = modelUs.filter { "\"" in it }.toSet()
    val advisorClaims = advisorUs.filter { "\"" in it }
    if (advisorClaims.any { it !in modelClaims }) tags.add("missed-claim")

    val modelThemClaims = modelThem.filter { "\"" in it }.toSet()
    val advisorThemClaims = advisorThem.filter { "\"" in it }
    if (modelThemClaims.any { it !in advisorThemClaims } || modelThemClaims.size > advisorThemClaims.size) {
        tags.add("left-claim-open")
    }

    if (advisorUs.any { "Clean Sweep" in it } && modelUs.none { "Clean Sweep" in it }) {
        tags.add("missed-clean-sweep")
    }

    if (f.modelAction.startsWith("Replace") || f.advisorAction.startsWith("Replace")) {
        tags.add("tableau-replace")
    }

    fun looksBonus(s: String): Boolean =
        s.startsWith("Play") || s.startsWith("PLAY") || s.startsWith("Decline") ||
            s.startsWith("Adjust") || s.startsWith("ADJUST")

    if (looksBonus(f.modelAction) || looksBonus(f.advisorAction)) tags.add("bonus-choice")

    val offense = listOf("missed-td", "missed-claim", "missed-clean-sweep")
    val defense = listOf("left-td-open", "left-claim-open")
    if (tags.any { it in offense }) tags.add("offense-miss")
    if (tags.any { it in defense }) tags.add("defense-leak")

    if (tags.none { it in offense || it in defense || it == "tableau-replace" || it == "bonus-choice" }) {
        if (f.advisorBetterOwnTurn) tags.add("turn-shape")
        if (f.advisorBetterAfterOppTurn && !f.advisorBetterOwnTurn) tags.add("after-opp-only")
    }

    return tags.toList()
}
