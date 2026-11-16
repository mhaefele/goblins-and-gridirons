"""Filesystem paths for generated AI training artifacts.

Owner: ``output/`` roots only (checkpoints, runs, evals, expert). Curriculum JSON
lives next to each model stem under ``checkpoints/{profile}/``. Destructive rollbacks:
``./start_over.sh`` / ``common.sh`` (one artifact owner per training phase — see wipe
helpers there). See ``ai/OWNERSHIP.md``.
"""

from pathlib import Path

# train/paths.py → gridiron_ai/ → ai/
AI_ROOT = Path(__file__).resolve().parents[2]
# Optional local-only ``old.module=new.module`` lines for SB3 zip unpickle (gitignored).
PICKLE_ALIASES_PATH = AI_ROOT / "local.pickle_aliases.properties"
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
# Lifetime chosen-move kind totals per checkpoint key (under ``runs/{key}/``; wiped with that runs tree).
KIND_TOTALS_JSON_NAME = "kind_totals.json"
KIND_TOTALS_TXT_NAME = "kind_totals.txt"
# Append-only scenario draw log (train + eval); ``eval_debug.append_scenario_draw_log``.
# Wiped with scenario shared artifacts (``common.sh`` ``wipe_scenario_training_shared``).
SCENARIO_DRAWS_LOG_PATH = RUNS_DIR / "scenario_draws.log"
# Ephemeral TB stem for ``--phase … --eval-only``; wiped with scenario shared artifacts.
EVAL_PHASE_RUNS_DIR = RUNS_DIR / "eval_phase"
# Scenario phases (scenario_a / scenario_b) and post-split baseline write eval JSON here.
SCENARIO_TRAINING_EVAL_PROFILE = "baseline"
