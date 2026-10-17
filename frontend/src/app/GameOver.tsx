import {
    formatMatchResultDetail,
    formatMatchResultHeadline,
    type MatchResultSummary,
} from "../render/matchResultCopy";

type GameOverProps = {
    matchId: string;
    homeScore?: number | undefined;
    awayScore?: number | undefined;
    turnNumber?: number | undefined;
    result?: MatchResultSummary | null | undefined;
    /** Fallback when structured result is missing. */
    resultSummary?: string | undefined;
    onNewMatch: () => void;
};

export default function GameOver({
    matchId,
    homeScore,
    awayScore,
    turnNumber,
    result,
    resultSummary,
    onNewMatch,
}: GameOverProps) {
    const hasScores = homeScore != null && awayScore != null;
    const headline = result != null ? formatMatchResultHeadline(result) : null;
    const detail =
        result != null
            ? formatMatchResultDetail(
                  result,
                  hasScores ? { home: homeScore, away: awayScore } : undefined,
              )
            : resultSummary != null && resultSummary !== "Game over"
              ? resultSummary
              : null;

    return (
        <main className="screen game-over">
            <h1>Game over</h1>
            {headline ? <p className="game-over__headline">{headline}</p> : null}
            <p className="game-over__score" role="status">
                {hasScores ? (
                    <>
                        Home {homeScore} — Away {awayScore}
                    </>
                ) : (
                    <>Match {matchId}</>
                )}
            </p>
            {detail ? <p className="game-over__result">{detail}</p> : null}
            {turnNumber != null ? (
                <p className="game-over__meta">Ended on turn {turnNumber}</p>
            ) : null}
            <button type="button" onClick={onNewMatch}>
                New match
            </button>
        </main>
    );
}
