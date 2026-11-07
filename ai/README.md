# Goblins & Gridirons — AI training

Python harness (Stable-Baselines3) over the Kotlin `/api/ai/...` trainer API.

| Doc | Role |
|-----|------|
| **[OWNERSHIP.md](OWNERSHIP.md)** | Concept → owner file (start here when editing) |
| [BACKLOG.md](BACKLOG.md) | Open tasks |
| [DESIGN.md](DESIGN.md) | Locked contract decisions |
| [TRAINING_ROADMAP.md](TRAINING_ROADMAP.md) | Phases, gates, mix knobs |
| [ACTION_SAMPLING.md](ACTION_SAMPLING.md) | Action heads / soft-mask inventory |

**Engine:** [`../backend/`](../backend/) · **Eval bots:** `backend/.../game/opponent/` ([AI1](../archive/BACKLOG-done.md#ai1--basic-computer-opponent)).

## Setup (once)

```bash
cd ai
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Backend must be up for train/eval (`./dev.sh` from repo root — Spring `:18080`, Vite `:15173`). GUI optional for train; required for play-vs-human.

## Run

```bash
./train.sh                 # ensure seed if missing; train forever (scenario_a → scenario_b → profiles)
./train.sh --profile td    # pin one specialist (still catches up scenario phases first)
./train.sh --scenario MARKED_RUN --cycles 1
./eval.sh                  # eval train rotation once (baseline + general + spread + defense post-split)
./eval.sh --profile td
./train.sh --help          # full flag list
./check.sh                 # compileall + import/CLI entry smokes (no backend)
```

`train.sh` fills gaps on **dump → pack → seed** (presence only). Scenario/profile zips are forked in Python from the prior stage (never copy the expert seed onto shaped profiles). Gates only route training — later trees are never auto-deleted (use `start_over.sh` to roll back). See [TRAINING_ROADMAP.md](TRAINING_ROADMAP.md).

Advanced (other scripts):

```bash
.venv/bin/python -m scripts.play_vs_human --profile general
```

### Start over from a phase

`start_over.sh` wipes from a phase through checkpoint (full artifact trees per phase — see `common.sh` header), then runs `train.sh`. No args → help. Prompts `y/N` before wiping. `--profile` scopes the **checkpoint** wipe and train hand-off; earlier phases always clear shared dump/pack/seed (and scenario dirs when wiped). Each wipe parks the previous trees in `output/.start_over_last` (one slot); restore with `--undo` (replaces whatever train wrote after the wipe).

```bash
./start_over.sh --phase dump                      # wipe dump…checkpoint, then train all
./start_over.sh --phase seed --profile general    # wipe seed + scenario phases + general checkpoint
./start_over.sh --phase scenario_a                # wipe scenario_a onward
./start_over.sh --phase scenario_b                # wipe scenario_b + profiles
./start_over.sh --phase checkpoint --profile td   # wipe td only, then train td
./start_over.sh --phase pack --cycles 3           # wipe pack onward, then train with same flags as train.sh
./start_over.sh --undo                            # restore the last parked wipe
DUMP_GAMES=4 ./start_over.sh --phase dump --profile general --cycles 1
```

Phases: `dump` → `pack` → `seed` → `scenario_a` → `scenario_b` → `checkpoint`. Full dump size: `DUMP_GAMES` in `common.sh`.

### Expert dump → pack → seed (manual)

Same chain `train.sh` runs automatically. Dump = Kotlin CLI; pack/clone = Python. None use the HTTP trainer loop.

**1. Dump** from `backend/` (`--games` = total matches; unpinned teams cycle 23×22 = 506 directed pairs — prefer a multiple of 506). Within the dump, games also cycle training scenarios × difficulty then full games. **Overwrites** `--out`.

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--export-expert --games=4 --threads=4 --out=../ai/output/expert/lookahead.jsonl.gz"
mvn spring-boot:run -Dspring-boot.run.arguments="--export-expert --games=506 --out=../ai/output/expert/lookahead.jsonl.gz"
```

**2. Pack** from `ai/` (ragged legals in npz; **overwrites** `--out`; keeps jsonl.gz; writes
`lookahead_kind_stats.txt` with chosen-move kind counts/% — also via `scripts.summarize_expert`):

```bash
.venv/bin/python -m scripts.import_expert --in output/expert/lookahead.jsonl.gz --out output/expert/lookahead.npz
```

**3. Clone** into the shared seed (or profile zips). `train.sh` / `ensure_train_prereqs` also run a seed health eval after a fresh clone (`./eval.sh --phase seed`):

```bash
.venv/bin/python -m scripts.clone_expert --in output/expert/lookahead.npz --to output/expert/maskable_ppo_seed.zip
.venv/bin/python -m scripts.clone_expert --in output/expert/lookahead.npz --profile all
./eval.sh --phase seed
```

Cycle / PPO / curriculum defaults: [TRAINING_ROADMAP.md](TRAINING_ROADMAP.md#current-defaults-implementation-pointers) (edit Python, not shell).

## TensorBoard

```bash
.venv/bin/tensorboard --logdir output/runs
```

Logs per profile under `output/runs/{profile}/`. Metric keys: see `metrics.py` / `tb_callback.py`.  
Stuck scenario learning / `--verbose` evidence: [`PPO_LEARNING_DEBUG.md`](PPO_LEARNING_DEBUG.md).

## Checkpoints

`output/checkpoints/{scenario_a|scenario_b|profile}/maskable_ppo_selfplay_final.zip`. Schema bumps invalidate older zips — re-export expert + `--reset-long-running` as needed. Layout of `output/`: [OWNERSHIP.md](OWNERSHIP.md) / `paths.py`.
