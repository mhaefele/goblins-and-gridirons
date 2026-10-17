import { useEffect, useRef, useState, type FormEvent, type RefObject } from "react";
import {
    listPitches,
    listTeams,
    type TeamCatalogEntry,
    type TeamComplexityLabel,
} from "../api/client";
import { assetRegistry } from "../assets/registry";
import TeamOverview, { ComplexityBadge } from "./TeamOverview";
import TeamRosterTable from "./TeamRosterTable";
import type { PendingMatchSetup } from "./screens";

type CreateMatchProps = {
    initialStep?: "home" | "away" | undefined;
    initialHomeTeamId?: string | undefined;
    initialAwayTeamId?: string | undefined;
    onHomeAwayReady: (setup: PendingMatchSetup) => void;
};

const DEFAULT_HOME_TEAM = "dwarves";
const DEFAULT_AWAY_TEAM = "orcs";
/** Fixed team card width in px (matches .team-card-row--paged .team-pick-card). */
const TEAM_CARD_WIDTH_PX = 136;
const TEAM_CARD_GAP_PX = 8;
const PAGER_NAV_PX = 72;
const TEAM_CARD_MAX_PER_PAGE = 7;

const COMPLEXITY_ORDER: Record<TeamComplexityLabel, number> = {
    Beginner: 0,
    Intermediate: 1,
    Advanced: 2,
};

type DetailTab = "overview" | "roster";


function findEntryId<T extends { id: string }>(entries: T[], preferredId: string): string {
    const match = entries.find((entry) => entry.id === preferredId);
    return match?.id ?? entries[0]?.id ?? "";
}

/** Prefer art-backed catalog entries; optionally avoid a team already picked for the other side. */
function pickRandomTeamId(teams: TeamCatalogEntry[], excludeId?: string): string {
    const artBacked = teams.filter((team) => assetRegistry.teamCard(team.id) != null);
    let pool = artBacked.length > 0 ? artBacked : teams;
    if (excludeId != null && pool.length > 1) {
        const without = pool.filter((team) => team.id !== excludeId);
        if (without.length > 0) pool = without;
    }
    if (pool.length === 0) return "";
    return pool[Math.floor(Math.random() * pool.length)]!.id;
}

/** Beginner → Intermediate → Advanced, then alphabetical by name within each band. */
function sortTeamsForPicker(teams: TeamCatalogEntry[]): TeamCatalogEntry[] {
    return [...teams].sort((a, b) => {
        const aRank = COMPLEXITY_ORDER[a.summary.complexityLabel] ?? 99;
        const bRank = COMPLEXITY_ORDER[b.summary.complexityLabel] ?? 99;
        if (aRank !== bRank) return aRank - bRank;
        return a.name.localeCompare(b.name);
    });
}

function useTeamsPerPage(containerRef: RefObject<HTMLElement | null>): number {
    const [count, setCount] = useState(4);

    useEffect(() => {
        const element = containerRef.current;
        if (element == null) return;

        function measure() {
            const el = containerRef.current;
            if (el == null) return;
            const width = el.clientWidth;
            const available = Math.max(0, width - PAGER_NAV_PX * 2);
            const perCard = TEAM_CARD_WIDTH_PX + TEAM_CARD_GAP_PX;
            setCount(Math.max(1, Math.min(TEAM_CARD_MAX_PER_PAGE, Math.floor(available / perCard))));
        }

        measure();
        const observer = new ResizeObserver(measure);
        observer.observe(element);
        return () => observer.disconnect();
    }, [containerRef]);

    return count;
}

export default function CreateMatch({
    initialStep = "home",
    initialHomeTeamId,
    initialAwayTeamId,
    onHomeAwayReady,
}: CreateMatchProps) {
    const [teams, setTeams] = useState<TeamCatalogEntry[]>([]);
    const [step, setStep] = useState<"home" | "away">(initialStep);
    const [homeTeamId, setHomeTeamId] = useState(initialHomeTeamId ?? "");
    const [awayTeamId, setAwayTeamId] = useState(initialAwayTeamId ?? "");
    const [loadingCatalog, setLoadingCatalog] = useState(true);
    const [quickStarting, setQuickStarting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        void (async () => {
            try {
                const teamCatalog = await listTeams();
                if (cancelled) return;
                const sorted = sortTeamsForPicker(teamCatalog.teams);
                setTeams(sorted);
                setHomeTeamId(initialHomeTeamId ?? findEntryId(teamCatalog.teams, DEFAULT_HOME_TEAM));
                setAwayTeamId(initialAwayTeamId ?? findEntryId(teamCatalog.teams, DEFAULT_AWAY_TEAM));
            } catch (catalogError) {
                if (!cancelled) {
                    const message = catalogError instanceof Error ? catalogError.message : "Failed to load catalog";
                    setError(message);
                }
            } finally {
                if (!cancelled) setLoadingCatalog(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [initialHomeTeamId, initialAwayTeamId]);

    function handleSubmit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setError(null);
        if (step === "home") {
            if (!homeTeamId) {
                setError("Pick a home team.");
                return;
            }
            setStep("away");
            return;
        }
        if (!awayTeamId) {
            setError("Pick an away team.");
            return;
        }
        onHomeAwayReady({ homeTeamId, awayTeamId });
    }

    function handleBack() {
        setError(null);
        setStep("home");
    }

    async function handleQuickStart() {
        if (quickStarting || teams.length === 0) return;
        setError(null);
        setQuickStarting(true);
        try {
            const homeId = pickRandomTeamId(teams);
            const awayId = pickRandomTeamId(teams, homeId);
            if (!homeId || !awayId) {
                setError("Could not pick teams.");
                return;
            }
            const pitchCatalog = await listPitches();
            const pitches = pitchCatalog.pitches;
            if (pitches.length === 0) {
                setError("No pitches available.");
                return;
            }
            const pitch = pitches[Math.floor(Math.random() * pitches.length)]!;
            onHomeAwayReady({
                homeTeamId: homeId,
                awayTeamId: awayId,
                pitchDefinitionId: pitch.id,
            });
        } catch (quickStartError) {
            const message =
                quickStartError instanceof Error ? quickStartError.message : "Quick start failed";
            setError(message);
        } finally {
            setQuickStarting(false);
        }
    }

    if (loadingCatalog) {
        return (
            <main className="screen create-match">
                <h1>Create match</h1>
                <p>Loading teams…</p>
            </main>
        );
    }

    if (teams.length === 0) {
        return (
            <main className="screen create-match">
                <h1>Create match</h1>
                <p role="alert">Catalog is empty — cannot create a match.</p>
            </main>
        );
    }

    const isHome = step === "home";
    const selectedTeamId = isHome ? homeTeamId : awayTeamId;
    const selectedTeam = teams.find((team) => team.id === selectedTeamId) ?? null;
    const onSelectTeam = isHome ? setHomeTeamId : setAwayTeamId;

    return (
        <main className="screen create-match create-match--cards">
            <div className="create-match__header">
                <h1>Create match</h1>
                {isHome ? (
                    <button
                        type="button"
                        className="create-match__quick-start"
                        data-testid="create-match-quick-start"
                        title="Random home, away, and pitch — jump to matchup confirm"
                        disabled={quickStarting}
                        onClick={() => void handleQuickStart()}
                    >
                        {quickStarting ? "Picking…" : "Quick start"}
                    </button>
                ) : null}
            </div>
            <form className="create-match__form" onSubmit={(event) => void handleSubmit(event)}>
                <fieldset className="create-match__fieldset">
                    <legend>{isHome ? "Select home team" : "Select away team"}</legend>
                    <LineupStrip team={selectedTeam} testIdPrefix={isHome ? "home" : "away"} />
                    <div className="create-match__team-tools">
                        <button
                            type="button"
                            className="create-match__random"
                            data-testid={`create-match-random-${isHome ? "home" : "away"}`}
                            title="Pick a random catalog team (prefers art-backed entries)"
                            onClick={() => {
                                const pick = pickRandomTeamId(teams);
                                if (pick) onSelectTeam(pick);
                            }}
                        >
                            Random
                        </button>
                    </div>
                    <TeamCardPager
                        teams={teams}
                        selectedId={selectedTeamId}
                        testIdPrefix={isHome ? "home" : "away"}
                        onSelect={onSelectTeam}
                    />
                </fieldset>

                {error ? (
                    <p className="create-match__error" role="alert">
                        {error}
                    </p>
                ) : null}
                <div className="create-match__actions">
                    {!isHome ? (
                        <button
                            type="button"
                            className="create-match__back"
                            data-testid="create-match-back"
                            title="Back to home team"
                            onClick={handleBack}
                        >
                            Back
                        </button>
                    ) : null}
                    <button
                        type="submit"
                        data-testid="create-match-submit"
                        disabled={quickStarting}
                        title={
                            isHome
                                ? `Continue — home: ${homeTeamId || "?"}`
                                : `Continue — ${homeTeamId || "home"} vs ${awayTeamId || "away"}`
                        }
                    >
                        Continue
                    </button>
                </div>
            </form>
        </main>
    );
}

type TeamCardPagerProps = {
    teams: TeamCatalogEntry[];
    selectedId: string;
    testIdPrefix: string;
    onSelect: (teamId: string) => void;
};

function TeamCardPager({ teams, selectedId, testIdPrefix, onSelect }: TeamCardPagerProps) {
    const pagerRef = useRef<HTMLDivElement>(null);
    const teamsPerPage = useTeamsPerPage(pagerRef);
    const pageCount = Math.max(1, Math.ceil(teams.length / teamsPerPage));
    const [page, setPage] = useState(0);

    useEffect(() => {
        const index = teams.findIndex((team) => team.id === selectedId);
        if (index < 0) return;
        const target = Math.floor(index / teamsPerPage);
        setPage((current) => {
            const start = current * teamsPerPage;
            if (index >= start && index < start + teamsPerPage) return current;
            return target;
        });
    }, [selectedId, teams, teamsPerPage]);

    const safePage = Math.min(page, pageCount - 1);
    const visible = teams.slice(safePage * teamsPerPage, safePage * teamsPerPage + teamsPerPage);

    return (
        <div className="team-card-pager" ref={pagerRef}>
            <button
                type="button"
                className="team-card-pager__nav team-card-pager__nav--prev"
                data-testid={`team-pager-${testIdPrefix}-prev`}
                aria-label="Previous teams"
                disabled={safePage <= 0}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
                ◀
            </button>
            <div
                className="team-card-row team-card-row--paged"
                style={{ gridTemplateColumns: `repeat(${teamsPerPage}, minmax(0, 1fr))` }}
            >
                {visible.map((team) => (
                    <TeamPickCard
                        key={`${testIdPrefix}-${team.id}`}
                        team={team}
                        selected={selectedId === team.id}
                        testId={`team-card-${testIdPrefix}-${team.id}`}
                        onSelect={() => onSelect(team.id)}
                    />
                ))}
            </div>
            <button
                type="button"
                className="team-card-pager__nav team-card-pager__nav--next"
                data-testid={`team-pager-${testIdPrefix}-next`}
                aria-label="Next teams"
                disabled={safePage >= pageCount - 1}
                onClick={() => setPage((current) => Math.min(pageCount - 1, current + 1))}
            >
                ▶
            </button>
        </div>
    );
}

type TeamPickCardProps = {
    team: TeamCatalogEntry;
    selected: boolean;
    testId: string;
    onSelect: () => void;
};

function TeamPickCard({ team, selected, testId, onSelect }: TeamPickCardProps) {
    const art = assetRegistry.teamCard(team.id);
    const crest = assetRegistry.teamCrest(team.id);
    const { complexityLabel, bashVsDashLabel } = team.summary;
    return (
        <button
            type="button"
            data-testid={testId}
            className={`team-pick-card team-pick-card--${complexityLabel.toLowerCase()}${selected ? " team-pick-card--selected" : ""}`}
            aria-pressed={selected}
            aria-label={selected ? `${team.name} selected` : `Select ${team.name}`}
            onClick={onSelect}
        >
            <ComplexityBadge label={complexityLabel} size="sm" />
            {art ? (
                <img src={art} alt="" className="team-pick-card__art" draggable={false} />
            ) : (
                <span className="team-pick-card__fallback">{team.name}</span>
            )}
            <span className="team-pick-card__hover-tip" aria-hidden>
                <span className="team-pick-card__hover-tip-line">{bashVsDashLabel}</span>
                <span className="team-pick-card__hover-tip-line">{complexityLabel}</span>
            </span>
            <span className="team-pick-card__meta">
                {crest ? <img src={crest} alt="" className="team-pick-card__crest" draggable={false} /> : null}
                <span className="team-pick-card__name">{team.name}</span>
            </span>
        </button>
    );
}

type LineupStripProps = {
    team: TeamCatalogEntry | null;
    testIdPrefix: string;
};

function LineupStrip({ team, testIdPrefix }: LineupStripProps) {
    const [tab, setTab] = useState<DetailTab>("overview");

    useEffect(() => {
        setTab("overview");
    }, [team?.id]);

    if (team == null || team.roster.length === 0) return null;

    const { complexityLabel, bashVsDashLabel } = team.summary;

    return (
        <div className="lineup-strip" data-testid={`roster-preview-${testIdPrefix}`}>
            <div className="lineup-strip__detail-box" aria-live="polite">
                <div className="lineup-strip__detail-header">
                    <div className="lineup-strip__header-meta">
                        <span className="lineup-strip__header-name">{team.name}</span>
                        <ComplexityBadge label={complexityLabel} size="sm" />
                        <span className="lineup-strip__header-archetype">{bashVsDashLabel}</span>
                    </div>
                    <div className="lineup-strip__tabs" role="tablist" aria-label="Team detail view">
                        <button
                            type="button"
                            role="tab"
                            id={`${testIdPrefix}-tab-overview`}
                            className={`lineup-strip__tab${tab === "overview" ? " lineup-strip__tab--active" : ""}`}
                            aria-selected={tab === "overview"}
                            data-testid={`roster-tab-overview-${testIdPrefix}`}
                            onClick={() => setTab("overview")}
                        >
                            Team Overview
                        </button>
                        <button
                            type="button"
                            role="tab"
                            id={`${testIdPrefix}-tab-roster`}
                            className={`lineup-strip__tab${tab === "roster" ? " lineup-strip__tab--active" : ""}`}
                            aria-selected={tab === "roster"}
                            data-testid={`roster-tab-roster-${testIdPrefix}`}
                            onClick={() => setTab("roster")}
                        >
                            Full Roster
                        </button>
                    </div>
                </div>
                <div className="lineup-strip__detail-body">
                    {tab === "overview" ? (
                        <TeamOverview teamName={team.name} summary={team.summary} roster={team.roster} />
                    ) : (
                        <TeamRosterTable teamId={team.id} teamName={team.name} roster={team.roster} />
                    )}
                </div>
            </div>
        </div>
    );
}
