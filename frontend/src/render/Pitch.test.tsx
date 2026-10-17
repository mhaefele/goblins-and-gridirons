import { fireEvent, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PitchLayoutDto } from "../api/client";
import Pitch from "./Pitch";
import { BOARD_IMAGE_CONTENT, boardImagePlacement } from "./boardImageLayout";

const layout: PitchLayoutDto = {
    pitchDefinitionId: "default",
    playWidth: 11,
    playHeight: 15,
    homeEndZoneRow: 1,
    awayEndZoneRow: 15,
    blockedSquares: [
        { x: 3, y: 4 },
        { x: 4, y: 4 },
        { x: 3, y: 5 },
        { x: 4, y: 5 },
        { x: 6, y: 8 },
    ],
    trapdoors: [{ x: 6, y: 8 }],
};

const tiles = {
    floor: "/floor.png",
    homeEndZone: "/home.png",
    awayEndZone: "/away.png",
};

function stubSvgPointerMapping(svg: SVGSVGElement, localX: number, localY: number) {
    svg.createSVGPoint = (() =>
        ({
            x: 0,
            y: 0,
            matrixTransform: () => ({ x: localX, y: localY }),
        })) as unknown as typeof svg.createSVGPoint;
    svg.getScreenCTM = () =>
        ({
            inverse: () => ({}),
        }) as unknown as DOMMatrix;
}

describe("Pitch tiles and targeting", () => {
    it("renders one tile image per playable cell when no board underlay", () => {
        const { container } = render(<Pitch layout={layout} tiles={tiles} />);
        expect(container.querySelector('[data-testid="pitch-board-image"]')).toBeNull();
        const images = container.querySelectorAll("image");
        // OOB frame uses solid rects — only the 11×15 playable cells are textures.
        expect(images.length).toBe(11 * 15);
        const first = images[0]!;
        expect(first.getAttribute("width")).toBe("1");
        expect(first.getAttribute("height")).toBe("1");
    });

    it("maps the board underlay so the playable floor fills the playable grid", () => {
        const content = BOARD_IMAGE_CONTENT.default!;
        const { getByTestId } = render(
            <Pitch layout={layout} boardImageUrl="/boards/default.png" boardImageContent={content} tiles={tiles} />,
        );
        const board = getByTestId("pitch-board-image");
        const expected = boardImagePlacement(layout.playWidth, layout.playHeight, content);
        expect(Number(board.getAttribute("x"))).toBeCloseTo(expected.x, 5);
        expect(Number(board.getAttribute("y"))).toBeCloseTo(expected.y, 5);
        expect(Number(board.getAttribute("width"))).toBeCloseTo(expected.width, 5);
        expect(Number(board.getAttribute("height"))).toBeCloseTo(expected.height, 5);
        expect(board.getAttribute("href")).toBe("/boards/default.png");
    });

    it("maps the barricade board with its measured playable floor", () => {
        const content = BOARD_IMAGE_CONTENT.barricade!;
        const barricadeLayout = { ...layout, pitchDefinitionId: "barricade" };
        const { getByTestId } = render(
            <Pitch
                layout={barricadeLayout}
                boardImageUrl="/boards/barricade.png"
                boardImageContent={content}
            />,
        );
        const board = getByTestId("pitch-board-image");
        const expected = boardImagePlacement(barricadeLayout.playWidth, barricadeLayout.playHeight, content);
        expect(Number(board.getAttribute("x"))).toBeCloseTo(expected.x, 5);
        expect(Number(board.getAttribute("y"))).toBeCloseTo(expected.y, 5);
        expect(Number(board.getAttribute("width"))).toBeCloseTo(expected.width, 5);
        expect(Number(board.getAttribute("height"))).toBeCloseTo(expected.height, 5);
    });

    it("falls back to full-viewBox placement when no content rect is provided", () => {
        const { container, getByTestId } = render(
            <Pitch layout={layout} boardImageUrl="/boards/default.png" tiles={tiles} />,
        );
        const board = getByTestId("pitch-board-image");
        expect(board.getAttribute("x")).toBe("0");
        expect(board.getAttribute("y")).toBe("0");
        expect(board.getAttribute("width")).toBe("13");
        expect(board.getAttribute("height")).toBe("17");
        // Board owns the floor — no per-cell tile flood and no procedural obstacles.
        expect(container.querySelectorAll("image")).toHaveLength(1);
        expect(container.querySelectorAll(".blocked-obstacle--pillar-2x2")).toHaveLength(0);
        expect(container.querySelectorAll(".blocked-obstacle--pillar")).toHaveLength(0);
    });

    it("draws a perimeter around a 2×2 blocked cluster (8 edges, not 16 cell outlines)", () => {
        const { container } = render(<Pitch layout={layout} tiles={tiles} />);
        const edges = container.querySelectorAll(".pitch-blocked-perimeter");
        // 2×2 cluster → 8 edges; plus single blocked at (6,8) → 4 edges = 12
        expect(edges.length).toBe(12);
    });

    it("keeps blocked perimeter when using a board underlay", () => {
        const { container } = render(<Pitch layout={layout} boardImageUrl="/boards/default.png" />);
        expect(container.querySelectorAll(".pitch-blocked-perimeter")).toHaveLength(12);
    });

    it("renders procedural pillar graphics only without a board underlay", () => {
        const { container } = render(<Pitch layout={layout} tiles={tiles} />);
        expect(container.querySelectorAll(".blocked-obstacle--pillar-2x2")).toHaveLength(1);
        expect(container.querySelectorAll(".blocked-obstacle--pillar")).toHaveLength(1); // leftover (6,8)
    });

    it("shows hover target rect for the hovered coordinate", () => {
        const { getByTestId } = render(<Pitch layout={layout} tiles={tiles} hoverCoordinate={{ x: 5, y: 7 }} />);
        const hover = getByTestId("pitch-hover-target");
        expect(hover.getAttribute("data-coord")).toBe("5,7");
        expect(hover.getAttribute("x")).toBe("5");
        expect(hover.getAttribute("y")).toBe("7");
    });

    it("hit targets fire the clicked coordinate exactly", () => {
        const onSquareClick = vi.fn();
        const highlights = new Map([
            ["5,7", { coordinate: { x: 5, y: 7 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
            ["4,6", { coordinate: { x: 4, y: 6 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
        ]);
        const { container } = render(
            <Pitch layout={layout} tiles={tiles} highlights={highlights} onSquareClick={onSquareClick} />,
        );
        const target = container.querySelector('[data-testid="pitch-hit-5,7"]');
        expect(target).not.toBeNull();
        fireEvent.click(target!);
        expect(onSquareClick).toHaveBeenCalledTimes(1);
        expect(onSquareClick).toHaveBeenCalledWith({ x: 5, y: 7 });
    });

    it("reports hover coordinate via CTM-mapped pointer move on the SVG", () => {
        const onSquareHover = vi.fn();
        const { container } = render(<Pitch layout={layout} tiles={tiles} onSquareHover={onSquareHover} />);
        const svg = container.querySelector('[data-testid="pitch-svg"]') as SVGSVGElement | null;
        expect(svg).not.toBeNull();
        stubSvgPointerMapping(svg!, 4.1, 9.7);

        fireEvent.pointerMove(svg!, { clientX: 100, clientY: 200 });
        expect(onSquareHover).toHaveBeenCalledWith({ x: 4, y: 9 });
    });

    it("maps SVG click (no hit-target) through the same CTM coordinate as hover", () => {
        const onSquareClick = vi.fn();
        const highlights = new Map([
            ["4,9", { coordinate: { x: 4, y: 9 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
        ]);
        const { container } = render(
            <Pitch layout={layout} tiles={tiles} highlights={highlights} onSquareClick={onSquareClick} />,
        );
        const svg = container.querySelector('[data-testid="pitch-svg"]') as SVGSVGElement | null;
        expect(svg).not.toBeNull();
        stubSvgPointerMapping(svg!, 4.9, 9.1);
        fireEvent.click(svg!, { clientX: 10, clientY: 20 });
        expect(onSquareClick).toHaveBeenCalledWith({ x: 4, y: 9 });
    });

    it("forwards clicks on non-highlighted squares so the UI can deselect", () => {
        const onSquareClick = vi.fn();
        const highlights = new Map([
            ["4,9", { coordinate: { x: 4, y: 9 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
        ]);
        const { container } = render(
            <Pitch layout={layout} tiles={tiles} highlights={highlights} onSquareClick={onSquareClick} />,
        );
        const svg = container.querySelector('[data-testid="pitch-svg"]') as SVGSVGElement | null;
        stubSvgPointerMapping(svg!, 1.2, 2.4);
        fireEvent.click(svg!, { clientX: 10, clientY: 20 });
        expect(onSquareClick).toHaveBeenCalledWith({ x: 1, y: 2 });
    });

    it("renders soft path preview squares", () => {
        const { container } = render(
            <Pitch
                layout={layout}
                tiles={tiles}
                pathPreview={[
                    { x: 2, y: 2 },
                    { x: 3, y: 2 },
                    { x: 4, y: 2 },
                ]}
            />,
        );
        expect(container.querySelectorAll(".pitch-path-preview")).toHaveLength(3);
    });

    it("draws a dashed throw arc preview from thrower to target", () => {
        const { getByTestId } = render(
            <Pitch
                layout={layout}
                tiles={tiles}
                throwArcPreview={{ from: { x: 2, y: 5 }, to: { x: 6, y: 8 }, obstructed: false, difficulty: "short" }}
            />,
        );
        const arc = getByTestId("pitch-throw-arc");
        expect(arc.getAttribute("d")).toContain("M ");
        expect(arc.getAttribute("d")).toContain("Q ");
        expect(arc.classList.contains("pitch-throw-arc--obstructed")).toBe(false);
    });

    it("hit targets have no stroke (avoids stealing clicks from neighboring cells)", () => {
        const highlights = new Map([
            ["5,7", { coordinate: { x: 5, y: 7 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
            ["6,7", { coordinate: { x: 6, y: 7 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
        ]);
        const { container } = render(<Pitch layout={layout} tiles={tiles} highlights={highlights} onSquareClick={() => {}} />);
        const hits = container.querySelectorAll(".pitch-hit-target");
        expect(hits.length).toBe(2);
        for (const hit of hits) {
            expect(hit.getAttribute("stroke") === "none" || hit.getAttribute("stroke-width") === "0").toBe(true);
        }
    });

    it("skips hit targets on occupied squares so tokens can receive hover", () => {
        const highlights = new Map([
            ["5,7", { coordinate: { x: 5, y: 7 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
            ["6,7", { coordinate: { x: 6, y: 7 }, actionType: "RUN" as const, primaryType: "RUN" as const }],
        ]);
        const { container } = render(
            <Pitch
                layout={layout}
                tiles={tiles}
                highlights={highlights}
                occupiedKeys={new Set(["5,7"])}
                onSquareClick={() => {}}
            />,
        );
        expect(container.querySelector('[data-testid="pitch-hit-5,7"]')).toBeNull();
        expect(container.querySelector('[data-testid="pitch-hit-6,7"]')).not.toBeNull();
    });
});
