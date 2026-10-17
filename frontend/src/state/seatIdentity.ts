import type { MatchGameStateDto } from "../api/client";
import type { BoardView } from "./boardView";

export type CoachSeat = "HOME" | "AWAY";

/** Optional `?coach=HOME|AWAY` — this browser's seat when set and that seat is human. */
export function readLocalCoachFromUrl(search = window.location.search): CoachSeat | null {
    const raw = new URLSearchParams(search).get("coach")?.trim().toUpperCase();
    if (raw === "HOME" || raw === "AWAY") return raw;
    return null;
}

export function isHumanSeat(gameState: MatchGameStateDto, team: CoachSeat): boolean {
    const control = team === "HOME" ? gameState.homeControlledBy : gameState.awayControlledBy;
    return control === "HUMAN";
}

export function isHotseat(gameState: MatchGameStateDto): boolean {
    return isHumanSeat(gameState, "HOME") && isHumanSeat(gameState, "AWAY");
}

/** Both seats human and no `?coach=` — shared controls on one browser. */
export function isSharedHotseat(gameState: MatchGameStateDto, preferred: CoachSeat | null): boolean {
    return isHotseat(gameState) && preferred == null;
}

/**
 * Whether this browser should load/submit legal actions for `team`.
 *
 * - `?coach=` set → only that seat (other HUMAN may be driven by an external agent)
 * - else any HUMAN seat (vs-computer or true hotseat)
 */
export function thisBrowserActsFor(
    gameState: MatchGameStateDto,
    team: CoachSeat,
    preferred: CoachSeat | null,
): boolean {
    if (!isHumanSeat(gameState, team)) return false;
    if (preferred != null) return preferred === team;
    return true;
}

/**
 * This client's seat for HUD / bonus hand / ability banners.
 *
 * - `preferred` from `?coach=` when that seat is HUMAN
 * - else the sole HUMAN seat (vs-computer)
 * - else hotseat: follow `activeCoach` (shared controls on one browser)
 */
export function resolveLocalCoach(view: BoardView, preferred: CoachSeat | null): CoachSeat {
    const { gameState } = view;
    if (preferred != null && isHumanSeat(gameState, preferred)) return preferred;
    const home = isHumanSeat(gameState, "HOME");
    const away = isHumanSeat(gameState, "AWAY");
    if (home && !away) return "HOME";
    if (away && !home) return "AWAY";
    return gameState.activeCoach;
}

/** True when this seat should show the "You" chip (not shared hotseat labeling). */
export function seatIsYou(
    gameState: MatchGameStateDto,
    team: CoachSeat,
    localCoach: CoachSeat,
    preferred: CoachSeat | null = null,
): boolean {
    if (!isHumanSeat(gameState, team)) return false;
    if (preferred != null) return team === preferred;
    if (isHotseat(gameState)) return false;
    return team === localCoach;
}
