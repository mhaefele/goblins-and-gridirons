import type { components, paths } from "./generated/openapi";

export type schemas = components["schemas"];

export type CreateMatchRequest = schemas["CreateMatchRequest"];
export type CreateMatchResponse = schemas["CreateMatchResponse"];
export type ComputerOpponentKind = NonNullable<schemas["ComputerOpponentOptionDto"]["kind"]>;
export type ComputerOpponentOptionDto = {
    kind: ComputerOpponentKind;
    onnxProfile: string | null;
    title: string;
    description: string;
};
export type ComputerOpponentCatalogDto = {
    opponents: ComputerOpponentOptionDto[];
    defaultKind: ComputerOpponentKind;
    defaultOnnxProfile: string | null;
};
export type MatchSnapshotDto = Omit<schemas["MatchSnapshotDto"], "gameState"> & {
    gameState: MatchGameStateDto;
};
export type PitchLayoutDto = schemas["PitchLayoutDto"];
export type MatchRosterDto = schemas["MatchRosterDto"];
export type MatchActionResponse = schemas["MatchActionResponse"];
export type LegalAction = schemas["LegalAction"];
export type LegalActionId = schemas["LegalActionId"];

/*
 * Backend enums — derived from generated schemas so renames flow through `./gen-api.sh`.
 * Springdoc marks every field optional, hence `NonNullable`.
 */
export type LegalActionType = NonNullable<schemas["ActionComplete"]["actionType"]>;
export type PlayerDecisionType = NonNullable<schemas["PlayerAction"]["decisionType"]>;
export type PlayerAbility = NonNullable<schemas["PlayerAction"]["ability"]>;
export type BonusPlay = NonNullable<schemas["RequiredBonusCard"]["bonusPlay"]>;
export type ChallengeClaimCondition = NonNullable<schemas["ChallengeCardDto"]["challengeCondition"]>;
export type BlockDieFace = NonNullable<schemas["DiceRolled"]["blockResult"]>;
export type ThrowRange = NonNullable<schemas["ChooseThrowReroll"]["range"]>;
export type BallEncounter = NonNullable<schemas["Run"]["ballEncounter"]>;
export type ClaimCertainty = NonNullable<schemas["PotentialChallengeClaim"]["certainty"]>;
export type TeamSide = NonNullable<schemas["MatchGameStateDto"]["activeCoach"]>;

/** Shared fields on every legal action (OpenAPI omits sealed-parent props on leaves). */
export type LegalActionCommon = {
    /** Present on wire from the server; tests may omit. */
    id?: LegalActionId;
    actionType: LegalActionType;
    /** Present when this action is illegal without playing that bonus (id encodes it). */
    requiredBonusCard?: { bonusPlay: BonusPlay } | null;
    /** Optional bonuses offered if you take this action (Back on Your Feet, Dead Eye, …) — not in id. */
    availableBonusCards?: { bonusPlay: BonusPlay }[];
    /**
     * Active challenges this action could earn if resolved as expected (D1.F.4).
     * Distinct from availableBonusCards (play-from-hand).
     */
    potentialChallengeClaims?: {
        claimCondition: ChallengeClaimCondition;
        challengeTitle: string;
        points: number;
        certainty: ClaimCertainty;
    }[];
    /**
     * Path / reserves onto a loose ball: pick up vs bounce (server-annotated).
     * Absent when the action does not enter a ball square.
     */
    ballEncounter?: BallEncounter | null;
};

/** Player-scoped legal actions. */
export type PlayerLegalActionBase = LegalActionCommon & {
    playerId: PlayerId;
};

/** Player actions the coach may take by clicking a pitch square. */
export type BoardTargetedLegalActionBase = PlayerLegalActionBase & {
    targetCoordinate: Coordinate;
};

/** @deprecated Prefer PlayerLegalActionBase / BoardTargetedLegalActionBase. */
export type LegalActionBase = BoardTargetedLegalActionBase;

/** Server `BonusCardChoice` — spends a hand card; carries its name and card text. */
type BonusCardChoiceFields = {
    bonusPlay: BonusPlay;
    description: string;
};

/** After-dice Do-Over adjust — fields carry the held roll for UI impact. */
export type AdjustThrowByDoOverWire = PlayerLegalActionBase & BonusCardChoiceFields & {
    kind: "adjustThrowByDoOver";
    destination: Coordinate;
    naturalRoll: number;
    range: ThrowRange;
    obstructed: boolean;
};

export type AdjustBlockByDoOverWire = PlayerLegalActionBase & BonusCardChoiceFields & {
    kind: "adjustBlockByDoOver";
    destination: Coordinate;
    faces: BlockDieFace[];
    assisted: boolean;
};

export type AdjustArmourByDoOverWire = LegalActionCommon & BonusCardChoiceFields & {
    kind: "adjustArmourByDoOver";
    targetId: string;
    naturalRoll: number;
};

export type AdjustDoOverWire =
    | AdjustThrowByDoOverWire
    | AdjustBlockByDoOverWire
    | AdjustArmourByDoOverWire;

/** Generated leaves that carry `bonusPlay` — the server's `BonusCardChoice` subtypes. */
type BonusCardChoiceLeaf<U> = U extends unknown ? ("bonusPlay" extends keyof U ? U : never) : never;
type PlayBonusKind = Exclude<BonusCardChoiceLeaf<LegalAction>["kind"], AdjustDoOverWire["kind"]>;

export type PlayBonusWire = LegalActionCommon & BonusCardChoiceFields & { kind: PlayBonusKind };

export type ForcedBonusPlayWire = PlayBonusWire | AdjustDoOverWire;

type ThrowTargetWire = { destination: Coordinate; range: ThrowRange; obstructed: boolean };

export type LegalActionWire =
    | (BoardTargetedLegalActionBase & { kind: "run"; path: Coordinate[] })
    | (BoardTargetedLegalActionBase & { kind: "mark"; path: Coordinate[] })
    | (BoardTargetedLegalActionBase & { kind: "sidestep"; path: Coordinate[] })
    | (BoardTargetedLegalActionBase & { kind: "throw"; target: ThrowTargetWire })
    | (BoardTargetedLegalActionBase & {
          kind: "throwTeammateSelect";
          teammateId: PlayerId;
      })
    | (BoardTargetedLegalActionBase & {
          kind: "throwTeammate";
          teammateId: PlayerId;
          target: ThrowTargetWire;
      })
    | (PlayerLegalActionBase & { kind: "cancelThrowTeammate" })
    | (BoardTargetedLegalActionBase & { kind: "block"; destination: Coordinate })
    | (BoardTargetedLegalActionBase & { kind: "foul"; destination: Coordinate })
    | (BoardTargetedLegalActionBase & { kind: "standUp" })
    | (BoardTargetedLegalActionBase & { kind: "reserves"; placement: Coordinate })
    | (PlayerLegalActionBase & { kind: "skip" })
    | (LegalActionCommon & { kind: "declineBonus" })
    | (BoardTargetedLegalActionBase & { kind: "placeLooseBall"; from: Coordinate; to: Coordinate })
    | (PlayerLegalActionBase & {
          kind: "chooseBlockReroll";
          destination: Coordinate;
          faces: BlockDieFace[];
          assisted: boolean;
          reroll: boolean;
      })
    | (PlayerLegalActionBase & {
          kind: "chooseBlockFaceOrder";
          destination: Coordinate;
          orderedFaces: BlockDieFace[];
      })
    | (PlayerLegalActionBase & {
          kind: "chooseThrowReroll";
          destination: Coordinate;
          naturalRoll: number;
          range: ThrowRange;
          obstructed: boolean;
          reroll: boolean;
      })
    | AdjustThrowByDoOverWire
    | AdjustBlockByDoOverWire
    | AdjustArmourByDoOverWire
    | (BoardTargetedLegalActionBase & { kind: "continueBlock"; destination: Coordinate })
    | (BoardTargetedLegalActionBase & { kind: "quickReleaseThrow"; target: ThrowTargetWire })
    | (BoardTargetedLegalActionBase & { kind: "declareRun" })
    | PlayBonusWire
    | (LegalActionCommon & {
          kind: "claimChallenge";
          slotIndex: number;
          claimCondition: ChallengeClaimCondition;
          challengeTitle: string;
          points: number;
      })
    | (LegalActionCommon & {
          kind: "replaceChallenge";
          slotIndex: number;
          claimCondition: ChallengeClaimCondition;
          challengeTitle: string;
          /** Claim-condition card text. */
          description: string;
      });

export type DomainEvent = schemas["DomainEvent"];
export type StoredDomainEvent = schemas["StoredDomainEvent"];
export type TeamCatalogDto = schemas["TeamCatalogDto"];
export type TeamCatalogEntry = schemas["TeamCatalogEntry"];
export type TeamSummary = schemas["TeamSummary"];
export type TeamRatings = schemas["TeamRatings"];
export type TeamComplexityLabel = NonNullable<TeamSummary["complexityLabel"]>;
export type PitchCatalogDto = schemas["PitchCatalogDto"];
export type PitchCatalogEntry = schemas["PitchCatalogEntry"];
export type PlayerPieceDto = schemas["PlayerPieceDto"];
export type PlayerCardDto = schemas["PlayerCardDto"];
export type MatchGameStateDto = Omit<schemas["MatchGameStateDto"], "challenges"> & {
    challenges?: ChallengeTableauDto;
};

/** Challenge card as shown on the public tableau / deck top. */
export type ChallengeCardDto = Required<schemas["ChallengeCardDto"]>;

export type ChallengeTableauDto = {
    activeSlots: Array<ChallengeCardDto | null>;
    deckTop?: ChallengeCardDto | null;
    deckRemaining: number;
    discardCount: number;
    claimsThisTurn: number;
};

/** Seat-private bonus hand — never on the shared snapshot. */
export type BonusHandDto = {
    cards: Array<Required<schemas["BonusHandCardDto"]>>;
};

/** Seat owner — OpenAPI inlines the enum on request/DTO fields. */
export type CoachControl = MatchGameStateDto["homeControlledBy"];
export type PieceSnapshotDto = schemas["PieceSnapshotDto"];
export type Coordinate = schemas["Coordinate"];
export type MatchId = schemas["MatchId"];
export type PlayerId = schemas["PlayerId"];

export class ApiError extends Error {
    readonly status: number;
    readonly body: unknown;

    constructor(status: number, body: unknown, message: string) {
        super(message);
        this.name = "ApiError";
        this.status = status;
        this.body = body;
    }
}

type JsonResponse<T> = Promise<T>;

async function readJson(response: Response): Promise<unknown> {
    const text = await response.text();
    if (!text) return undefined;
    try {
        return JSON.parse(text) as unknown;
    } catch {
        return text;
    }
}

async function requestJson<T>(path: string, init?: RequestInit): JsonResponse<T> {
    const response = await fetch(path, {
        ...init,
        headers: {
            Accept: "application/json",
            ...(init?.body ? { "Content-Type": "application/json" } : {}),
            ...init?.headers,
        },
    });
    const body = await readJson(response);
    if (!response.ok) {
        const message =
            typeof body === "object" && body !== null
                ? "detail" in body && typeof (body as { detail: unknown }).detail === "string"
                    ? (body as { detail: string }).detail
                    : "message" in body && typeof (body as { message: unknown }).message === "string"
                      ? (body as { message: string }).message
                      : `HTTP ${response.status} for ${path}`
                : `HTTP ${response.status} for ${path}`;
        throw new ApiError(response.status, body, message);
    }
    return body as T;
}

function matchBase(matchId: MatchId): string {
    return `/api/matches/${encodeURIComponent(matchId)}`;
}

export async function listTeams(): Promise<TeamCatalogDto> {
    return requestJson("/api/catalog/teams");
}

export async function listPitches(): Promise<PitchCatalogDto> {
    return requestJson("/api/catalog/pitches");
}

export async function listComputerOpponents(): Promise<ComputerOpponentCatalogDto> {
    return requestJson("/api/computer-opponents");
}

export async function createMatch(body: CreateMatchRequest): Promise<CreateMatchResponse> {
    return requestJson("/api/matches", { method: "POST", body: JSON.stringify(body) });
}

export async function startMatch(matchId: MatchId): Promise<MatchActionResponse> {
    return requestJson(`${matchBase(matchId)}/start`, { method: "POST" });
}

export async function getSnapshot(matchId: MatchId): Promise<MatchSnapshotDto> {
    return requestJson(`${matchBase(matchId)}/snapshot`);
}

export async function getPitch(matchId: MatchId): Promise<PitchLayoutDto> {
    return requestJson(`${matchBase(matchId)}/pitch`);
}

export async function getRoster(matchId: MatchId): Promise<MatchRosterDto> {
    return requestJson(`${matchBase(matchId)}/roster`);
}

/** Seat-private bonus hand — never fold into the shared snapshot. */
export async function getBonusHand(matchId: MatchId, seat: TeamSide): Promise<BonusHandDto> {
    return requestJson(`${matchBase(matchId)}/bonus-hand?seat=${encodeURIComponent(seat)}`);
}

export async function getLegalActions(matchId: MatchId): Promise<LegalActionWire[]> {
    return requestJson(`${matchBase(matchId)}/legal-actions`);
}

export async function postSubmitAction(matchId: MatchId, legalActionId: LegalActionId): Promise<MatchActionResponse> {
    return requestJson(`${matchBase(matchId)}/legal-actions/submit`, { method: "POST", body: JSON.stringify(legalActionId) });
}


/** Compile-time reminder that paths stay in lockstep with the generated OpenAPI doc. */
export type MatchPaths = keyof paths;
