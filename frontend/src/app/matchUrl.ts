const MATCH_ID_PARAM = "matchId";

export function readMatchIdFromUrl(search = window.location.search): string | null {
    const value = new URLSearchParams(search).get(MATCH_ID_PARAM)?.trim();
    return value != null && value.length > 0 ? value : null;
}

/** Sync `?matchId=` with history without a full navigation. Pass null to clear. Preserves `?coach=`. */
export function syncMatchIdInUrl(matchId: string | null): void {
    const url = new URL(window.location.href);
    if (matchId == null) {
        url.searchParams.delete(MATCH_ID_PARAM);
    } else {
        url.searchParams.set(MATCH_ID_PARAM, matchId);
    }
    const next = `${url.pathname}${url.search}${url.hash}`;
    const current = `${window.location.pathname}${window.location.search}${window.location.hash}`;
    if (next !== current) {
        window.history.replaceState(null, "", next);
    }
}
