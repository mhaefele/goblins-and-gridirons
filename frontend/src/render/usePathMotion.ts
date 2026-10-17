import { useEffect, useState } from "react";
import type { Coordinate } from "../api/client";
import { squareCenter } from "./pitchLayout";

export type PathMotionStyle = "straight" | "arc" | "bounce";

function easeOutCubic(t: number): number {
    return 1 - (1 - t) ** 3;
}

/** Peak lift in pitch units for a throw arc between two squares. */
export function throwArcHeight(from: Coordinate, to: Coordinate): number {
    const dx = Math.abs(to.x - from.x);
    const dy = Math.abs(to.y - from.y);
    const range = Math.max(dx, dy, 1);
    return Math.min(1.35, 0.45 + range * 0.22);
}

/** Short hop lift for bounce flights. */
export function bounceHopHeight(): number {
    return 0.38;
}

/** SVG quadratic path for a hover/flight arc between square centers. */
export function throwArcPathD(from: Coordinate, to: Coordinate): string {
    const a = squareCenter(from.x, from.y);
    const b = squareCenter(to.x, to.y);
    const midX = (a.cx + b.cx) / 2;
    const midY = (a.cy + b.cy) / 2 - throwArcHeight(from, to);
    return `M ${a.cx} ${a.cy} Q ${midX} ${midY} ${b.cx} ${b.cy}`;
}

function pointAlongPath(path: Coordinate[], t: number, style: PathMotionStyle): { cx: number; cy: number } {
    if (path.length === 0) return { cx: 0, cy: 0 };
    if (path.length === 1 || t <= 0) return squareCenter(path[0]!.x, path[0]!.y);
    if (t >= 1) {
        const end = path[path.length - 1]!;
        return squareCenter(end.x, end.y);
    }
    const segments = path.length - 1;
    const scaled = t * segments;
    const index = Math.min(segments - 1, Math.floor(scaled));
    const local = scaled - index;
    const fromCoord = path[index]!;
    const toCoord = path[index + 1]!;
    const from = squareCenter(fromCoord.x, fromCoord.y);
    const to = squareCenter(toCoord.x, toCoord.y);
    const cx = from.cx + (to.cx - from.cx) * local;
    let cy = from.cy + (to.cy - from.cy) * local;
    if (style === "arc") {
        // Parabola peaking mid-segment (negative Y = "up" on the pitch SVG).
        cy -= 4 * local * (1 - local) * throwArcHeight(fromCoord, toCoord);
    } else if (style === "bounce") {
        cy -= 4 * local * (1 - local) * bounceHopHeight();
    }
    return { cx, cy };
}

/** Smooth pitch-unit position along a path; falls back to `at` when motion is idle. */
export function usePathMotion(
    path: Coordinate[] | null | undefined,
    durationMs: number | null | undefined,
    at: Coordinate | null,
    style: PathMotionStyle = "straight",
): { cx: number; cy: number; t: number } {
    const fallback = at != null ? squareCenter(at.x, at.y) : { cx: 0, cy: 0 };
    const [display, setDisplay] = useState({ ...fallback, t: 1 });

    useEffect(() => {
        if (path == null || path.length < 2 || durationMs == null || durationMs <= 0) {
            const parked = path != null && path.length >= 1 ? squareCenter(path[path.length - 1]!.x, path[path.length - 1]!.y) : fallback;
            setDisplay({ ...parked, t: 1 });
            return;
        }

        let frame = 0;
        const started = performance.now();
        setDisplay({ ...pointAlongPath(path, 0, style), t: 0 });

        function tick(now: number) {
            const raw = Math.min(1, (now - started) / durationMs!);
            const t = easeOutCubic(raw);
            setDisplay({ ...pointAlongPath(path!, t, style), t });
            if (raw < 1) frame = requestAnimationFrame(tick);
        }

        frame = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(frame);
        // Only re-run when a new motion starts (path identity / duration), not every board tick.
        // eslint-disable-next-line react-hooks/exhaustive-deps -- fallback tracks `at` when idle
    }, [path, durationMs, style]);

    useEffect(() => {
        if (path != null && path.length >= 2 && durationMs != null && durationMs > 0) return;
        if (path != null && path.length >= 1) {
            const end = path[path.length - 1]!;
            setDisplay({ ...squareCenter(end.x, end.y), t: 1 });
            return;
        }
        setDisplay({ ...(at != null ? squareCenter(at.x, at.y) : { cx: 0, cy: 0 }), t: 1 });
    }, [at?.x, at?.y, path, durationMs]);

    return display;
}
