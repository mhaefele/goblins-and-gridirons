import { describe, expect, it } from "vitest";
import { clientPointToSquare, coordKey } from "./pitchLayout";

describe("clientPointToSquare", () => {
    it("maps through an identity CTM to floor of SVG user units", () => {
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("viewBox", "0 0 13 17");
        // jsdom lacks getScreenCTM / createSVGPoint — stub a 1:1 mapping.
        svg.createSVGPoint = (() =>
            ({
                x: 0,
                y: 0,
                matrixTransform: () => ({ x: 5.2, y: 7.9 }),
            })) as unknown as typeof svg.createSVGPoint;
        svg.getScreenCTM = () =>
            ({
                inverse: () => ({}),
            }) as unknown as DOMMatrix;

        expect(clientPointToSquare(svg, 0, 0)).toEqual({ x: 5, y: 7 });
        expect(coordKey({ x: 5, y: 7 })).toBe("5,7");
    });

    it("returns null when CTM is unavailable", () => {
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.getScreenCTM = () => null;
        expect(clientPointToSquare(svg, 10, 10)).toBeNull();
    });

    it("floors near the far edge of a cell without rounding up", () => {
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.createSVGPoint = (() =>
            ({
                x: 0,
                y: 0,
                matrixTransform: () => ({ x: 5.999, y: 7.001 }),
            })) as unknown as typeof svg.createSVGPoint;
        svg.getScreenCTM = () =>
            ({
                inverse: () => ({}),
            }) as unknown as DOMMatrix;

        expect(clientPointToSquare(svg, 0, 0)).toEqual({ x: 5, y: 7 });
    });
});
