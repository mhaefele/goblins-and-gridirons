import type { LegalActionWire } from "../api/client";
import AbilityBadge from "./AbilityBadge";
import BonusBadge from "./BonusBadge";
import { formatBlockFaceOrderLabel, type ForcedDecision } from "./forcedDecision";

type ForcedDecisionDialogProps = {
    decision: ForcedDecision;
    onChoose: (action: LegalActionWire) => void;
};

/** Modal that blocks the pitch until the coach plays/uses or declines. */
export default function ForcedDecisionDialog({ decision, onChoose }: ForcedDecisionDialogProps) {
    if (decision.kind === "blockFaceOrder") {
        return (
            <div className="forced-decision" role="dialog" aria-modal="true" aria-label={decision.title}>
                <div className="forced-decision__panel">
                    <p className="forced-decision__eyebrow">Special ability</p>
                    <h2 className="forced-decision__title">
                        {decision.ability != null ? (
                            <AbilityBadge ability={decision.ability} size="md" className="forced-decision__badge" />
                        ) : null}
                        {decision.title}
                    </h2>
                    <p className="forced-decision__description">{decision.summary}</p>
                    <p className="forced-decision__impact" role="status">
                        {decision.impact}
                    </p>
                    <ul className="forced-decision__options">
                        {decision.choices.map((choice) => {
                            const order = formatBlockFaceOrderLabel(choice.orderedFaces);
                            return (
                                <li key={choice.id}>
                                    <button
                                        type="button"
                                        className="forced-decision__primary"
                                        title={`Resolve as ${order}`}
                                        onClick={() => onChoose(choice)}
                                    >
                                        <span className="forced-decision__option-label">Resolve as {order}</span>
                                        <span className="forced-decision__option-detail">
                                            Faces apply left to right until Injured
                                        </span>
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                </div>
            </div>
        );
    }

    if (decision.kind === "claim") {
        return (
            <div className="forced-decision" role="dialog" aria-modal="true" aria-label={decision.title}>
                <div className="forced-decision__panel">
                    <p className="forced-decision__eyebrow">Challenge claim</p>
                    <h2 className="forced-decision__title">{decision.title}</h2>
                    <p className="forced-decision__impact" role="status">
                        Choose one eligible active challenge to claim into your bonus hand.
                    </p>
                    <ul className="forced-decision__options">
                        {decision.choices.map((choice) => (
                            <li key={choice.id}>
                                <button
                                    type="button"
                                    className="forced-decision__primary"
                                    title={`Claim ${choice.challengeTitle}`}
                                    onClick={() => onChoose(choice)}
                                >
                                    <span className="forced-decision__option-label">
                                        Claim {choice.challengeTitle}
                                    </span>
                                    <span className="forced-decision__option-detail">
                                        +{choice.points} point{choice.points === 1 ? "" : "s"} · slot{" "}
                                        {choice.slotIndex + 1}
                                    </span>
                                </button>
                            </li>
                        ))}
                    </ul>
                </div>
            </div>
        );
    }

    if (decision.kind === "replace") {
        return (
            <div className="forced-decision" role="dialog" aria-modal="true" aria-label={decision.title}>
                <div className="forced-decision__panel">
                    <p className="forced-decision__eyebrow">Challenge replace</p>
                    <h2 className="forced-decision__title">{decision.title}</h2>
                    <p className="forced-decision__impact" role="status">
                        All three actives are full — discard one so a new challenge can be drawn.
                    </p>
                    <ul className="forced-decision__options">
                        {decision.choices.map((choice) => {
                            const effect = choice.description;
                            return (
                                <li key={choice.id}>
                                    <button
                                        type="button"
                                        className="forced-decision__primary"
                                        title={`Discard ${choice.challengeTitle}`}
                                        onClick={() => onChoose(choice)}
                                    >
                                        <span className="forced-decision__option-label">
                                            Discard {choice.challengeTitle}
                                        </span>
                                        <span className="forced-decision__option-detail">
                                            Slot {choice.slotIndex + 1}
                                        </span>
                                        {effect != null ? (
                                            <span className="forced-decision__option-effect">{effect}</span>
                                        ) : null}
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                </div>
            </div>
        );
    }

    if (decision.kind === "bonus") {
        return (
            <div className="forced-decision" role="dialog" aria-modal="true" aria-label={decision.title}>
                <div className="forced-decision__panel">
                    <p className="forced-decision__eyebrow">Bonus play</p>
                    <h2 className="forced-decision__title">{decision.title}</h2>
                    {decision.impact != null ? (
                        <p className="forced-decision__impact" role="status">
                            {decision.impact}
                        </p>
                    ) : null}
                    <ul className="forced-decision__options">
                        {decision.plays.map((play) => {
                            const name = play.bonusPlay;
                            const effect = play.description;
                            return (
                                <li key={play.id}>
                                    <button
                                        type="button"
                                        className="forced-decision__primary forced-decision__primary--bonus"
                                        title={`Play ${name}`}
                                        onClick={() => onChoose(play)}
                                    >
                                        <BonusBadge bonusPlay={name} size="md" className="forced-decision__badge" />
                                        <span className="forced-decision__option-copy">
                                            <span className="forced-decision__option-label">Play {name}</span>
                                            {effect != null ? (
                                                <span className="forced-decision__option-detail">{effect}</span>
                                            ) : null}
                                        </span>
                                    </button>
                                </li>
                            );
                        })}
                        {decision.abilityReroll != null ? (
                            <li>
                                <button
                                    type="button"
                                    className="forced-decision__primary"
                                    title={decision.abilityReroll.useLabel}
                                    onClick={() => onChoose(decision.abilityReroll!.use)}
                                >
                                    {decision.abilityReroll.useLabel}
                                </button>
                            </li>
                        ) : null}
                    </ul>
                    {decision.abilityReroll != null ? (
                        <button
                            type="button"
                            className="forced-decision__decline"
                            title={decision.abilityReroll.keepLabel}
                            onClick={() => onChoose(decision.abilityReroll!.keep)}
                        >
                            {decision.abilityReroll.keepLabel}
                        </button>
                    ) : null}
                    {decision.skip != null ? (
                        <button
                            type="button"
                            className="forced-decision__decline"
                            title="Decline this bonus window"
                            onClick={() => onChoose(decision.skip!)}
                        >
                            Decline
                        </button>
                    ) : null}
                </div>
            </div>
        );
    }

    return (
        <div className="forced-decision" role="dialog" aria-modal="true" aria-label={decision.title}>
            <div className="forced-decision__panel">
                <p className="forced-decision__eyebrow">Special ability</p>
                <h2 className="forced-decision__title">
                    {decision.ability != null ? (
                        <AbilityBadge ability={decision.ability} size="md" className="forced-decision__badge" />
                    ) : null}
                    {decision.title}
                </h2>
                {decision.description != null ? (
                    <p className="forced-decision__description">{decision.description}</p>
                ) : null}
                {decision.impact != null ? (
                    <p className="forced-decision__impact" role="status">
                        {decision.impact}
                    </p>
                ) : null}
                <ul className="forced-decision__options">
                    <li>
                        <button
                            type="button"
                            className="forced-decision__primary"
                            title={decision.useLabel}
                            onClick={() => onChoose(decision.use)}
                        >
                            {decision.useLabel}
                        </button>
                    </li>
                </ul>
                <button
                    type="button"
                    className="forced-decision__decline"
                    title={decision.declineLabel}
                    onClick={() => onChoose(decision.decline)}
                >
                    {decision.declineLabel}
                </button>
            </div>
        </div>
    );
}
