import type { PlayerCardDto } from "../api/client";
import { assetRegistry, positionSlug } from "../assets/registry";
import AbilityBadge from "../render/AbilityBadge";

type RosterRow = {
    card: PlayerCardDto;
    qty: number;
};

type TeamRosterTableProps = {
    teamId: string;
    teamName: string;
    roster: PlayerCardDto[];
};

function formatMove(move: number | null | undefined): string {
    if (move == null) return "—";
    return String(move);
}

function formatThrow(throwMinimumRoll: number | null | undefined): string {
    if (throwMinimumRoll == null) return "—";
    return `${throwMinimumRoll}+`;
}

function formatArmour(armourMinimumRoll: number | null | undefined): string {
    if (armourMinimumRoll == null) return "—";
    return `${armourMinimumRoll}+`;
}

/** Collapse duplicate position slots into Qty rows, preserving first-seen order. */
export function groupRosterByPosition(roster: PlayerCardDto[]): RosterRow[] {
    const rows: RosterRow[] = [];
    const indexByLabel = new Map<string, number>();
    for (const card of roster) {
        const existing = indexByLabel.get(card.positionLabel);
        if (existing != null) {
            rows[existing]!.qty += 1;
            continue;
        }
        indexByLabel.set(card.positionLabel, rows.length);
        rows.push({ card, qty: 1 });
    }
    return rows;
}

const THROW_TEAMMATE_DESCRIPTION =
    "Open action: throw an adjacent standing teammate of a different position.";

function SpecialRules({ card }: { card: PlayerCardDto }) {
    if (!card.ability?.key && !card.canThrowTeammate) {
        return <span className="team-roster-table__muted">—</span>;
    }
    return (
        <div className="team-roster-table__rules">
            {card.ability ? (
                <p className="team-roster-table__rule">
                    <AbilityBadge ability={card.ability.key} size="sm" className="team-roster-table__ability-badge" />
                    <span>
                        <strong>{card.ability.key}</strong> — {card.ability.description}
                    </span>
                </p>
            ) : null}
            {card.canThrowTeammate ? (
                <p className="team-roster-table__rule">
                    <span>
                        <strong>Throw Team-mate</strong> — {THROW_TEAMMATE_DESCRIPTION}
                    </span>
                </p>
            ) : null}
        </div>
    );
}

export default function TeamRosterTable({ teamId, teamName, roster }: TeamRosterTableProps) {
    const rows = groupRosterByPosition(roster);

    return (
        <div className="team-roster-table" data-testid="team-roster-table">
            <table>
                <caption className="visually-hidden">{teamName} full roster</caption>
                <thead>
                    <tr>
                        <th scope="col">Icon</th>
                        <th scope="col">Position</th>
                        <th scope="col">Qty</th>
                        <th scope="col">Move</th>
                        <th scope="col">Block Dice</th>
                        <th scope="col">Throw</th>
                        <th scope="col">Armour</th>
                        <th scope="col">Special Rules</th>
                    </tr>
                </thead>
                <tbody>
                    {rows.map(({ card, qty }) => {
                        const slug = positionSlug(card.positionLabel);
                        const token = assetRegistry.token({ teamId, position: slug, status: "standing" });
                        const art = assetRegistry.playerCardArt(teamId, slug);
                        return (
                            <tr key={card.positionLabel}>
                                <td className="team-roster-table__icon-cell">
                                    <span className="team-roster-table__icon">
                                        {token ? (
                                            <img src={token} alt="" draggable={false} />
                                        ) : art ? (
                                            <img src={art} alt="" draggable={false} />
                                        ) : (
                                            <span aria-hidden>•</span>
                                        )}
                                    </span>
                                </td>
                                <td className="team-roster-table__position">{card.positionLabel}</td>
                                <td>{qty}</td>
                                <td>{formatMove(card.move)}</td>
                                <td>{card.blockDiceCount}</td>
                                <td>{formatThrow(card.throwMinimumRoll)}</td>
                                <td>{formatArmour(card.armourMinimumRoll)}</td>
                                <td>
                                    <SpecialRules card={card} />
                                </td>
                            </tr>
                        );
                    })}
                </tbody>
            </table>
        </div>
    );
}
