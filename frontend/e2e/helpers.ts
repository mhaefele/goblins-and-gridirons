import { expect, type Page } from "@playwright/test";

export async function assertOccupancyConsistent(page: Page) {
    const snapshot = await page.evaluate(async () => {
        const root = document.querySelector("[data-match-id]");
        const matchId = root?.getAttribute("data-match-id");
        if (matchId == null) return null;
        const response = await fetch(`/api/matches/${encodeURIComponent(matchId)}/snapshot`);
        if (!response.ok) return null;
        return response.json() as Promise<{
            pieces: { players: Array<{ at: { x: number; y: number } | null; status: string }> };
        }>;
    });

    if (snapshot == null) return;

    const occupied = snapshot.pieces.players
        .filter((player) => player.status !== "RESERVES" && player.at != null)
        .map((player) => `${player.at!.x},${player.at!.y}`);
    expect(new Set(occupied).size).toBe(occupied.length);
}
