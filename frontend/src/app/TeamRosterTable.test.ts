import { describe, expect, it } from "vitest";
import type { PlayerCardDto } from "../api/client";
import { groupRosterByPosition } from "./TeamRosterTable";

function card(positionLabel: string, extras: Partial<PlayerCardDto> = {}): PlayerCardDto {
    return {
        teamId: "dwarves",
        positionLabel,
        blockDiceCount: 1,
        canThrowTeammate: false,
        ...extras,
    };
}

describe("groupRosterByPosition", () => {
    it("collapses identical positions and preserves first-seen order", () => {
        const rows = groupRosterByPosition([
            card("Lineman"),
            card("Blitzer", { blockDiceCount: 2 }),
            card("Lineman"),
            card("Lineman"),
            card("Runner", { move: 6 }),
        ]);
        expect(rows.map((row) => [row.card.positionLabel, row.qty])).toEqual([
            ["Lineman", 3],
            ["Blitzer", 1],
            ["Runner", 1],
        ]);
    });
});
