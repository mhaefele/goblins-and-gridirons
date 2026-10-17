import { useEffect, useMemo, useState } from "react";
import {
    ApiError,
    createMatch,
    listComputerOpponents,
    listPitches,
    type CoachControl,
    type ComputerOpponentCatalogDto,
    type ComputerOpponentKind,
    type ComputerOpponentOptionDto,
    type CreateMatchRequest,
    type PitchCatalogEntry,
} from "../api/client";
import { opponentKindIcon, opponentProfileIcon } from "../assets/opponents/icons";
import { assetRegistry } from "../assets/registry";
import CoachControlToggle from "./CoachControlToggle";
import type { PendingMatchSetup } from "./screens";

type MatchupSplashProps = {
    setup: PendingMatchSetup;
    onStarted: (matchId: string) => void;
    onBack: () => void;
};

const DEFAULT_PITCH = "default";

function findEntryId<T extends { id: string }>(entries: T[], preferredId: string): string {
    const match = entries.find((entry) => entry.id === preferredId);
    return match?.id ?? entries[0]?.id ?? "";
}

function teamLabel(teamId: string): string {
    return teamId.charAt(0).toUpperCase() + teamId.slice(1);
}

function optionKey(option: ComputerOpponentOptionDto): string {
    return option.onnxProfile ? `${option.kind}:${option.onnxProfile}` : option.kind;
}

export default function MatchupSplash({ setup, onStarted, onBack }: MatchupSplashProps) {
    const { homeTeamId, awayTeamId, pitchDefinitionId: initialPitchId } = setup;
    const homeArt = assetRegistry.teamCard(homeTeamId);
    const awayArt = assetRegistry.teamCard(awayTeamId);
    const [pitches, setPitches] = useState<PitchCatalogEntry[]>([]);
    const [pitchDefinitionId, setPitchDefinitionId] = useState(initialPitchId ?? "");
    const [loadingPitches, setLoadingPitches] = useState(true);
    const [opponents, setOpponents] = useState<ComputerOpponentCatalogDto | null>(null);
    const [loadingOpponents, setLoadingOpponents] = useState(true);
    const [homeControlledBy, setHomeControlledBy] = useState<CoachControl>("HUMAN");
    const [awayControlledBy, setAwayControlledBy] = useState<CoachControl>("COMPUTER");
    const [computerKind, setComputerKind] = useState<ComputerOpponentKind>("LOOKAHEAD");
    const [onnxProfile, setOnnxProfile] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const needsComputer = homeControlledBy === "COMPUTER" || awayControlledBy === "COMPUTER";
    const builtInOptions = useMemo(
        () => (opponents?.opponents ?? []).filter((o) => o.kind !== "ONNX"),
        [opponents],
    );
    const onnxOptions = useMemo(
        () => (opponents?.opponents ?? []).filter((o) => o.kind === "ONNX"),
        [opponents],
    );
    const selectedDescription = useMemo(() => {
        if (computerKind === "ONNX") {
            return onnxOptions.find((o) => o.onnxProfile === onnxProfile)?.description ?? null;
        }
        return builtInOptions.find((o) => o.kind === computerKind)?.description ?? null;
    }, [builtInOptions, computerKind, onnxOptions, onnxProfile]);

    useEffect(() => {
        let cancelled = false;
        void (async () => {
            try {
                const pitchCatalog = await listPitches();
                if (cancelled) return;
                setPitches(pitchCatalog.pitches);
                const preferred = initialPitchId ?? DEFAULT_PITCH;
                setPitchDefinitionId(findEntryId(pitchCatalog.pitches, preferred));
            } catch (catalogError) {
                if (!cancelled) {
                    const message = catalogError instanceof Error ? catalogError.message : "Failed to load pitches";
                    setError(message);
                }
            } finally {
                if (!cancelled) setLoadingPitches(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [initialPitchId]);

    useEffect(() => {
        let cancelled = false;
        void (async () => {
            try {
                const catalog = await listComputerOpponents();
                if (cancelled) return;
                setOpponents(catalog);
                setComputerKind(catalog.defaultKind);
                setOnnxProfile(catalog.defaultOnnxProfile);
            } catch (catalogError) {
                if (!cancelled) {
                    const message =
                        catalogError instanceof Error ? catalogError.message : "Failed to load computer opponents";
                    setError(message);
                }
            } finally {
                if (!cancelled) setLoadingOpponents(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, []);

    async function kickOff() {
        if (submitting) return;
        setError(null);
        if (!pitchDefinitionId) {
            setError("Pick a pitch.");
            return;
        }
        if (needsComputer && computerKind === "ONNX" && !onnxProfile) {
            setError("Pick a trained model profile.");
            return;
        }
        setSubmitting(true);
        try {
            const body: CreateMatchRequest = {
                homeTeamId,
                awayTeamId,
                pitchDefinitionId,
                homeControlledBy,
                awayControlledBy,
                ...(needsComputer
                    ? {
                          computerOpponent: computerKind,
                          onnxProfile: computerKind === "ONNX" ? onnxProfile : null,
                      }
                    : {}),
            };
            const response = await createMatch(body);
            onStarted(response.matchId);
        } catch (submitError) {
            const message =
                submitError instanceof ApiError
                    ? submitError.message
                    : submitError instanceof Error
                      ? submitError.message
                      : "Failed to create match";
            setError(message);
            setSubmitting(false);
        }
    }

    useEffect(() => {
        function onKey(event: KeyboardEvent) {
            if (submitting) return;
            if (event.key === "Escape") onBack();
            if (event.key === "Enter") {
                const button = document.querySelector<HTMLButtonElement>('[data-testid="matchup-continue"]');
                button?.click();
            }
        }
        window.addEventListener("keydown", onKey);
        return () => window.removeEventListener("keydown", onKey);
    }, [submitting, onBack]);

    return (
        <main className="screen matchup-splash" role="dialog" aria-label="Matchup">
            <div className="matchup-splash__cards">
                <div className="matchup-splash__side">
                    {homeArt ? <img src={homeArt} alt={teamLabel(homeTeamId)} /> : <h2>{teamLabel(homeTeamId)}</h2>}
                    <p>Home</p>
                    <CoachControlToggle
                        side="Home"
                        value={homeControlledBy}
                        onChange={setHomeControlledBy}
                        testIdPrefix="home"
                    />
                </div>
                <p className="matchup-splash__vs">VS</p>
                <div className="matchup-splash__side">
                    {awayArt ? <img src={awayArt} alt={teamLabel(awayTeamId)} /> : <h2>{teamLabel(awayTeamId)}</h2>}
                    <p>Away</p>
                    <CoachControlToggle
                        side="Away"
                        value={awayControlledBy}
                        onChange={setAwayControlledBy}
                        testIdPrefix="away"
                    />
                </div>
            </div>

            {needsComputer ? (
                <fieldset className="matchup-splash__opponent-fieldset">
                    <legend>Computer opponent</legend>
                    {loadingOpponents ? (
                        <p>Loading opponents…</p>
                    ) : (
                        <>
                            <div className="opponent-pick-row" role="group" aria-label="Opponent type">
                                {builtInOptions.map((option) => {
                                    const selected = computerKind === option.kind;
                                    const icon = opponentKindIcon(option.kind);
                                    return (
                                        <button
                                            key={optionKey(option)}
                                            type="button"
                                            data-testid={`opponent-kind-${option.kind.toLowerCase()}`}
                                            className={`opponent-pick-card${selected ? " opponent-pick-card--selected" : ""}`}
                                            aria-pressed={selected}
                                            title={option.description}
                                            disabled={submitting}
                                            onClick={() => {
                                                setComputerKind(option.kind);
                                                setOnnxProfile(null);
                                            }}
                                        >
                                            {icon ? (
                                                <img src={icon} alt="" className="opponent-pick-card__icon" />
                                            ) : null}
                                            <span className="opponent-pick-card__title">{option.title}</span>
                                        </button>
                                    );
                                })}
                                {onnxOptions.length > 0 ? (
                                    <button
                                        type="button"
                                        data-testid="opponent-kind-onnx"
                                        className={`opponent-pick-card${computerKind === "ONNX" ? " opponent-pick-card--selected" : ""}`}
                                        aria-pressed={computerKind === "ONNX"}
                                        title="Trained model — pick a profile"
                                        disabled={submitting}
                                        onClick={() => {
                                            setComputerKind("ONNX");
                                            setOnnxProfile((prev) => prev ?? onnxOptions[0]?.onnxProfile ?? null);
                                        }}
                                    >
                                        {opponentKindIcon("ONNX") ? (
                                            <img
                                                src={opponentKindIcon("ONNX")!}
                                                alt=""
                                                className="opponent-pick-card__icon"
                                            />
                                        ) : null}
                                        <span className="opponent-pick-card__title">Trained model</span>
                                    </button>
                                ) : null}
                            </div>
                            {computerKind === "ONNX" && onnxOptions.length > 0 ? (
                                <div className="opponent-pick-row opponent-pick-row--profiles" role="group" aria-label="Model profile">
                                    {onnxOptions.map((option) => {
                                        const profileId = option.onnxProfile ?? "";
                                        const selected = onnxProfile === profileId;
                                        const icon = opponentProfileIcon(profileId);
                                        return (
                                            <button
                                                key={optionKey(option)}
                                                type="button"
                                                data-testid={`opponent-profile-${profileId}`}
                                                className={`opponent-pick-card opponent-pick-card--profile${selected ? " opponent-pick-card--selected" : ""}`}
                                                aria-pressed={selected}
                                                title={option.description}
                                                disabled={submitting}
                                                onClick={() => setOnnxProfile(profileId)}
                                            >
                                                {icon ? (
                                                    <img src={icon} alt="" className="opponent-pick-card__icon" />
                                                ) : null}
                                                <span className="opponent-pick-card__title">{option.title}</span>
                                            </button>
                                        );
                                    })}
                                </div>
                            ) : null}
                            {selectedDescription ? (
                                <p className="matchup-splash__opponent-blurb">{selectedDescription}</p>
                            ) : null}
                        </>
                    )}
                </fieldset>
            ) : null}

            <fieldset className="matchup-splash__pitch-fieldset">
                <legend>Pitch</legend>
                {loadingPitches ? (
                    <p>Loading pitches…</p>
                ) : pitches.length === 0 ? (
                    <p role="alert">No pitches available.</p>
                ) : (
                    <div className="pitch-card-row matchup-splash__pitch-row">
                        {pitches.map((pitch) => {
                            const thumb = assetRegistry.pitchThumb(pitch.id);
                            const selected = pitchDefinitionId === pitch.id;
                            return (
                                <button
                                    key={pitch.id}
                                    type="button"
                                    data-testid={`pitch-card-${pitch.id}`}
                                    className={`pitch-pick-card${selected ? " pitch-pick-card--selected" : ""}`}
                                    aria-pressed={selected}
                                    title={selected ? `${pitch.name} selected` : `Play on ${pitch.name}`}
                                    disabled={submitting}
                                    onClick={() => setPitchDefinitionId(pitch.id)}
                                >
                                    {thumb ? (
                                        <img src={thumb} alt="" className="pitch-pick-card__thumb" />
                                    ) : (
                                        <span className="pitch-pick-card__thumb pitch-pick-card__thumb--missing">{pitch.name}</span>
                                    )}
                                    <span>{pitch.name}</span>
                                </button>
                            );
                        })}
                    </div>
                )}
            </fieldset>

            {error ? (
                <p className="matchup-splash__error" role="alert">
                    {error}
                </p>
            ) : null}
            <div className="matchup-splash__actions">
                <button
                    type="button"
                    className="matchup-splash__back"
                    data-testid="matchup-back"
                    title="Back to team select (Escape)"
                    disabled={submitting}
                    onClick={onBack}
                >
                    Back
                </button>
                <button
                    type="button"
                    className="matchup-splash__kickoff"
                    data-testid="matchup-continue"
                    title="Create the match and open the pitch (Enter)"
                    disabled={
                        submitting ||
                        loadingPitches ||
                        !pitchDefinitionId ||
                        (needsComputer && loadingOpponents) ||
                        (needsComputer && computerKind === "ONNX" && !onnxProfile)
                    }
                    onClick={() => void kickOff()}
                >
                    {submitting ? "Creating…" : "Kick off"}
                </button>
            </div>
        </main>
    );
}
