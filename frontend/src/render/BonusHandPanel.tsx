import type { BonusHandDto, BonusPlay, LegalActionWire } from "../api/client";
import { formatActionType } from "./actionVisuals";
import BonusBadge from "./BonusBadge";

type BonusHandPanelProps = {
    hand: BonusHandDto | null;
    /** Current legal actions — used to hint cards tagged as availableBonusCards / requiredBonusCard. */
    legalActions: LegalActionWire[];
};

/**
 * Bonus plays that unlock or offer a board action right now (not a Play/Skip window — those use the modal),
 * mapped to the label of the action that carries them.
 */
function boardOfferedBonusPlays(legalActions: LegalActionWire[]): Map<BonusPlay, string> {
    const offered = new Map<BonusPlay, string>();
    for (const action of legalActions) {
        const label = formatActionType(action.actionType);
        if (action.requiredBonusCard != null) {
            offered.set(action.requiredBonusCard, label);
        }
        for (const play of action.availableBonusCards ?? []) {
            offered.set(play, label);
        }
    }
    return offered;
}

/** Seat-private bonus hand — play/decline happens in ForcedDecisionDialog or via board actions. */
export default function BonusHandPanel({ hand, legalActions }: BonusHandPanelProps) {
    const cards = hand?.cards ?? [];
    const availableOnBoard = boardOfferedBonusPlays(legalActions);

    return (
        <section className="bonus-hand" aria-label="Bonus hand">
            <header className="bonus-hand__header">
                <span>Bonus hand</span>
                <span className="bonus-hand__count">{cards.length}</span>
            </header>
            {cards.length === 0 ? (
                <p className="bonus-hand__empty">No bonus cards yet</p>
            ) : (
                <ul className="bonus-hand__list">
                    {cards.map((card) => {
                        const name = card.bonusPlay;
                        const effect = card.description;
                        const offeredBy = availableOnBoard.get(name);
                        const boardHint =
                            offeredBy != null ? `To use: take a highlighted ${offeredBy} on the board` : null;
                        const ariaParts = [
                            name,
                            boardHint,
                            effect,
                            boardHint == null && effect == null ? `from ${card.challengeTitle}` : null,
                        ].filter((part): part is string => part != null && part.length > 0);
                        return (
                            <li
                                key={name}
                                className={
                                    boardHint != null ? "bonus-hand__card bonus-hand__card--available" : "bonus-hand__card"
                                }
                                tabIndex={effect != null || boardHint != null ? 0 : undefined}
                                aria-label={ariaParts.join(". ")}
                            >
                                <BonusBadge bonusPlay={name} size="sm" className="bonus-hand__badge" />
                                <div className="bonus-hand__text">
                                    <strong>{name}</strong>
                                    <span className="bonus-hand__from">
                                        from {card.challengeTitle} ({card.points})
                                    </span>
                                    {boardHint != null ? (
                                        <span className="bonus-hand__use-hint">{boardHint}</span>
                                    ) : null}
                                </div>
                                {effect != null || boardHint != null ? (
                                    <p className="bonus-hand__tip" role="tooltip">
                                        {boardHint != null ? (
                                            <>
                                                <span className="bonus-hand__tip-label">How to use</span>
                                                <span className="bonus-hand__tip-body">{boardHint}</span>
                                            </>
                                        ) : null}
                                        {effect != null ? (
                                            <>
                                                <span
                                                    className={
                                                        boardHint != null
                                                            ? "bonus-hand__tip-label bonus-hand__tip-label--follow"
                                                            : "bonus-hand__tip-label"
                                                    }
                                                >
                                                    Effect
                                                </span>
                                                <span className="bonus-hand__tip-body">{effect}</span>
                                            </>
                                        ) : null}
                                    </p>
                                ) : null}
                            </li>
                        );
                    })}
                </ul>
            )}
        </section>
    );
}
