import { describe, expect, it } from "vitest";
import {
    formatMatchResult,
    formatMatchResultDetail,
    formatMatchResultHeadline,
} from "./matchResultCopy";

describe("formatMatchResult", () => {
    it("formats kind and winner", () => {
        expect(formatMatchResult({ kind: "suddenDeath", winner: "HOME" })).toBe(
            "Sudden death — Home wins",
        );
        expect(formatMatchResult({ kind: "pointsVictory", winner: null })).toBe(
            "Points victory — draw",
        );
    });
});

describe("formatMatchResultHeadline", () => {
    it("names the winner or draw", () => {
        expect(formatMatchResultHeadline({ kind: "suddenDeath", winner: "HOME" })).toBe(
            "Home wins",
        );
        expect(formatMatchResultHeadline({ kind: "pointsVictory", winner: null })).toBe("Draw");
    });
});

describe("formatMatchResultDetail", () => {
    it("explains sudden death with lead when scores are present", () => {
        expect(
            formatMatchResultDetail(
                { kind: "suddenDeath", winner: "HOME" },
                { home: 14, away: 2 },
            ),
        ).toContain("led by 12");
        expect(
            formatMatchResultDetail(
                { kind: "suddenDeath", winner: "HOME" },
                { home: 14, away: 2 },
            ),
        ).toContain("Sudden Death");
    });

    it("explains points victory draw", () => {
        expect(
            formatMatchResultDetail(
                { kind: "pointsVictory", winner: null },
                { home: 6, away: 6 },
            ),
        ).toMatch(/last Challenge Card/i);
        expect(
            formatMatchResultDetail(
                { kind: "pointsVictory", winner: null },
                { home: 6, away: 6 },
            ),
        ).toMatch(/draw/i);
    });
});
