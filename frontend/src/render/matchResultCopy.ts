export type MatchResultSummary = {
    kind: "suddenDeath" | "pointsVictory";
    winner: "HOME" | "AWAY" | null;
};

function sideLabel(side: "HOME" | "AWAY"): string {
    return side === "HOME" ? "Home" : "Away";
}

/** Short HUD / status line: "Sudden death — Home wins". */
export function formatMatchResult(result: MatchResultSummary): string {
    const kind = result.kind === "suddenDeath" ? "Sudden death" : "Points victory";
    if (result.winner == null) return `${kind} — draw`;
    return `${kind} — ${sideLabel(result.winner)} wins`;
}

/** Primary game-over headline: who won. */
export function formatMatchResultHeadline(result: MatchResultSummary): string {
    if (result.winner == null) return "Draw";
    return `${sideLabel(result.winner)} wins`;
}

/**
 * Rulebook-flavored explanation of *why* the match ended.
 * Scores are optional but improve Sudden Death / Points Victory copy when present.
 */
export function formatMatchResultDetail(
    result: MatchResultSummary,
    scores?: { home: number; away: number },
): string {
    const lead =
        scores != null ? Math.abs(scores.home - scores.away) : null;
    const scorePhrase =
        scores != null ? `${scores.home}–${scores.away}` : null;

    switch (result.kind) {
        case "suddenDeath": {
            if (result.winner == null) {
                return "Sudden Death ended the match.";
            }
            const winner = sideLabel(result.winner);
            if (lead != null && scorePhrase != null) {
                return `${winner} led by ${lead} (${scorePhrase}) at the start of their turn — a 10+ point lead ends the match by Sudden Death.`;
            }
            return `${winner} led by 10 or more points at the start of their turn, winning by Sudden Death.`;
        }
        case "pointsVictory": {
            const base =
                "After the last Challenge Card was dealt, each team took one more turn";
            if (result.winner == null) {
                return scorePhrase != null
                    ? `${base}. Final score ${scorePhrase} — draw.`
                    : `${base}. Scores were tied.`;
            }
            return scorePhrase != null
                ? `${base}. ${sideLabel(result.winner)} finished ahead ${scorePhrase}.`
                : `${base}. ${sideLabel(result.winner)} finished with the higher score.`;
        }
    }
}
