import { describe, expect, it } from "vitest";
import { blockedClusters } from "./blockedClusters";

describe("blockedClusters", () => {
    it("packs a 2×2 pillar into one cluster", () => {
        expect(
            blockedClusters([
                { x: 3, y: 4 },
                { x: 4, y: 4 },
                { x: 3, y: 5 },
                { x: 4, y: 5 },
            ]),
        ).toEqual([{ origin: { x: 3, y: 4 }, width: 2, height: 2 }]);
    });

    it("packs four default dungeon pillars as four 2×2 clusters", () => {
        const clusters = blockedClusters([
            { x: 3, y: 4 },
            { x: 4, y: 4 },
            { x: 3, y: 5 },
            { x: 4, y: 5 },
            { x: 8, y: 4 },
            { x: 9, y: 4 },
            { x: 8, y: 5 },
            { x: 9, y: 5 },
            { x: 3, y: 11 },
            { x: 4, y: 11 },
            { x: 3, y: 12 },
            { x: 4, y: 12 },
            { x: 8, y: 11 },
            { x: 9, y: 11 },
            { x: 8, y: 12 },
            { x: 9, y: 12 },
        ]);
        expect(clusters).toHaveLength(4);
        expect(clusters.every((cluster) => cluster.width === 2 && cluster.height === 2)).toBe(true);
    });

    it("falls back to 2×1 pairs and 1×1 singles for barricade layouts", () => {
        const clusters = blockedClusters([
            { x: 6, y: 3 },
            { x: 3, y: 5 },
            { x: 4, y: 5 },
            { x: 6, y: 8 },
        ]);
        expect(clusters).toEqual([
            { origin: { x: 6, y: 3 }, width: 1, height: 1 },
            { origin: { x: 3, y: 5 }, width: 2, height: 1 },
            { origin: { x: 6, y: 8 }, width: 1, height: 1 },
        ]);
    });
});
