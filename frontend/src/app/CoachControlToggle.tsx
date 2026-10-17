import type { CoachControl } from "../api/client";

type CoachControlToggleProps = {
    side: "Home" | "Away";
    value: CoachControl;
    onChange: (value: CoachControl) => void;
    testIdPrefix: string;
};

export default function CoachControlToggle({ side, value, onChange, testIdPrefix }: CoachControlToggleProps) {
    return (
        <div className="coach-control" role="group" aria-label={`${side} coach`}>
            <span className="coach-control__label">Coach</span>
            <div className="coach-control__toggle">
                <button
                    type="button"
                    data-testid={`${testIdPrefix}-coach-human`}
                    className={`coach-control__option${value === "HUMAN" ? " coach-control__option--selected" : ""}`}
                    aria-pressed={value === "HUMAN"}
                    title={`${side} — human player`}
                    onClick={() => onChange("HUMAN")}
                >
                    <HumanIcon />
                    <span>Human</span>
                </button>
                <button
                    type="button"
                    data-testid={`${testIdPrefix}-coach-computer`}
                    className={`coach-control__option${value === "COMPUTER" ? " coach-control__option--selected" : ""}`}
                    aria-pressed={value === "COMPUTER"}
                    title={`${side} — computer opponent`}
                    onClick={() => onChange("COMPUTER")}
                >
                    <CpuIcon />
                    <span>CPU</span>
                </button>
            </div>
        </div>
    );
}

function HumanIcon() {
    return (
        <svg className="coach-control__icon" viewBox="0 0 20 20" aria-hidden="true">
            <circle cx="10" cy="6.5" r="3.2" fill="currentColor" />
            <path
                d="M4.2 16.5c1-3.3 3-4.8 5.8-4.8s4.8 1.5 5.8 4.8"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
            />
        </svg>
    );
}

function CpuIcon() {
    return (
        <svg className="coach-control__icon" viewBox="0 0 20 20" aria-hidden="true">
            <rect x="5" y="5" width="10" height="10" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.8" />
            <rect x="8" y="8" width="4" height="4" fill="currentColor" />
            <path
                d="M7.5 2.8v2M12.5 2.8v2M7.5 15.2v2M12.5 15.2v2M2.8 7.5h2M2.8 12.5h2M15.2 7.5h2M15.2 12.5h2"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
            />
        </svg>
    );
}
