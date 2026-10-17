import type { ChallengeTableauDto } from "../api/client";
import { assetRegistry } from "../assets/registry";

type ChallengeTableauProps = {
    challenges: ChallengeTableauDto;
    /** Coach turn number — claims are locked on Coach A's first turn (turn 1). */
    turnNumber?: number | undefined;
    /** Turns left in Points Victory countdown; null when not armed. */
    pointsVictoryTurnsRemaining?: number | null | undefined;
    /** Active slot currently being claimed (celebration highlight). */
    claimingSlotIndex?: number | null | undefined;
};

/**
 * Public challenge faces: three actives + deck top (next card) in one row.
 * Faces use registry art keyed by challengeCondition; otherwise the shared back plate.
 */
export default function ChallengeTableau({
    challenges,
    turnNumber,
    pointsVictoryTurnsRemaining,
    claimingSlotIndex = null,
}: ChallengeTableauProps) {
    const back = assetRegistry.challengeCardBack() ?? undefined;
    const claimsLocked = turnNumber === 1;
    return (
        <section className="challenge-tableau" aria-label="Challenge cards">
            <div className="challenge-tableau__row">
                {challenges.activeSlots.map((slot, index) => (
                    <ChallengeFace
                        key={slot != null ? `${index}-${slot.challengeCondition}` : `empty-${index}`}
                        title={slot?.title ?? "Empty"}
                        points={slot?.points}
                        challengeCondition={slot?.challengeCondition}
                        description={slot?.description}
                        backUrl={back}
                        empty={slot == null}
                        claiming={claimingSlotIndex === index}
                        claimsLocked={claimsLocked}
                    />
                ))}
                <div className="challenge-tableau__deck" title={`${challenges.deckRemaining} left in deck`}>
                    <ChallengeFace
                        title={challenges.deckTop?.title ?? "Deck"}
                        points={challenges.deckTop?.points}
                        challengeCondition={challenges.deckTop?.challengeCondition}
                        description={challenges.deckTop?.description}
                        backUrl={back}
                        empty={challenges.deckTop == null}
                        deck
                    />
                    <span className="challenge-tableau__deck-label">Next</span>
                    <span className="challenge-tableau__deck-count">{challenges.deckRemaining}</span>
                </div>
            </div>
            {claimsLocked ? (
                <p
                    className="challenge-tableau__claims-locked"
                    role="status"
                    title="Challenge cards cannot be claimed during Coach A's first turn"
                >
                    First turn — no claims
                </p>
            ) : null}
            {pointsVictoryTurnsRemaining != null && pointsVictoryTurnsRemaining > 0 ? (
                <p
                    className={`challenge-tableau__last-turn${
                        pointsVictoryTurnsRemaining === 1 ? " challenge-tableau__last-turn--final" : ""
                    }`}
                    role="status"
                    title={
                        pointsVictoryTurnsRemaining === 1
                            ? "Final coach turn — Points Victory after this turn"
                            : "Last Challenge Card dealt — each coach has one turn left, then highest score wins"
                    }
                >
                    {pointsVictoryTurnsRemaining === 1
                        ? "Final turn — Points Victory next"
                        : "Last Challenge dealt — last turns"}
                </p>
            ) : null}
            <div className="challenge-tableau__meta">
                <span title="Challenge cards claimed this coach turn">Claims {challenges.claimsThisTurn}</span>
                <span title="Discard pile size">Discard {challenges.discardCount}</span>
            </div>
        </section>
    );
}

function ChallengeFace({
    title,
    points,
    challengeCondition,
    description,
    backUrl,
    empty,
    deck = false,
    claiming = false,
    claimsLocked = false,
}: {
    title: string;
    points?: number | undefined;
    challengeCondition?: string | undefined;
    description?: string | undefined;
    backUrl?: string | undefined;
    empty: boolean;
    deck?: boolean;
    claiming?: boolean;
    claimsLocked?: boolean;
}) {
    const face =
        challengeCondition != null ? assetRegistry.challengeCardFace(challengeCondition) : null;
    const src = face ?? backUrl;
    const claim = !empty ? description : undefined;
    const emptyLabel = empty ? (deck ? "Deck empty" : "Empty active slot") : undefined;
    const locked = claimsLocked && !empty && !deck;
    const aria =
        empty ? emptyLabel
        : locked ? `${title}. Cannot claim on Coach A's first turn`
        : claim != null ? `${title}. ${claim}`
        : title;
    return (
        <article
            className={`challenge-face${empty ? " challenge-face--empty" : ""}${deck ? " challenge-face--deck" : ""}${claiming ? " challenge-face--claiming" : ""}${locked ? " challenge-face--claims-locked" : ""}`}
            tabIndex={claim != null ? 0 : undefined}
            aria-label={aria}
        >
            <div className="challenge-face__frame">
                {src ? <img className="challenge-face__art" src={src} alt="" draggable={false} /> : null}
                {locked ? (
                    <span className="challenge-face__lock" title="Cannot claim on Coach A's first turn">
                        No claim
                    </span>
                ) : null}
                {!empty ? (
                    <div className="challenge-face__caption">
                        <span className="challenge-face__title">{title}</span>
                        {points != null ? <span className="challenge-face__pts">{points}</span> : null}
                    </div>
                ) : (
                    <span className="challenge-face__empty-label">{deck ? "—" : "·"}</span>
                )}
            </div>
            {claim != null ? (
                <p className="challenge-face__tip" role="tooltip">
                    <span className="challenge-face__tip-label">{locked ? "Locked" : "Claim"}</span>
                    {locked ? "Cannot claim during Coach A's first turn." : claim}
                </p>
            ) : null}
        </article>
    );
}
