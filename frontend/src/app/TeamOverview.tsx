import type { PlayerCardDto, TeamComplexityLabel, TeamRatings, TeamSummary } from "../api/client";
import AbilityBadge from "../render/AbilityBadge";
import { AgilityIcon, ArmorIcon, BashIcon, RadarMeshIcon, SpeedIcon } from "./TeamStatIcons";

type TeamOverviewProps = {
    teamName: string;
    summary: TeamSummary;
    roster: PlayerCardDto[];
};

const THROW_TEAMMATE_DESCRIPTION =
    "Open action: throw an adjacent standing teammate of a different position.";

type AbilityTag = {
    key: string;
    description: string;
};

/** Unique special abilities across the roster (first-seen order), plus Throw Team-mate when present. */
export function collectRosterAbilityTags(roster: PlayerCardDto[]): AbilityTag[] {
    const tags: AbilityTag[] = [];
    const seen = new Set<string>();
    let hasThrowTeammate = false;
    for (const card of roster) {
        if (card.canThrowTeammate) hasThrowTeammate = true;
        const ability = card.ability;
        if (ability == null || seen.has(ability.key)) continue;
        seen.add(ability.key);
        tags.push({ key: ability.key, description: ability.description });
    }
    if (hasThrowTeammate) {
        tags.push({ key: "Throw Team-mate", description: THROW_TEAMMATE_DESCRIPTION });
    }
    return tags;
}

const COMPLEXITY_CLASS: Record<TeamComplexityLabel, string> = {
    Beginner: "team-complexity--beginner",
    Intermediate: "team-complexity--intermediate",
    Advanced: "team-complexity--advanced",
};

const RATING_ROWS: Array<{ key: keyof TeamRatings; label: string; Icon: typeof SpeedIcon }> = [
    { key: "speed", label: "Speed", Icon: SpeedIcon },
    { key: "bash", label: "Bash", Icon: BashIcon },
    { key: "agility", label: "Agility", Icon: AgilityIcon },
    { key: "armor", label: "Armor", Icon: ArmorIcon },
];

export function complexityClassName(label: TeamComplexityLabel): string {
    return `team-complexity ${COMPLEXITY_CLASS[label]}`;
}

export function ComplexityBadge({ label, size = "md" }: { label: TeamComplexityLabel; size?: "sm" | "md" }) {
    return (
        <span className={`${complexityClassName(label)} team-complexity--${size}`}>
            <span className="team-complexity__dot" aria-hidden />
            <span className="team-complexity__label">{label}</span>
        </span>
    );
}

function RatingBar({ label, value, Icon }: { label: string; value: number; Icon: typeof SpeedIcon }) {
    const clamped = Math.max(1, Math.min(5, value));
    return (
        <div className="team-overview__rating">
            <span className="team-overview__rating-icon" aria-hidden>
                <Icon />
            </span>
            <span className="team-overview__rating-label">{label}</span>
            <div
                className="team-overview__rating-track"
                role="meter"
                aria-label={`${label}: ${clamped} of 5`}
                aria-valuemin={1}
                aria-valuemax={5}
                aria-valuenow={clamped}
            >
                {Array.from({ length: 5 }, (_, index) => (
                    <span
                        key={index}
                        className={`team-overview__rating-pip${index < clamped ? " team-overview__rating-pip--on" : ""}`}
                    />
                ))}
            </div>
            <span className="team-overview__rating-value">{clamped}</span>
        </div>
    );
}

function BashVsDashGauge({ value, label }: { value: number; label: string }) {
    const clamped = Math.max(1, Math.min(5, value));
    const percent = ((clamped - 1) / 4) * 100;
    return (
        <div className="team-overview__gauge">
            <div className="team-overview__gauge-ends">
                <span>Heavy Bash</span>
                <span>Heavy Dash</span>
            </div>
            <div
                className="team-overview__gauge-track"
                role="meter"
                aria-label={`Bash versus Dash: ${label}, ${clamped} of 5`}
                aria-valuemin={1}
                aria-valuemax={5}
                aria-valuenow={clamped}
            >
                <span className="team-overview__gauge-fill" style={{ width: `${percent}%` }} />
                <span className="team-overview__gauge-thumb" style={{ left: `${percent}%` }} />
            </div>
            <p className="team-overview__gauge-label">{label}</p>
        </div>
    );
}

export default function TeamOverview({ teamName, summary, roster }: TeamOverviewProps) {
    const abilityTags = collectRosterAbilityTags(roster);

    return (
        <section className="team-overview" aria-label={`${teamName} overview`} data-testid="team-overview">
            <p className="team-overview__summary">{summary.summary}</p>

            <div className="team-overview__body">
                <div className="team-overview__ratings-block">
                    <h3 className="team-overview__section-label">
                        <RadarMeshIcon className="team-overview__section-icon" />
                        Ratings
                    </h3>
                    <div className="team-overview__ratings">
                        {RATING_ROWS.map(({ key, label, Icon }) => (
                            <RatingBar key={key} label={label} value={summary.ratings[key]} Icon={Icon} />
                        ))}
                    </div>
                    <BashVsDashGauge value={summary.bashVsDash} label={summary.bashVsDashLabel} />
                    {abilityTags.length > 0 ? (
                        <div className="team-overview__abilities" aria-label="Special abilities">
                            {abilityTags.map((tag) => (
                                <span
                                    key={tag.key}
                                    className="team-overview__ability-chip"
                                    title={tag.description}
                                >
                                    {tag.key !== "Throw Team-mate" ? (
                                        <AbilityBadge
                                            ability={tag.key}
                                            size="sm"
                                            className="team-overview__ability-badge"
                                        />
                                    ) : null}
                                    <span>{tag.key}</span>
                                </span>
                            ))}
                        </div>
                    ) : null}
                </div>

                <div className="team-overview__advice">
                    <div className="team-overview__advice-card">
                        <h3 className="team-overview__section-label">Key strengths</h3>
                        <ul>
                            {summary.keyStrengths.map((item) => (
                                <li key={item}>{item}</li>
                            ))}
                        </ul>
                    </div>
                    <div className="team-overview__advice-card team-overview__advice-card--weak">
                        <h3 className="team-overview__section-label">Weaknesses</h3>
                        <ul>
                            {summary.weaknesses.map((item) => (
                                <li key={item}>{item}</li>
                            ))}
                        </ul>
                    </div>
                    <div className="team-overview__advice-card team-overview__advice-card--strategy">
                        <h3 className="team-overview__section-label">Recommended strategy</h3>
                        <p>{summary.recommendedStrategy}</p>
                    </div>
                </div>
            </div>
        </section>
    );
}
