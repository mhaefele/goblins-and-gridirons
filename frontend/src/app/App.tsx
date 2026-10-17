import { useState } from "react";
import CreateMatch from "./CreateMatch";
import GameOver from "./GameOver";
import { readMatchIdFromUrl, syncMatchIdInUrl } from "./matchUrl";
import MatchupSplash from "./MatchupSplash";
import PitchAlignDebug, { isPitchAlignPath, PITCH_ALIGN_PATH } from "./PitchAlignDebug";
import Play from "./Play";
import type { Screen } from "./screens";

function syncPitchAlignPath(active: boolean): void {
    const url = new URL(window.location.href);
    const desired = active ? PITCH_ALIGN_PATH : "/";
    if (url.pathname === desired) return;
    url.pathname = desired;
    if (active) {
        url.search = "";
    }
    window.history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
}

function initialScreen(): Screen {
    if (isPitchAlignPath()) return { name: "pitchAlign" };
    const matchId = readMatchIdFromUrl();
    return matchId != null ? { name: "play", matchId } : { name: "create" };
}

export default function App() {
    const [screen, setScreenState] = useState<Screen>(initialScreen);

    function setScreen(next: Screen) {
        if (next.name === "pitchAlign") {
            syncMatchIdInUrl(null);
            syncPitchAlignPath(true);
        } else if (next.name === "create" || next.name === "matchup") {
            syncMatchIdInUrl(null);
            syncPitchAlignPath(false);
        } else {
            syncPitchAlignPath(false);
            syncMatchIdInUrl(next.matchId);
        }
        setScreenState(next);
    }

    switch (screen.name) {
        case "create":
            return (
                <CreateMatch
                    initialStep={screen.step}
                    initialHomeTeamId={screen.homeTeamId}
                    initialAwayTeamId={screen.awayTeamId}
                    onHomeAwayReady={(setup) =>
                        setScreen({
                            name: "matchup",
                            ...setup,
                        })
                    }
                />
            );
        case "matchup":
            return (
                <MatchupSplash
                    setup={screen}
                    onStarted={(matchId) => setScreen({ name: "play", matchId })}
                    onBack={() =>
                        setScreen({
                            name: "create",
                            step: "away",
                            homeTeamId: screen.homeTeamId,
                            awayTeamId: screen.awayTeamId,
                        })
                    }
                />
            );
        case "play":
            return (
                <Play
                    key={screen.matchId}
                    matchId={screen.matchId}
                    onMatchUnavailable={() => setScreen({ name: "create" })}
                    onGameOver={(summary) =>
                        setScreen({
                            name: "gameOver",
                            matchId: screen.matchId,
                            ...(summary?.homeScore != null ? { homeScore: summary.homeScore } : {}),
                            ...(summary?.awayScore != null ? { awayScore: summary.awayScore } : {}),
                            ...(summary?.turnNumber != null ? { turnNumber: summary.turnNumber } : {}),
                            ...(summary?.result != null ? { result: summary.result } : {}),
                            ...(summary?.resultSummary != null ? { resultSummary: summary.resultSummary } : {}),
                        })
                    }
                />
            );
        case "gameOver":
            return (
                <GameOver
                    matchId={screen.matchId}
                    homeScore={screen.homeScore}
                    awayScore={screen.awayScore}
                    turnNumber={screen.turnNumber}
                    result={screen.result}
                    resultSummary={screen.resultSummary}
                    onNewMatch={() => setScreen({ name: "create" })}
                />
            );
        case "pitchAlign":
            return <PitchAlignDebug />;
        default: {
            const _exhaustive: never = screen;
            return _exhaustive;
        }
    }
}
