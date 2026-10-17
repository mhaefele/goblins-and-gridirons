import { describe, expect, it } from "vitest";
import type { MatchGameStateDto } from "../api/client";
import type { BoardView } from "./boardView";
import { isHotseat, resolveLocalCoach, seatIsYou, thisBrowserActsFor, type CoachSeat } from "./seatIdentity";

function gameState(partial: {
    activeCoach?: CoachSeat;
    homeControlledBy?: "HUMAN" | "COMPUTER";
    awayControlledBy?: "HUMAN" | "COMPUTER";
}): MatchGameStateDto {
    return {
        turnNumber: 1,
        activeCoach: partial.activeCoach ?? "HOME",
        phase: "PLAYER_ACTION",
        actionsRemaining: 3,
        homeScore: 0,
        awayScore: 0,
        homeControlledBy: partial.homeControlledBy ?? "HUMAN",
        awayControlledBy: partial.awayControlledBy ?? "COMPUTER",
        result: null,
    } as MatchGameStateDto;
}

function view(partial: Parameters<typeof gameState>[0]): BoardView {
    return {
        lastEventId: 0,
        pieces: { players: [], looseBallLocations: [] },
        gameState: gameState(partial),
    };
}

describe("resolveLocalCoach", () => {
    it("uses preferred coach when that seat is human", () => {
        const v = view({ homeControlledBy: "HUMAN", awayControlledBy: "HUMAN", activeCoach: "AWAY" });
        expect(resolveLocalCoach(v, "HOME")).toBe("HOME");
    });

    it("ignores preferred when that seat is computer", () => {
        const v = view({ homeControlledBy: "HUMAN", awayControlledBy: "COMPUTER", activeCoach: "HOME" });
        expect(resolveLocalCoach(v, "AWAY")).toBe("HOME");
    });

    it("picks the sole human seat vs computer", () => {
        expect(resolveLocalCoach(view({ homeControlledBy: "COMPUTER", awayControlledBy: "HUMAN" }), null)).toBe(
            "AWAY",
        );
    });

    it("follows active coach in hotseat", () => {
        const v = view({ homeControlledBy: "HUMAN", awayControlledBy: "HUMAN", activeCoach: "AWAY" });
        expect(resolveLocalCoach(v, null)).toBe("AWAY");
    });
});

describe("thisBrowserActsFor", () => {
    it("allows any human seat without preferred (hotseat)", () => {
        const gs = gameState({ homeControlledBy: "HUMAN", awayControlledBy: "HUMAN" });
        expect(thisBrowserActsFor(gs, "HOME", null)).toBe(true);
        expect(thisBrowserActsFor(gs, "AWAY", null)).toBe(true);
    });

    it("locks to preferred seat when both are human", () => {
        const gs = gameState({ homeControlledBy: "HUMAN", awayControlledBy: "HUMAN" });
        expect(thisBrowserActsFor(gs, "HOME", "HOME")).toBe(true);
        expect(thisBrowserActsFor(gs, "AWAY", "HOME")).toBe(false);
    });

    it("never acts for a computer seat", () => {
        const gs = gameState({ homeControlledBy: "HUMAN", awayControlledBy: "COMPUTER" });
        expect(thisBrowserActsFor(gs, "AWAY", null)).toBe(false);
        expect(thisBrowserActsFor(gs, "HOME", "HOME")).toBe(true);
    });
});

describe("seatIsYou", () => {
    it("labels only the local human seat when vs computer", () => {
        const gs = gameState({ homeControlledBy: "HUMAN", awayControlledBy: "COMPUTER" });
        expect(seatIsYou(gs, "HOME", "HOME")).toBe(true);
        expect(seatIsYou(gs, "AWAY", "HOME")).toBe(false);
    });

    it("labels neither seat You in shared hotseat", () => {
        const gs = gameState({ homeControlledBy: "HUMAN", awayControlledBy: "HUMAN" });
        expect(isHotseat(gs)).toBe(true);
        expect(seatIsYou(gs, "HOME", "HOME")).toBe(false);
        expect(seatIsYou(gs, "AWAY", "AWAY")).toBe(false);
    });

    it("labels You on preferred seat when both human (split seat)", () => {
        const gs = gameState({ homeControlledBy: "HUMAN", awayControlledBy: "HUMAN" });
        expect(seatIsYou(gs, "HOME", "HOME", "HOME")).toBe(true);
        expect(seatIsYou(gs, "AWAY", "HOME", "HOME")).toBe(false);
    });
});
