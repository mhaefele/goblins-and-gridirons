import { describe, expect, it } from "vitest";
import { assetRegistry, positionSlug } from "./registry";

const tokenPaths = Object.keys(import.meta.glob("./tokens/*/*.png"));

describe("assetRegistry folder conventions", () => {
    it("has a prone token and a player card for every standing token", () => {
        const standing = tokenPaths
            .map((path) => /^\.\/tokens\/([^/]+)\/(.+)-standing\.png$/.exec(path))
            .filter((match): match is RegExpExecArray => match != null);
        expect(standing.length).toBeGreaterThan(0);
        for (const [, teamId, position] of standing) {
            expect(assetRegistry.token({ teamId: teamId!, position: position!, status: "prone" }), `${teamId}/${position}`).toBeTruthy();
            expect(assetRegistry.playerCardArt(teamId!, position!), `${teamId}/${position}`).toBeTruthy();
        }
    });

    it("has hero art and a crest for every team with tokens", () => {
        const teamIds = new Set(tokenPaths.map((path) => path.split("/")[2]!));
        for (const teamId of teamIds) {
            expect(assetRegistry.teamCard(teamId), teamId).toBeTruthy();
            expect(assetRegistry.teamCrest(teamId), teamId).toBeTruthy();
        }
    });

    it("resolves position labels and challenge condition keys by slug", () => {
        const position = positionSlug("Linewoman");
        expect(assetRegistry.token({ teamId: "amazon", position, status: "standing" })).toMatch(/linewoman-standing/);
        expect(assetRegistry.challengeCardFace("STAR_TURN")).toBeTruthy();
    });
});
