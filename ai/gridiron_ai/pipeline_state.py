"""Persisted training pipeline FSM — ``checkpoints/pipeline.json``.

Owner: load / save / rewind of the one saved answer to "what should training run?":

  pipeline stage   scenario_a → scenario_b → profiles   (one-way; demotions never rewind)
  per profile      forked → climbing → games            (each stem its own small FSM)

Routing (``training_phases.resolve_targets``), cycle order, eval selection and summaries
consult this state first — not checkpoint directories. Does **not** check curriculum gates:
``training_phases.advance_pipeline`` / ``force_promote`` / ``ensure_checkpoint_from_prior``
decide transitions and call ``save_pipeline``. Readers only ``load_pipeline``.

Rewinds mirror destructive wipes (``common.sh`` / ``--reset-long-running``)::

    python -m gridiron_ai.pipeline_state rewind scenario_b
    python -m gridiron_ai.pipeline_state rewind profiles --profile td
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from typing import Literal

StageName = Literal["scenario_a", "scenario_b", "profiles"]
ProfileStage = Literal["climbing", "games"]

STAGES: tuple[StageName, ...] = ("scenario_a", "scenario_b", "profiles")


@dataclass(frozen=True)
class ProfileSplit:
    """When the first profile forked from frozen scenario_b."""

    at: float
    scenario_b_steps: int | None = None


@dataclass(frozen=True)
class ProfileState:
    """One forked profile stem: re-climbs scenarios first, then plays full games first."""

    stage: ProfileStage = "climbing"
    # games reached via ``--force-promote`` rather than a live gating-d5 pass.
    forced: bool = False


@dataclass(frozen=True)
class PipelineState:
    stage: StageName = "scenario_a"
    split: ProfileSplit | None = None
    # Forked profile checkpoint keys (baseline + shaped); absent = not forked yet.
    profiles: dict[str, ProfileState] = field(default_factory=dict)

    def passed(self, stage: StageName) -> bool:
        """True once training has moved beyond ``stage``."""
        return STAGES.index(self.stage) > STAGES.index(stage)

    def plays_games(self, profile: str) -> bool:
        return profile in self.profiles and self.profiles[profile].stage == "games"


# After types: persistence.pipeline imports these classes (no cycle).
from gridiron_ai.persistence.pipeline import (  # noqa: E402, F401
    load_pipeline,
    pipeline_path,
    save_pipeline,
)


def rewind_pipeline(stage: StageName, *, profile: str = "all") -> PipelineState:
    """Forget progress from ``stage`` onward after its checkpoint trees were wiped.

    ``profiles`` + one ``profile`` forgets only that stem (next train re-forks it);
    ``all`` also clears the split so the next fork re-records it.
    """
    state = load_pipeline()
    if stage == "scenario_a":
        rewound = PipelineState()
    elif stage == "scenario_b":
        rewound = PipelineState(stage="scenario_b") if state.passed("scenario_a") else state
    elif profile == "all":
        rewound = PipelineState(stage=state.stage)
    else:
        profiles = {key: p for key, p in state.profiles.items() if key != profile}
        rewound = PipelineState(stage=state.stage, split=state.split, profiles=profiles)
    if rewound != state:
        save_pipeline(rewound)
    return rewound


def main() -> None:
    parser = argparse.ArgumentParser(description="Rewind the saved training pipeline position.")
    sub = parser.add_subparsers(dest="command", required=True)
    rewind = sub.add_parser("rewind", help="forget saved progress from a stage onward")
    rewind.add_argument("stage", choices=STAGES)
    rewind.add_argument("--profile", default="all", help="profiles stage only: one stem or all")
    args = parser.parse_args()
    state = rewind_pipeline(args.stage, profile=args.profile)
    print(f"  pipeline → {pipeline_path()}: stage={state.stage} profiles={sorted(state.profiles)}")


if __name__ == "__main__":
    main()
