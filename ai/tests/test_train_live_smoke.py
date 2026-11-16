"""Live harness smoke — Spring Boot ``BaseApiTest`` analogue for the Python train path.

Requires ``./dev.sh`` on ``:18080`` and ``output/expert/maskable_ppo_seed.zip``.
Skips when either is missing so offline unit runs stay green.

Exercises the real product loop once: catalog refresh → Subproc worker → load policy →
one env step → one PPO ``learn`` rollout → atomic save. Not a forever train; not mocked HTTP.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
from sb3_contrib import MaskablePPO

from gridiron_ai.train.api.client import GridironClient
from gridiron_ai.train.api.scenarios import refresh_catalog
from gridiron_ai.train.checkpoints import atomic_model_save
from gridiron_ai.train.dev_stack import DEFAULT_BASE_URL, url_reachable
from gridiron_ai.train.paths import EXPERT_SEED_ZIP
from gridiron_ai.model.reward import RewardConfig
from scripts.train_self_play import (
    DEFAULT_MAX_EPISODE_STEPS,
    _set_ent_coef,
    _set_learning_rate,
    _set_ppo_stability,
    _set_vf_coef,
    _DEFAULT_LEARNING_RATE,
    make_vec_env,
)


def _require_live_backend() -> str:
    base = DEFAULT_BASE_URL.rstrip("/")
    if not url_reachable(f"{base}/health"):
        pytest.skip(f"Spring Boot not at {base}/health — start ./dev.sh for live harness smoke")
    return base


def _require_seed_zip() -> Path:
    if not EXPERT_SEED_ZIP.is_file():
        pytest.skip(f"missing seed zip {EXPERT_SEED_ZIP} — run ./train.sh dump/pack/seed first")
    return EXPERT_SEED_ZIP


def test_live_train_harness_boots_steps_and_learns_once(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """One thin slice of ``train_self_play`` against the live API (n_envs=1)."""
    base_url = _require_live_backend()
    seed_zip = _require_seed_zip()

    # Do not clobber the developer's real catalog cache during the smoke.
    catalog_path = tmp_path / "scenario_catalog.json"
    monkeypatch.setattr("gridiron_ai.train.persistence.scenario_catalog.SCENARIO_CATALOG_PATH", catalog_path)
    monkeypatch.setattr("gridiron_ai.train.api.scenarios.SCENARIO_CATALOG_PATH", catalog_path)

    client = GridironClient(base_url)
    try:
        catalog = refresh_catalog(client)
    finally:
        client.close()
    boards = catalog.board_names()
    assert boards, "live catalog returned no board drills"
    scenario = boards[0]

    env = make_vec_env(
        base_url,
        DEFAULT_MAX_EPISODE_STEPS,
        n_envs=1,
        continue_on_submit_error=True,
        reward_config=RewardConfig(),
        scenario=scenario,
        scenario_difficulty=0,
        episode_mode="TRAIN",
        soft_mask_bias=True,
        verbose=False,
    )
    model: MaskablePPO | None = None
    try:
        model = MaskablePPO.load(str(seed_zip), env=env, device="cpu")
        _set_learning_rate(model, _DEFAULT_LEARNING_RATE)
        _set_ent_coef(model)
        _set_vf_coef(model)
        _set_ppo_stability(model)

        obs = env.reset()
        masks = np.asarray(env.env_method("action_masks")[0], dtype=bool)
        assert masks.any(), "expected at least one legal row after scenario reset"
        action, _states = model.predict(obs, deterministic=True, action_masks=masks[None, :])
        _obs2, _rewards, _dones, infos = env.step(action)
        assert isinstance(infos, (list, tuple)) and len(infos) == 1

        # One on-policy rollout → one ``train()`` (same choke point as a train segment).
        rollout = int(model.n_steps) * int(env.num_envs)
        model.learn(total_timesteps=rollout, progress_bar=False, reset_num_timesteps=True)

        save_stem = tmp_path / "live_smoke_final"
        atomic_model_save(model, save_stem)
        assert Path(str(save_stem) + ".zip").is_file()
    finally:
        if model is not None:
            model.env = None  # type: ignore[assignment]
        env.close()
