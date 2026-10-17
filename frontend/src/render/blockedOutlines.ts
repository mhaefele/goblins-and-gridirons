import type { Coordinate } from "../api/client";
import { coordKey } from "./pitchLayout";

/** Outer-edge segments for contiguous blocked regions — no shared internal edges. */
export function blockedPerimeterSegments(blockedSquares: Coordinate[]): Array<{ x1: number; y1: number; x2: number; y2: number }> {
    const blocked = new Set(blockedSquares.map(coordKey));
    const segments: Array<{ x1: number; y1: number; x2: number; y2: number }> = [];

    for (const square of blockedSquares) {
        const { x, y } = square;
        if (!blocked.has(coordKey({ x, y: y - 1 }))) segments.push({ x1: x, y1: y, x2: x + 1, y2: y });
        if (!blocked.has(coordKey({ x, y: y + 1 }))) segments.push({ x1: x, y1: y + 1, x2: x + 1, y2: y + 1 });
        if (!blocked.has(coordKey({ x: x - 1, y }))) segments.push({ x1: x, y1: y, x2: x, y2: y + 1 });
        if (!blocked.has(coordKey({ x: x + 1, y }))) segments.push({ x1: x + 1, y1: y, x2: x + 1, y2: y + 1 });
    }

    return segments;
}
