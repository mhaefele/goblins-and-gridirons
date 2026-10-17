export type PendingMatchSetup = {
    homeTeamId: string;
    awayTeamId: string;
    /** When set (e.g. Quick start), pre-select this pitch on the matchup screen. */
    pitchDefinitionId?: string;
};

export type Screen =
    | { name: "create"; step?: "home" | "away"; homeTeamId?: string; awayTeamId?: string }
    | ({ name: "matchup" } & PendingMatchSetup)
    | { name: "play"; matchId: string }
    | {
          name: "gameOver";
          matchId: string;
          homeScore?: number;
          awayScore?: number;
          turnNumber?: number;
          result?: { kind: "suddenDeath" | "pointsVictory"; winner: "HOME" | "AWAY" | null };
          /** Fallback when structured result is missing. */
          resultSummary?: string;
      }
    | { name: "pitchAlign" };

