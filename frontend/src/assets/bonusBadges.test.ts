import { describe, expect, it } from "vitest";
import { bonusBadgeSlug, bonusBadgeUrl } from "./bonusBadges";

describe("bonusBadgeSlug", () => {
    it("slugifies display names and enums", () => {
        expect(bonusBadgeSlug("Hail Mary Pass")).toBe("hail-mary-pass");
        expect(bonusBadgeSlug("Momentum")).toBe("momentum");
        expect(bonusBadgeSlug("Underdog")).toBe("underdog");
        expect(bonusBadgeSlug("UNDERDOG")).toBe("underdog");
        expect(bonusBadgeSlug("MOMENTUM")).toBe("momentum");
    });
});

describe("bonusBadgeUrl", () => {
    it("resolves by display name, slug, and enum", () => {
        const byTitle = bonusBadgeUrl("Sprint");
        const bySlug = bonusBadgeUrl("sprint");
        const byEnum = bonusBadgeUrl("SPRINT");
        expect(byTitle).toBeTruthy();
        expect(bySlug).toBe(byTitle);
        expect(byEnum).toBe(byTitle);
        expect(String(byTitle)).toMatch(/sprint/i);
    });

    it("resolves Momentum display and enum", () => {
        expect(String(bonusBadgeUrl("Momentum"))).toMatch(/momentum/i);
        expect(String(bonusBadgeUrl("MOMENTUM"))).toMatch(/momentum/i);
    });

    it("returns null for unknown keys", () => {
        expect(bonusBadgeUrl(null)).toBeNull();
        expect(bonusBadgeUrl("Not A Real Bonus")).toBeNull();
    });
});
