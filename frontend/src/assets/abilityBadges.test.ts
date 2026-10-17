import { describe, expect, it } from "vitest";
import { abilityBadgeSlug, abilityBadgeUrl } from "./abilityBadges";
import { resolveAbilityBadgeUrl } from "../render/AbilityBadge";

describe("abilityBadgeSlug", () => {
    it("slugifies card titles", () => {
        expect(abilityBadgeSlug("Sure Scoop")).toBe("sure-scoop");
        expect(abilityBadgeSlug("Quick Release")).toBe("quick-release");
        expect(abilityBadgeSlug("Catch and Go")).toBe("catch-and-go");
        expect(abilityBadgeSlug("Rally")).toBe("rally");
        expect(abilityBadgeSlug("Charge")).toBe("charge");
        expect(abilityBadgeSlug("Nimble Beast")).toBe("nimble-beast");
        expect(abilityBadgeSlug("Colossus")).toBe("colossus");
    });
});

describe("abilityBadgeUrl", () => {
    it("resolves by card title and slug", () => {
        const byTitle = abilityBadgeUrl("Ball Guard");
        const bySlug = abilityBadgeUrl("ball-guard");
        expect(byTitle).toBeTruthy();
        expect(bySlug).toBe(byTitle);
        expect(String(byTitle)).toMatch(/ball-guard/i);
    });

    it("resolves every renamed ability title by its slug filename", () => {
        expect(abilityBadgeUrl("Nimble Beast")).toBeTruthy();
        expect(abilityBadgeUrl("Colossus")).toBeTruthy();
        expect(abilityBadgeUrl("Underfoot")).toBeTruthy();
        expect(abilityBadgeUrl("Beneath Notice")).toBeTruthy();
        expect(abilityBadgeUrl("Ethereal")).toBeTruthy();
        expect(abilityBadgeUrl("Charge")).toBeTruthy();
    });

    it("returns null for unknown keys", () => {
        expect(abilityBadgeUrl(null)).toBeNull();
        expect(abilityBadgeUrl("Not A Real Ability")).toBeNull();
    });
});

describe("resolveAbilityBadgeUrl", () => {
    it("also accepts PlayerAbility enum names", () => {
        expect(String(resolveAbilityBadgeUrl("Ball Guard"))).toMatch(/ball-guard/i);
        expect(String(resolveAbilityBadgeUrl("Relentless After Block"))).toMatch(/relentless/i);
        expect(String(resolveAbilityBadgeUrl("Thirst"))).toMatch(/thirst/i);
        expect(String(resolveAbilityBadgeUrl("NIMBLE_BEAST"))).toMatch(/nimble-beast/i);
        expect(String(resolveAbilityBadgeUrl("COLOSSUS"))).toMatch(/colossus/i);
    });
});
