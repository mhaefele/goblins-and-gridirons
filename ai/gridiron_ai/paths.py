"""Filesystem paths for generated AI training artifacts.

Owner: ``output/`` roots only (checkpoints, runs, evals, expert). Curriculum JSON
lives next to each model stem under ``checkpoints/{profile}/``. Destructive rollbacks:
``./start_over.sh`` / ``common.sh`` (one artifact owner per training phase — see wipe
helpers there). See ``ai/OWNERSHIP.md``.
"""

from pathlib import Path

AI_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = AI_ROOT / "output"
CHECKPOINT_DIR = OUTPUT_DIR / "checkpoints"
RUNS_DIR = OUTPUT_DIR / "runs"
EVAL_DIR = OUTPUT_DIR / "evals"
EXPERT_DIR = OUTPUT_DIR / "expert"
# Behavioral-clone seed zip; Python forks scenario_a from this (not shaped profiles).
EXPERT_SEED_ZIP = EXPERT_DIR / "maskable_ppo_seed.zip"
# Cached GET /api/ai/training/scenarios (refreshed at train startup from scenario_a onward).
SCENARIO_CATALOG_PATH = OUTPUT_DIR / "scenario_catalog.json"
# Append-only cycle metrics; train driver (``train_self_play``). Wiped from scenario_a rollback.
CYCLE_LOG_PATH = RUNS_DIR / "train_eval_cycles.tsv"
# Append-only scenario draw log (train + eval); ``eval_debug.append_scenario_draw_log``.
SCENARIO_DRAWS_LOG_PATH = RUNS_DIR / "scenario_draws.log"
# Scenario phases (scenario_a / scenario_b) and post-split baseline write eval JSON here.
SCENARIO_TRAINING_EVAL_PROFILE = "baseline"
