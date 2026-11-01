# AI ownership map

**Where to change what.** Prefer this file over reading every markdown / module top-to-bottom.

Doc roles (do not restate each other):

| Doc | Owns |
|-----|------|
| **This file** | Concept → owner path; dual-catalog hazards; do-not-touch |
| [`README.md`](README.md) | How to run / wipe / expert chain commands |
| [`DESIGN.md`](DESIGN.md) | Locked contract decisions (why the wire/API is shaped this way) |
| [`TRAINING_ROADMAP.md`](TRAINING_ROADMAP.md) | Phase gates, curriculum policy, PPO/mix knob table |
| [`ACTION_SAMPLING.md`](ACTION_SAMPLING.md) | Action-head inventory / soft-mask / next sampling experiments |
| [`BLINDSPOTS.md`](BLINDSPOTS.md) | Model-vs-lookahead failure taxonomy + remediation pointers |
| [`BACKLOG.md`](BACKLOG.md) | Open tasks only |

Kotlin DTOs / packers are the field-level contract — **not** a parallel markdown table.

---

## Concept → owner

| Concept | Owner | Do not |
|---------|-------|--------|
| Obs packing / `schemaVersion` / cell layout | Kotlin `AiObsPacker`, `AiObsLayout`, `AiObsDto` | Re-encode eligibility or channel order in Python |
| Gym Dict slice + spaces | `gridiron_ai/obs.py`, `constants.py` | Invent new obs facts client-side |
| Feature extract (embed / pitch coords) | `gridiron_ai/features.py` | Put legality into features |
| Legal row pack / join-score action / soft-mask | Kotlin `AiLegalPacker` + `actions.py` / `legal_row_policy.py` | Hard-mask distractors; duplicate `expectedScenarioMove` in Python; factor MultiDiscrete remask |
| HTTP create / step / submit / scenarios list | Kotlin `/api/ai/...` + `gridiron_ai/client.py` | Open `/events` from the harness |
| Gym env (reset/step/reward call) | `gridiron_ai/env.py` | Own drill grading |
| Reward margin / terminal / profile shaping / scenario coach-window ± | `gridiron_ai/reward.py` | Per-move expected-move slap/bonus (soft-mask owns that) |
| Episode metrics / TB aggregates | `gridiron_ai/metrics.py`, `tb_callback.py` | — |
| **Drill board / expected moves / coach window** | Kotlin `api/ai/training/**` + `TrainingScenarioEnum`; semantics in [`backend/TRAINING_SCENARIOS.md`](../backend/TRAINING_SCENARIOS.md) | Script boards in Python |
| Scenario catalog (name / family / difficultyProxy) | Kotlin `GET /api/ai/training/scenarios` → cached `output/scenario_catalog.json` via `scenarios.py` | Hard-code name lists in Python or frontend |
| Difficulty JSON, lead, unlock levels, scenario timesteps, gate-failure counts | `gridiron_ai/scenario_curriculum.py` | — |
| Phase routing / fork-from-prior / **profile→checkpoint** (`active_targets`, `resolve_targets`, `TrainTarget` paths) / gate transitions (`advance_pipeline`, `force_promote`) | `gridiron_ai/training_phases.py` | Copy expert seed onto shaped profiles; invent `CHECKPOINT_DIR / profile` in summary/play/train; auto-wipe later phases |
| **Saved pipeline FSM** (stage, split, per-profile climbing/games) | `gridiron_ai/pipeline_state.py` → `checkpoints/pipeline.json` | Re-derive stage from zips / marker files; write it from readers (summary, status) |
| Train/eval **orchestration** (PPO, vec-env, cycle) | `scripts/train_self_play.py` | Put phase *policy* here — belong in curriculum/phases |
| Artifact paths + phase-owned output layout | `gridiron_ai/paths.py` | — |
| Destructive rollback (wipe artifact trees) | `common.sh` (`start_over.sh`) | Partial deletes from Python; auto-wipe on gate regress |
| Expert dump | Kotlin `--export-expert` | — |
| Pack / clone | `expert.py` / `clone.py` + scripts | Play HTTP during clone |
| ONNX product opponent | `export_opponent.sh` → `backend/.../resources/ai-opponent/`; Kotlin `game/opponent/onnx/` | Re-encode features/logits in Kotlin; soft-load missing artifacts |
| Shell entrypoints | `train.sh`, `eval.sh`, `export_opponent.sh`, `start_over.sh`, `common.sh` | Duplicate mix defaults in shell |

---

## Scenario definition (split ownership)

```text
TrainingScenarioEnum + AbstractTrainingScenario @Component
        → family / difficultyProxy on the bean
        → GET /api/ai/training/scenarios (catalog DTO)
        → Python refresh_catalog → output/scenario_catalog.json
        → scenario_curriculum (difficulty) + training_phases (gates)
```

**Adding a drill:** Kotlin enum + `@Component` (+ tests). Set `family` / `difficultyProxy` on the bean when needed. See [`backend/TRAINING_SCENARIOS.md`](../backend/TRAINING_SCENARIOS.md) for placement, difficulty scatter, grading, and catalog fields. Train refreshes the catalog once at startup (new names start at difficulty 0). No Python catalog edit.

**Do not** mirror closed backend enum/catalog membership in Python or the frontend without asking — prefer an API (+ cache). See `.cursor/rules/no-mirrored-enums.mdc`.

---

## Why `train_self_play.py` is large

Curriculum *concepts* landed in `scenario_curriculum.py` / `training_phases.py`, but the **cycle driver** stayed one script and absorbed each new concern: vec-env / MaskablePPO create·resume, VecNormalize, TSV logging, scenario eval→train→eval sandwich, full-game segments, profile rotation. ~1.3k LOC of orchestration, not misplaced board scripts.

Follow-up split: [AI2.14](BACKLOG.md#ai214--split-train_self_play).

---

## Python module crumbs (one-liners)

| Module | Owns |
|--------|------|
| `constants.py` | Schema + head/obs sizes (must match Kotlin) |
| `client.py` | REST only |
| `obs.py` | Packed float → gym Dict |
| `actions.py` | Pad / match / resolve legal-row index |
| `legal_row_policy.py` | Join-score legal rows (+ soft-mask bias on preferred rows) |
| `features.py` | Embeddings → MLP vector; `encode_legal_rows` reuses board embs (does not re-pack) |
| `env.py` | Gymnasium API over client |
| `reward.py` | Step reward + profile presets |
| `scenarios.py` | Fetch/cache catalog (family + proxy); no hard-coded name lists |
| `scenario_curriculum.py` | Per-stem difficulty + cycle filters |
| `training_phases.py` | scenario_a / b / profiles routing; `active_targets` / `resolve_targets` (post-split train/eval `all` via `reward.profiles_for_all` — baseline + general + spread; summary `all` includes td/defense) |
| `pipeline_state.py` | Load / save / rewind `pipeline.json` (no gate checks) |
| `scenario_training.py` | Pointer: grading is Kotlin |
| `metrics.py` / `tb_callback.py` | Episode aggregates / TensorBoard |
| `paths.py` | `output/` roots |
| `expert.py` / `clone.py` | Dump → npz → seed zip |
| `eval_debug.py` | Throwaway scenario-eval logging |
| `scripts/train_self_play.py` | Train/eval loop entrypoint |
