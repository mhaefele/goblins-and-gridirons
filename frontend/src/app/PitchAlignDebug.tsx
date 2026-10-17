import type { ReactNode } from "react";
import type { PitchLayoutDto } from "../api/client";
import { assetRegistry } from "../assets/registry";
import Pitch from "../render/Pitch";
import { BOARD_IMAGE_CONTENT } from "../render/boardImageLayout";
import { coordKey, isPlayableSquare } from "../render/pitchLayout";
import { PITCH_ALIGN_COLORS, PITCH_ALIGN_LAYOUTS } from "./pitchAlignLayouts";

/** Path for the pitch alignment preview (not a query param). */
export const PITCH_ALIGN_PATH = "/pitch-align";

export function isPitchAlignPath(pathname = window.location.pathname): boolean {
    return pathname === PITCH_ALIGN_PATH || pathname === `${PITCH_ALIGN_PATH}/`;
}

function AlignOverlay({ layout }: { layout: PitchLayoutDto }) {
    const blocked = new Set(layout.blockedSquares.map(coordKey));
    const trapdoors = new Set(layout.trapdoors.map(coordKey));
    const cells: ReactNode[] = [];

    for (let y = 1; y <= layout.playHeight; y++) {
        for (let x = 1; x <= layout.playWidth; x++) {
            if (!isPlayableSquare(layout, x, y)) continue;
            const key = coordKey({ x, y });
            const isBlocked = blocked.has(key);
            const isTrapdoor = trapdoors.has(key);

            if (isTrapdoor) {
                cells.push(
                    <rect
                        key={`td-${key}`}
                        data-align="trapdoor"
                        x={x + 0.08}
                        y={y + 0.08}
                        width={0.84}
                        height={0.84}
                        fill={`${PITCH_ALIGN_COLORS.trapdoor}66`}
                        stroke={PITCH_ALIGN_COLORS.trapdoor}
                        strokeWidth={0.08}
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
            } else if (isBlocked) {
                cells.push(
                    <rect
                        key={`ob-${key}`}
                        data-align="obstacle"
                        x={x + 0.06}
                        y={y + 0.06}
                        width={0.88}
                        height={0.88}
                        fill={`${PITCH_ALIGN_COLORS.obstacle}55`}
                        stroke={PITCH_ALIGN_COLORS.obstacle}
                        strokeWidth={0.07}
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
            } else {
                cells.push(
                    <rect
                        key={`g-${key}`}
                        data-align="grid"
                        x={x}
                        y={y}
                        width={1}
                        height={1}
                        fill="none"
                        stroke={PITCH_ALIGN_COLORS.grid}
                        strokeWidth={0.045}
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
            }
        }
    }

    return <g data-testid="pitch-align-overlay">{cells}</g>;
}

function BoardPair({ layout }: { layout: PitchLayoutDto }) {
    const id = layout.pitchDefinitionId;
    const boardUrl = assetRegistry.pitchBoard(id);
    const content = BOARD_IMAGE_CONTENT[id] ?? null;

    return (
        <section className="pitch-align-debug__board" data-testid={`pitch-align-${id}`} data-pitch={id}>
            <h2>{id}</h2>
            <div className="pitch-align-debug__pair">
                <div className="pitch-align-debug__panel" data-testid={`pitch-align-${id}-grid`}>
                    <h3>Grid overlay</h3>
                    <Pitch
                        layout={layout}
                        boardImageUrl={boardUrl}
                        boardImageContent={content}
                        className="pitch-align-debug__svg"
                    >
                        <AlignOverlay layout={layout} />
                    </Pitch>
                </div>
                <div className="pitch-align-debug__panel" data-testid={`pitch-align-${id}-play`}>
                    <h3>In-match display</h3>
                    <Pitch
                        layout={layout}
                        boardImageUrl={boardUrl}
                        boardImageContent={content}
                        className="pitch-align-debug__svg"
                    />
                </div>
            </div>
        </section>
    );
}

export default function PitchAlignDebug() {
    return (
        <main className="pitch-align-debug" data-testid="pitch-align-debug">
            <header className="pitch-align-debug__header">
                <h1>Pitch grid alignment</h1>
                <p>
                    <span className="pitch-align-debug__swatch pitch-align-debug__swatch--grid" /> playable grid
                    <span className="pitch-align-debug__swatch pitch-align-debug__swatch--obstacle" /> obstacles
                    <span className="pitch-align-debug__swatch pitch-align-debug__swatch--trapdoor" /> trapdoors
                </p>
                <p className="pitch-align-debug__hint">
                    <code>{PITCH_ALIGN_PATH}</code> — left: debug grid; right: as shown in play. Layouts from
                    PitchCatalog.
                </p>
            </header>
            <div className="pitch-align-debug__boards">
                {PITCH_ALIGN_LAYOUTS.map((layout) => (
                    <BoardPair key={layout.pitchDefinitionId} layout={layout} />
                ))}
            </div>
        </main>
    );
}
