import type { Coordinate } from "../api/client";
import { coordKey } from "./pitchLayout";

export type BlockedCluster = {
    /** Top-left cell of the cluster. */
    origin: Coordinate;
    width: number;
    height: number;
};

function hasUnclaimed(blocked: Set<string>, claimed: Set<string>, x: number, y: number): boolean {
    const key = coordKey({ x, y });
    return blocked.has(key) && !claimed.has(key);
}

function claimRect(claimed: Set<string>, x: number, y: number, width: number, height: number) {
    for (let dy = 0; dy < height; dy++) {
        for (let dx = 0; dx < width; dx++) {
            claimed.add(coordKey({ x: x + dx, y: y + dy }));
        }
    }
}

/** Pack blocked cells into 2×2, then 2×1 / 1×2, then 1×1 covers (greedy, scan order). */
export function blockedClusters(blockedSquares: Coordinate[]): BlockedCluster[] {
    const blocked = new Set(blockedSquares.map(coordKey));
    const claimed = new Set<string>();
    const clusters: BlockedCluster[] = [];
    const sorted = [...blockedSquares].sort((a, b) => a.y - b.y || a.x - b.x);

    for (const { x, y } of sorted) {
        if (!hasUnclaimed(blocked, claimed, x, y)) continue;
        if (
            hasUnclaimed(blocked, claimed, x + 1, y) &&
            hasUnclaimed(blocked, claimed, x, y + 1) &&
            hasUnclaimed(blocked, claimed, x + 1, y + 1)
        ) {
            clusters.push({ origin: { x, y }, width: 2, height: 2 });
            claimRect(claimed, x, y, 2, 2);
            continue;
        }
        if (hasUnclaimed(blocked, claimed, x + 1, y)) {
            clusters.push({ origin: { x, y }, width: 2, height: 1 });
            claimRect(claimed, x, y, 2, 1);
            continue;
        }
        if (hasUnclaimed(blocked, claimed, x, y + 1)) {
            clusters.push({ origin: { x, y }, width: 1, height: 2 });
            claimRect(claimed, x, y, 1, 2);
            continue;
        }
        clusters.push({ origin: { x, y }, width: 1, height: 1 });
        claimRect(claimed, x, y, 1, 1);
    }

    return clusters;
}
