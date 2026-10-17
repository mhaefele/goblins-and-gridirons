import type { PlayerCardDto } from "../api/client";
import { assetRegistry, positionSlug } from "../assets/registry";
import AbilityBadge from "./AbilityBadge";

type PlayerCardProps = {
    card: PlayerCardDto | null;
    variant?: "default" | "roster-preview";
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
    if (armourMinimumRoll == null) return "No armour";
    return `${armourMinimumRoll}+`;
}

export default function PlayerCard({ card, variant = "default" }: PlayerCardProps) {
    const rosterPreview = variant === "roster-preview";

    if (card == null) {
        return (
            <section
                className={`player-card player-card--empty${rosterPreview ? " player-card--roster-preview" : ""}`}
                aria-label="Player card"
            >
                <p>Select a token to view its card.</p>
            </section>
        );
    }

    const art = assetRegistry.playerCardArt(card.teamId, positionSlug(card.positionLabel));
    const crest = assetRegistry.teamCrest(card.teamId);
    const abilityContent = card.ability ? (
        <p className="player-card__ability">
            <AbilityBadge ability={card.ability.key} size="md" className="player-card__ability-badge" />
            <span>
                <strong>{card.ability.key}</strong> — {card.ability.description}
            </span>
        </p>
    ) : rosterPreview && !card.canThrowTeammate ? (
        <p className="player-card__ability player-card__ability--empty">No special ability</p>
    ) : null;

    const throwTeammateNote = card.canThrowTeammate ? (
        <p className="player-card__ability player-card__throw-teammate">
            <span>
                <strong>Throw Team-mate</strong> — Open action: throw an adjacent standing teammate of a
                different position.
            </span>
        </p>
    ) : null;

    const abilityBlock =
        abilityContent != null || throwTeammateNote != null ? (
            <>
                {abilityContent}
                {throwTeammateNote}
            </>
        ) : null;

    return (
        <section
            className={`player-card player-card--hero${rosterPreview ? " player-card--roster-preview" : ""}`}
            aria-label={`${card.positionLabel} card`}
        >
            <div className="player-card__art" style={art ? { backgroundImage: `url(${art})` } : undefined}>
                {crest ? <img className="player-card__crest" src={crest} alt="" /> : null}
                {card.ability ? (
                    <AbilityBadge
                        ability={card.ability.key}
                        size="lg"
                        className="player-card__art-badge"
                        alt=""
                    />
                ) : null}
                <h2 className="player-card__title">{card.positionLabel}</h2>
            </div>
            <dl className="player-card__stats">
                <div className="player-card__row">
                    <dt>Move</dt>
                    <dd>{formatMove(card.move)}</dd>
                </div>
                <div className="player-card__row">
                    <dt>Block dice</dt>
                    <dd>{card.blockDiceCount}</dd>
                </div>
                <div className="player-card__row">
                    <dt>Throw</dt>
                    <dd className={card.throwMinimumRoll == null ? "player-card__muted" : undefined}>{formatThrow(card.throwMinimumRoll)}</dd>
                </div>
                <div className="player-card__row">
                    <dt>Armour</dt>
                    <dd className={card.armourMinimumRoll == null ? "player-card__muted" : undefined}>{formatArmour(card.armourMinimumRoll)}</dd>
                </div>
            </dl>
            {rosterPreview ? <div className="player-card__ability-box">{abilityBlock}</div> : abilityBlock}
        </section>
    );
}
