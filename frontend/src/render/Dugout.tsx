import type { MatchRosterDto, PlayerCardDto, PlayerId, PlayerPieceDto } from "../api/client";
import { assetRegistry } from "../assets/registry";
import { DugoutToken } from "./Token";

type DugoutProps = {
    reserves: PlayerPieceDto[];
    allPlayers: PlayerPieceDto[];
    roster: MatchRosterDto;
    selectedPlayerId?: PlayerId | null | undefined;
    eligiblePlayerIds?: Set<PlayerId> | undefined;
    onSelectPlayer?: ((playerId: PlayerId) => void) | undefined;
    onHoverPlayer?: ((playerId: PlayerId | null) => void) | undefined;
};

function rosterCardById(roster: MatchRosterDto): Map<PlayerId, PlayerCardDto> {
    return new Map(roster.players.map((entry) => [entry.playerId, entry.playerCard]));
}

function teamCatalogId(pieces: PlayerPieceDto[], cards: Map<PlayerId, PlayerCardDto>): string | null {
    for (const piece of pieces) {
        const card = cards.get(piece.id);
        if (card?.teamId) return card.teamId;
    }
    return null;
}

export default function Dugout({
    reserves,
    allPlayers,
    roster,
    selectedPlayerId,
    eligiblePlayerIds,
    onSelectPlayer,
    onHoverPlayer,
}: DugoutProps) {
    const cards = rosterCardById(roster);
    const homeReserves = reserves.filter((piece) => piece.team === "HOME");
    const awayReserves = reserves.filter((piece) => piece.team === "AWAY");
    const homeTeamId = teamCatalogId(
        [...homeReserves, ...allPlayers.filter((piece) => piece.team === "HOME")],
        cards,
    );
    const awayTeamId = teamCatalogId(
        [...awayReserves, ...allPlayers.filter((piece) => piece.team === "AWAY")],
        cards,
    );

    return (
        <section className="dugout" aria-label="Reserves dugout">
            <h2 className="dugout__title">Reserves</h2>
            <div className="dugout__teams">
                <DugoutTeam
                    label="Home reserves"
                    teamId={homeTeamId}
                    pieces={homeReserves}
                    cards={cards}
                    selectedPlayerId={selectedPlayerId}
                    eligiblePlayerIds={eligiblePlayerIds}
                    onSelectPlayer={onSelectPlayer}
                    onHoverPlayer={onHoverPlayer}
                />
                <DugoutTeam
                    label="Away reserves"
                    teamId={awayTeamId}
                    pieces={awayReserves}
                    cards={cards}
                    selectedPlayerId={selectedPlayerId}
                    eligiblePlayerIds={eligiblePlayerIds}
                    onSelectPlayer={onSelectPlayer}
                    onHoverPlayer={onHoverPlayer}
                />
            </div>
        </section>
    );
}

type DugoutTeamProps = {
    label: string;
    teamId?: string | null | undefined;
    pieces: PlayerPieceDto[];
    cards: Map<PlayerId, PlayerCardDto>;
    selectedPlayerId?: PlayerId | null | undefined;
    eligiblePlayerIds?: Set<PlayerId> | undefined;
    onSelectPlayer?: ((playerId: PlayerId) => void) | undefined;
    onHoverPlayer?: ((playerId: PlayerId | null) => void) | undefined;
};

function DugoutTeam({
    label,
    teamId,
    pieces,
    cards,
    selectedPlayerId,
    eligiblePlayerIds,
    onSelectPlayer,
    onHoverPlayer,
}: DugoutTeamProps) {
    const slotWidth = 1.2;
    const svgWidth = Math.max(pieces.length * slotWidth, slotWidth);
    const crest = teamId ? assetRegistry.teamCrest(teamId) : null;

    return (
        <div className={`dugout__team${teamId ? ` dugout__team--${teamId}` : ""}`}>
            <h3 className="dugout__team-label">
                {crest ? <img src={crest} alt="" className="dugout__crest" /> : null}
                {label}
            </h3>
            {pieces.length === 0 ? (
                <p className="dugout__empty">None</p>
            ) : (
                <svg className="dugout__svg" viewBox={`0 0 ${svgWidth} 1`} preserveAspectRatio="xMinYMid meet" role="group" aria-label={label}>
                    {pieces.map((piece, index) => {
                        const x = index * slotWidth + 0.6;
                        const card = cards.get(piece.id);
                        const tokenProps = {
                            piece,
                            x,
                            y: 0.5,
                            selected: selectedPlayerId === piece.id,
                            eligible: eligiblePlayerIds?.has(piece.id) === true && selectedPlayerId !== piece.id,
                        };
                        if (onSelectPlayer) {
                            return <DugoutToken key={piece.id} {...tokenProps} card={card} onSelect={onSelectPlayer} onHover={onHoverPlayer} />;
                        }
                        return <DugoutToken key={piece.id} {...tokenProps} card={card} />;
                    })}
                </svg>
            )}
        </div>
    );
}
