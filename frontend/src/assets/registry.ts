import type { PlayerCardDto, PlayerPieceDto } from "../api/client";
import { assetSlug, urlsByStem } from "./slug";

import challengeBack from "./cards/challenge/back.svg?url";
import pitchBoardDefault from "./pitch/boards/default.png?url";
import pitchBoardBarricade from "./pitch/boards/barricade.png?url";
import dungeonStoneTile from "./pitch/tiles/dungeon-stone.png?url";
import dungeonEndzoneHomeTile from "./pitch/tiles/dungeon-endzone-home.png?url";
import dungeonEndzoneAwayTile from "./pitch/tiles/dungeon-endzone-away.png?url";
import barricadeWoodTile from "./pitch/tiles/barricade-wood.png?url";
import barricadeEndzoneHomeTile from "./pitch/tiles/barricade-endzone-home.png?url";
import barricadeEndzoneAwayTile from "./pitch/tiles/barricade-endzone-away.png?url";
import ballIcon from "./ui/ball.svg?url";
import blockFaceIcon from "./ui/block-face.svg?url";
import d6FaceIcon from "./ui/d6-face.svg?url";
import d8FaceIcon from "./ui/d8-face.svg?url";

export type TokenStatus = "standing" | "prone";

export type TokenKey = {
    teamId: string;
    position: string;
    status: TokenStatus;
};

export type IconName = "ball" | "d6" | "d8" | "block";

/*
 * Folder conventions (a team / position rename is a file move only):
 *   tokens/{teamId}/{positionSlug}-{standing|prone}.png
 *   cards/players/{teamId}/{positionSlug}.png
 *   cards/teams/{teamId}-hero.webp · cards/teams/{teamId}-crest.svg
 *   cards/challenge/faces/{condition-key}.svg  (ChallengeClaimCondition, kebab-cased)
 */
const TOKENS = urlsByStem(
    import.meta.glob<string>("./tokens/*/*.png", { eager: true, query: "?url", import: "default" }),
    "./tokens/",
);
const PLAYER_CARDS = urlsByStem(
    import.meta.glob<string>("./cards/players/*/*.png", { eager: true, query: "?url", import: "default" }),
    "./cards/players/",
);
const TEAM_HEROES = urlsByStem(
    import.meta.glob<string>("./cards/teams/*-hero.webp", { eager: true, query: "?url", import: "default" }),
    "./cards/teams/",
);
const TEAM_CRESTS = urlsByStem(
    import.meta.glob<string>("./cards/teams/*-crest.svg", { eager: true, query: "?url", import: "default" }),
    "./cards/teams/",
);
const CHALLENGE_FACES = urlsByStem(
    import.meta.glob<string>("./cards/challenge/faces/*.svg", { eager: true, query: "?url", import: "default" }),
    "./cards/challenge/faces/",
);

/** Missing position art falls back to the team's lineman, else its alphabetically first token. */
function fallbackToken(teamId: string, status: TokenStatus): string | null {
    const lineman = TOKENS[`${teamId}/lineman-${status}`];
    if (lineman != null) return lineman;
    const first = Object.keys(TOKENS)
        .filter((key) => key.startsWith(`${teamId}/`) && key.endsWith(`-${status}`))
        .sort()[0];
    return first != null ? (TOKENS[first] ?? null) : null;
}

const PITCH_BOARDS: Record<string, string> = {
    default: pitchBoardDefault,
    barricade: pitchBoardBarricade,
};

export type PitchTileSet = {
    floor: string;
    homeEndZone: string;
    awayEndZone: string;
};

const PITCH_TILES: Record<string, PitchTileSet> = {
    default: {
        floor: dungeonStoneTile,
        homeEndZone: dungeonEndzoneHomeTile,
        awayEndZone: dungeonEndzoneAwayTile,
    },
    barricade: {
        floor: barricadeWoodTile,
        homeEndZone: barricadeEndzoneHomeTile,
        awayEndZone: barricadeEndzoneAwayTile,
    },
};

const ICON_ASSETS: Record<IconName, string> = {
    ball: ballIcon,
    d6: d6FaceIcon,
    d8: d8FaceIcon,
    block: blockFaceIcon,
};

export function positionSlug(positionLabel: string): string {
    return assetSlug(positionLabel);
}

export function tokenStatusFromPiece(piece: PlayerPieceDto): TokenStatus {
    return piece.status === "PRONE" ? "prone" : "standing";
}

export function tokenKeyFromCard(card: PlayerCardDto, piece: PlayerPieceDto): TokenKey {
    return {
        teamId: card.teamId,
        position: positionSlug(card.positionLabel),
        status: tokenStatusFromPiece(piece),
    };
}

export interface AssetRegistry {
    token(key: TokenKey): string | null;
    icon(name: IconName): string | null;
    teamCard(teamId: string): string | null;
    teamCrest(teamId: string): string | null;
    playerCardArt(teamId: string, position: string): string | null;
    pitchBoard(pitchId: string): string | null;
    /** Per-cell textures aligned 1:1 with the logical pitch grid. */
    pitchTiles(pitchId: string): PitchTileSet | null;
    pitchThumb(pitchId: string): string | null;
    challengeCardBack(): string | null;
    /** Challenge face art for a [ChallengeClaimCondition] enum name. */
    challengeCardFace(challengeCondition: string): string | null;
}

export const assetRegistry: AssetRegistry = {
    token(key: TokenKey): string | null {
        return TOKENS[`${key.teamId}/${key.position}-${key.status}`] ?? fallbackToken(key.teamId, key.status);
    },

    icon(name: IconName): string | null {
        return ICON_ASSETS[name] ?? null;
    },

    teamCard(teamId: string): string | null {
        return TEAM_HEROES[`${teamId}-hero`] ?? null;
    },

    teamCrest(teamId: string): string | null {
        return TEAM_CRESTS[`${teamId}-crest`] ?? null;
    },

    playerCardArt(teamId: string, position: string): string | null {
        return PLAYER_CARDS[`${teamId}/${positionSlug(position)}`] ?? null;
    },

    pitchBoard(pitchId: string): string | null {
        return PITCH_BOARDS[pitchId] ?? null;
    },

    pitchTiles(pitchId: string): PitchTileSet | null {
        return PITCH_TILES[pitchId] ?? null;
    },

    /** Create-match / splash art — not used for aligned in-game grid rendering. */
    pitchThumb(pitchId: string): string | null {
        return PITCH_BOARDS[pitchId] ?? null;
    },

    challengeCardBack(): string | null {
        return challengeBack;
    },

    challengeCardFace(challengeCondition: string): string | null {
        return CHALLENGE_FACES[assetSlug(challengeCondition)] ?? null;
    },
};
