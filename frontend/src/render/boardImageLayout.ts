/**
 * Board PNGs include a painted wood/spike frame around the pitch floor.
 * Stretching the full PNG to the SVG viewBox leaves floor between the frame and
 * the playable grid (or pushes cells into the frame). Measured playable pixel
 * rects are the floor inside that frame; content fractions map them onto the
 * logical playable grid so cells fill floor-to-floor.
 */

/** Pixel-space playable floor inside the OOB frame (measured from boards/*.png). */
export type BoardImagePlayablePx = {
    /** Full board PNG width in pixels. */
    imageWidth: number;
    /** Full board PNG height in pixels. */
    imageHeight: number;
    /** Left edge of playable floor (first playable column), px from image left. */
    x: number;
    /** Top edge of playable floor (home end zone), px from image top. */
    y: number;
    /** Playable floor width in pixels (maps to `playWidth` cells). */
    w: number;
    /** Playable floor height in pixels (maps to `playHeight` cells). */
    h: number;
};

/**
 * Measured playable floors for shipped board art.
 *
 * Out of bounds on these images is the decorative frame outside this rect:
 * - `default`: wooden rail with rivets around the stone pitch
 * - `barricade`: spiked wooden walls around the plank pitch
 *
 * Everything with `px < x`, `px >= x+w`, `py < y`, or `py >= y+h` is frame/OOB art.
 */
export const BOARD_IMAGE_PLAYABLE_PX: Record<string, BoardImagePlayablePx> = {
    // Painted stone floor + wood frame; playable is exact 11×15 cell lattice (1024×1536).
    default: { imageWidth: 1024, imageHeight: 1536, x: 116, y: 88, w: 792, h: 1350 },
    // Painted floor + spiked frame; playable is exact 11×15 cell lattice (1024×1536).
    barricade: { imageWidth: 1024, imageHeight: 1536, x: 138, y: 118, w: 748, h: 1260 },
};

export type BoardImageContentRect = {
    /** Left edge of the playable floor, as a fraction of image width. */
    x: number;
    /** Top edge of the playable floor, as a fraction of image height. */
    y: number;
    /** Playable floor width as a fraction of image width. */
    w: number;
    /** Playable floor height as a fraction of image height. */
    h: number;
};

export function playablePxToContent(playable: BoardImagePlayablePx): BoardImageContentRect {
    return {
        x: playable.x / playable.imageWidth,
        y: playable.y / playable.imageHeight,
        w: playable.w / playable.imageWidth,
        h: playable.h / playable.imageHeight,
    };
}

/** Normalized playable floors for shipped board art (derived from {@link BOARD_IMAGE_PLAYABLE_PX}). */
export const BOARD_IMAGE_CONTENT: Record<string, BoardImageContentRect> = Object.fromEntries(
    Object.entries(BOARD_IMAGE_PLAYABLE_PX).map(([id, playable]) => [id, playablePxToContent(playable)]),
);

/** Place the full PNG so `content` maps onto the playable rectangle (1,1)–(1+pw,1+ph). */
export function boardImagePlacement(
    playWidth: number,
    playHeight: number,
    content: BoardImageContentRect,
): { x: number; y: number; width: number; height: number } {
    const width = playWidth / content.w;
    const height = playHeight / content.h;
    return {
        x: 1 - content.x * width,
        y: 1 - content.y * height,
        width,
        height,
    };
}

/**
 * Map an SVG user point to board-image pixel coordinates given a placement.
 * Used to assert playable cells land on the measured floor, not the OOB frame.
 */
export function svgPointToImagePixel(
    svgX: number,
    svgY: number,
    placement: { x: number; y: number; width: number; height: number },
    imageWidth: number,
    imageHeight: number,
): { px: number; py: number } {
    return {
        px: ((svgX - placement.x) / placement.width) * imageWidth,
        py: ((svgY - placement.y) / placement.height) * imageHeight,
    };
}
