import type { PitchLayoutDto } from "../api/client";

/**
 * Hardcoded copies of backend `PitchCatalog` layouts for offline grid-alignment
 * debugging (no match / API required).
 */
export const PITCH_ALIGN_LAYOUTS: PitchLayoutDto[] = [
    {
        pitchDefinitionId: "default",
        playWidth: 11,
        playHeight: 15,
        homeEndZoneRow: 1,
        awayEndZoneRow: 15,
        trapdoors: [{ x: 6, y: 8 }],
        blockedSquares: [
            { x: 3, y: 4 },
            { x: 4, y: 4 },
            { x: 3, y: 5 },
            { x: 4, y: 5 },
            { x: 8, y: 4 },
            { x: 9, y: 4 },
            { x: 8, y: 5 },
            { x: 9, y: 5 },
            { x: 3, y: 11 },
            { x: 4, y: 11 },
            { x: 3, y: 12 },
            { x: 4, y: 12 },
            { x: 8, y: 11 },
            { x: 9, y: 11 },
            { x: 8, y: 12 },
            { x: 9, y: 12 },
        ],
    },
    {
        pitchDefinitionId: "barricade",
        playWidth: 11,
        playHeight: 15,
        homeEndZoneRow: 1,
        awayEndZoneRow: 15,
        trapdoors: [
            { x: 3, y: 8 },
            { x: 9, y: 8 },
        ],
        blockedSquares: [
            { x: 6, y: 3 },
            { x: 6, y: 8 },
            { x: 6, y: 13 },
            { x: 3, y: 5 },
            { x: 4, y: 5 },
            { x: 8, y: 5 },
            { x: 9, y: 5 },
            { x: 3, y: 11 },
            { x: 4, y: 11 },
            { x: 8, y: 11 },
            { x: 9, y: 11 },
        ],
    },
];

export const PITCH_ALIGN_COLORS = {
    grid: "#22c55e",
    obstacle: "#ef4444",
    trapdoor: "#06b6d4",
} as const;
