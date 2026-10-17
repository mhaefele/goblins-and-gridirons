import { describe, expect, it } from "vitest";
import { blockedPerimeterSegments } from "./blockedOutlines";

describe("blockedPerimeterSegments", () => {
    it("draws four edges for an isolated square", () => {
        const segments = blockedPerimeterSegments([{ x: 3, y: 4 }]);
        expect(segments).toHaveLength(4);
    });

    it("omits the shared edge between two adjacent squares", () => {
        const segments = blockedPerimeterSegments([
            { x: 3, y: 4 },
            { x: 4, y: 4 },
        ]);
        // Two cells sharing a vertical edge → 6 outer edges, not 8.
        expect(segments).toHaveLength(6);
        expect(segments).not.toContainEqual({ x1: 4, y1: 4, x2: 4, y2: 5 });
    });

    it("outlines a 2×2 block as one outer square (8 edges)", () => {
        const segments = blockedPerimeterSegments([
            { x: 3, y: 4 },
            { x: 4, y: 4 },
            { x: 3, y: 5 },
            { x: 4, y: 5 },
        ]);
        expect(segments).toHaveLength(8);
    });

    it("keeps separate islands as separate perimeters", () => {
        const segments = blockedPerimeterSegments([
            { x: 1, y: 1 },
            { x: 1, y: 2 },
            { x: 5, y: 5 },
        ]);
        // Vertical pair → 6 edges; singleton → 4; total 10
        expect(segments).toHaveLength(10);
    });
});
