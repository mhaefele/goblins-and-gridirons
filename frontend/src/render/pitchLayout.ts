import type { Coordinate, PitchLayoutDto } from "../api/client";

export function gridWidth(layout: PitchLayoutDto): number {
    return layout.playWidth + 2;
}

export function gridHeight(layout: PitchLayoutDto): number {
    return layout.playHeight + 2;
}

export function coordKey(coord: Coordinate): string {
    return `${coord.x},${coord.y}`;
}

export function isPlayableSquare(layout: PitchLayoutDto, x: number, y: number): boolean {
    return x >= 1 && x <= layout.playWidth && y >= 1 && y <= layout.playHeight;
}

export function isOutOfBounds(layout: PitchLayoutDto, x: number, y: number): boolean {
    return x === 0 || y === 0 || x === layout.playWidth + 1 || y === layout.playHeight + 1;
}

export function squareLabel(x: number, y: number): string {
    return `Square (${x}, ${y})`;
}

export function squareCenter(x: number, y: number): { cx: number; cy: number } {
    return { cx: x + 0.5, cy: y + 0.5 };
}

/** Map a browser pointer position to a pitch grid coordinate via the SVG CTM. */
export function clientPointToSquare(svg: SVGSVGElement, clientX: number, clientY: number): Coordinate | null {
    const ctm = svg.getScreenCTM();
    if (ctm == null) return null;
    const point = svg.createSVGPoint();
    point.x = clientX;
    point.y = clientY;
    const local = point.matrixTransform(ctm.inverse());
    const x = Math.floor(local.x);
    const y = Math.floor(local.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    return { x, y };
}
