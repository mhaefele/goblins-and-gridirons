import { describe, expect, it } from "vitest";
import {
    BOARD_IMAGE_CONTENT,
    BOARD_IMAGE_PLAYABLE_PX,
    boardImagePlacement,
    playablePxToContent,
    svgPointToImagePixel,
} from "./boardImageLayout";

const PLAY_WIDTH = 11;
const PLAY_HEIGHT = 15;

describe("BOARD_IMAGE_PLAYABLE_PX", () => {
    it("encodes a playable floor inside each board image with a frame margin (OOB)", () => {
        for (const [id, playable] of Object.entries(BOARD_IMAGE_PLAYABLE_PX)) {
            expect(playable.w, id).toBeGreaterThan(0);
            expect(playable.h, id).toBeGreaterThan(0);
            expect(playable.x, id).toBeGreaterThan(0);
            expect(playable.y, id).toBeGreaterThan(0);
            expect(playable.x + playable.w, id).toBeLessThan(playable.imageWidth);
            expect(playable.y + playable.h, id).toBeLessThan(playable.imageHeight);
            // Frame/OOB rail exists on every side of the measured floor.
            expect(playable.x, `${id} left frame`).toBeGreaterThanOrEqual(8);
            expect(playable.y, `${id} top frame`).toBeGreaterThanOrEqual(8);
            expect(playable.imageWidth - (playable.x + playable.w), `${id} right frame`).toBeGreaterThanOrEqual(8);
            expect(playable.imageHeight - (playable.y + playable.h), `${id} bottom frame`).toBeGreaterThanOrEqual(8);
        }
    });

    it("derives BOARD_IMAGE_CONTENT from the pixel playable floors", () => {
        for (const id of Object.keys(BOARD_IMAGE_PLAYABLE_PX)) {
            expect(BOARD_IMAGE_CONTENT[id]).toEqual(playablePxToContent(BOARD_IMAGE_PLAYABLE_PX[id]!));
        }
    });
});

describe("boardImagePlacement", () => {
    it("maps the content rect onto the playable grid", () => {
        const content = { x: 0.1, y: 0.2, w: 0.8, h: 0.6 };
        const placed = boardImagePlacement(PLAY_WIDTH, PLAY_HEIGHT, content);
        expect(placed.width).toBeCloseTo(PLAY_WIDTH / 0.8);
        expect(placed.height).toBeCloseTo(PLAY_HEIGHT / 0.6);
        expect(placed.x).toBeCloseTo(1 - 0.1 * placed.width);
        expect(placed.y).toBeCloseTo(1 - 0.2 * placed.height);
        expect(placed.x + content.x * placed.width).toBeCloseTo(1);
        expect(placed.y + content.y * placed.height).toBeCloseTo(1);
        expect(placed.x + (content.x + content.w) * placed.width).toBeCloseTo(1 + PLAY_WIDTH);
        expect(placed.y + (content.y + content.h) * placed.height).toBeCloseTo(1 + PLAY_HEIGHT);
    });

    it("maps shipped board openings onto the playable grid", () => {
        for (const id of Object.keys(BOARD_IMAGE_PLAYABLE_PX)) {
            const content = BOARD_IMAGE_CONTENT[id]!;
            const placed = boardImagePlacement(PLAY_WIDTH, PLAY_HEIGHT, content);
            expect(placed.x + content.x * placed.width).toBeCloseTo(1);
            expect(placed.y + content.y * placed.height).toBeCloseTo(1);
            expect(placed.x + (content.x + content.w) * placed.width).toBeCloseTo(1 + PLAY_WIDTH);
            expect(placed.y + (content.y + content.h) * placed.height).toBeCloseTo(1 + PLAY_HEIGHT);
        }
    });
});

describe("usable grid vs out-of-bounds frame", () => {
    /**
     * Every corner of every playable SVG cell must land inside the measured
     * playable pixel floor — never on the wood/spike OOB frame.
     */
    it("keeps all playable cells inside the measured floor for each board", () => {
        for (const [id, playable] of Object.entries(BOARD_IMAGE_PLAYABLE_PX)) {
            const content = BOARD_IMAGE_CONTENT[id]!;
            const placement = boardImagePlacement(PLAY_WIDTH, PLAY_HEIGHT, content);
            const floorLeft = playable.x;
            const floorTop = playable.y;
            const floorRight = playable.x + playable.w;
            const floorBottom = playable.y + playable.h;

            for (let gx = 1; gx <= PLAY_WIDTH; gx++) {
                for (let gy = 1; gy <= PLAY_HEIGHT; gy++) {
                    for (const [sx, sy] of [
                        [gx, gy],
                        [gx + 1, gy],
                        [gx, gy + 1],
                        [gx + 1, gy + 1],
                    ] as const) {
                        const { px, py } = svgPointToImagePixel(
                            sx,
                            sy,
                            placement,
                            playable.imageWidth,
                            playable.imageHeight,
                        );
                        expect(px, `${id} cell(${gx},${gy}) corner svg(${sx},${sy}) px`).toBeGreaterThanOrEqual(
                            floorLeft - 1e-6,
                        );
                        expect(px, `${id} cell(${gx},${gy}) corner svg(${sx},${sy}) px`).toBeLessThanOrEqual(
                            floorRight + 1e-6,
                        );
                        expect(py, `${id} cell(${gx},${gy}) corner svg(${sx},${sy}) py`).toBeGreaterThanOrEqual(
                            floorTop - 1e-6,
                        );
                        expect(py, `${id} cell(${gx},${gy}) corner svg(${sx},${sy}) py`).toBeLessThanOrEqual(
                            floorBottom + 1e-6,
                        );
                    }
                }
            }
        }
    });

    it("places the OOB SVG gutter on the frame margins, not on the floor", () => {
        for (const [id, playable] of Object.entries(BOARD_IMAGE_PLAYABLE_PX)) {
            const content = BOARD_IMAGE_CONTENT[id]!;
            const placement = boardImagePlacement(PLAY_WIDTH, PLAY_HEIGHT, content);

            // Outer corners of the padded grid (OOB ring) must fall outside the floor.
            const oobCorners = [
                [0, 0],
                [PLAY_WIDTH + 2, 0],
                [0, PLAY_HEIGHT + 2],
                [PLAY_WIDTH + 2, PLAY_HEIGHT + 2],
            ] as const;

            for (const [sx, sy] of oobCorners) {
                const { px, py } = svgPointToImagePixel(
                    sx,
                    sy,
                    placement,
                    playable.imageWidth,
                    playable.imageHeight,
                );
                const insideFloor =
                    px >= playable.x &&
                    px <= playable.x + playable.w &&
                    py >= playable.y &&
                    py <= playable.y + playable.h;
                expect(insideFloor, `${id} OOB corner svg(${sx},${sy}) @ px(${px},${py})`).toBe(false);
            }
        }
    });
});
