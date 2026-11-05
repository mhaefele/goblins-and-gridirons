"""JSON load/save for AI training artifacts — one module per on-disk file shape.

Curriculum *policy* (budgets, unlocks, multipliers) stays in ``scenario_curriculum``;
pipeline FSM types stay in ``pipeline_state``. See ``ai/OWNERSHIP.md``.

Each module exposes a named store alias / ``TypedDict`` for its on-disk schema
(``ScenarioDifficultyLevels``, ``LastEvalStore``, ``KindTotalsDict``, …).
``pipeline`` is dataclass-typed via ``PipelineState``.

| Module | On disk | Contents |
|---|---|---|
| ``scenario_difficulty`` | ``{stem}.scenario_difficulty.json`` | Curriculum key → difficulty 0–5 |
| ``scenario_gate_failures`` | ``{stem}.scenario_gate_failures.json`` | Key → level → imperfect after-eval count |
| ``scenario_drill_stats`` | ``{stem}.scenario_drill_stats.json`` | Key → ``cycles`` / ``d5_demotes`` (per phase) |
| ``scenario_last_eval`` | ``{stem}.scenario_last_eval.json`` | Key → level → ``{current, prior}`` slim eval metrics |
| ``pipeline`` | ``checkpoints/pipeline.json`` | Stage + split + per-profile climbing/games |
| ``kind_totals`` | ``runs/{key}/kind_totals.json`` | Lifetime chosen-move kind counts (+ ``.txt``) |
| ``scenario_catalog`` | ``output/scenario_catalog.json`` | Cached GET /api/ai/training/scenarios |
"""
