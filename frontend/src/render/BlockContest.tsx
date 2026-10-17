import type { PlayerCardDto, PlayerPieceDto } from "../api/client";
import { squareCenter } from "./pitchLayout";

type BlockContestProps = {
    attacker: PlayerPieceDto;
    defender: PlayerPieceDto;
    attackerCard?: PlayerCardDto | undefined;
    defenderCard?: PlayerCardDto | undefined;
};

function shortName(piece: PlayerPieceDto, card: PlayerCardDto | undefined): string {
    return card?.positionLabel ?? piece.id;
}

/** Line + labels showing who is blocking whom while block dice resolve. */
export default function BlockContest({ attacker, defender, attackerCard, defenderCard }: BlockContestProps) {
    if (attacker.at == null || defender.at == null) return null;

    const from = squareCenter(attacker.at.x, attacker.at.y);
    const to = squareCenter(defender.at.x, defender.at.y);
    const midX = (from.cx + to.cx) / 2;
    const midY = (from.cy + to.cy) / 2;
    const attackerLabel = shortName(attacker, attackerCard);
    const defenderLabel = shortName(defender, defenderCard);
    const tip = `${attacker.id} blocks ${defender.id}`;

    return (
        <g className="block-contest" data-testid="block-contest" role="img" aria-label={tip} pointerEvents="none">
            <title>{tip}</title>
            <line
                className="block-contest__line"
                x1={from.cx}
                y1={from.cy}
                x2={to.cx}
                y2={to.cy}
                stroke="#fb923c"
                strokeWidth={0.08}
                strokeDasharray="0.18 0.12"
                strokeLinecap="round"
            />
            <circle className="block-contest__ring" cx={from.cx} cy={from.cy} r={0.58} fill="none" stroke="#f97316" strokeWidth={0.07} />
            <circle className="block-contest__ring" cx={to.cx} cy={to.cy} r={0.58} fill="none" stroke="#fb923c" strokeWidth={0.07} />
            <rect className="block-contest__label-bg" x={midX - 1.1} y={midY - 0.82} width={2.2} height={0.38} rx={0.08} fill="#0c0a09" opacity={0.82} />
            <text
                className="block-contest__label"
                x={midX}
                y={midY - 0.55}
                textAnchor="middle"
                dominantBaseline="auto"
                fontSize={0.28}
                fontWeight={800}
            >
                {attackerLabel} ▸ {defenderLabel}
            </text>
        </g>
    );
}
