import { describe, expect, it } from "vitest";
import type { PlayerCardDto } from "../api/client";
import { collectRosterAbilityTags } from "./TeamOverview";

function card(extras: Partial<PlayerCardDto> = {}): PlayerCardDto {
    return {
        teamId: "amazon",
        positionLabel: "Lineman",
        blockDiceCount: 1,
        canThrowTeammate: false,
        ...extras,
    };
}

describe("collectRosterAbilityTags", () => {
    it("dedupes abilities and appends Throw Team-mate when present", () => {
        const tags = collectRosterAbilityTags([
            card({
                ability: { key: "Disruptor", description: "Ignore one assist." },
            }),
            card({
                positionLabel: "Blitzer",
                ability: { key: "Strike and Fade", description: "Move after block." },
            }),
            card({
                positionLabel: "Lineman",
                ability: { key: "Disruptor", description: "Ignore one assist." },
            }),
            card({
                positionLabel: "Ogre",
                canThrowTeammate: true,
            }),
        ]);
        expect(tags).toEqual([
            { key: "Disruptor", description: "Ignore one assist." },
            { key: "Strike and Fade", description: "Move after block." },
            {
                key: "Throw Team-mate",
                description: "Open action: throw an adjacent standing teammate of a different position.",
            },
        ]);
    });
});
