"""Offline wiring smokes (no backend).

Catches arity / import drift in env factories. Import / ``python -m`` boot failures
belong in ``test_entrypoint_imports.py``. Live harness path: ``test_train_live_smoke.py``.
"""

from __future__ import annotations

import inspect

from gymnasium import spaces

from gridiron_ai.model.reward import RewardConfig
from scripts.train_self_play import (
    DEFAULT_N_ENVS,
    _env_thunk,
    make_bootstrap_vec_env,
    make_env,
    make_vec_env,
)


def test_make_bootstrap_vec_env_runs_once() -> None:
    """Build the same DummyVecEnv stack ``_load_or_create_model`` uses (n_envs=1)."""
    env = make_bootstrap_vec_env(
        base_url="http://127.0.0.1:9",
        reward_config=RewardConfig(),
        n_envs=1,
        verbose=False,
    )
    try:
        assert env.num_envs == 1
        assert isinstance(env.action_space, spaces.Discrete)
        assert isinstance(env.observation_space, spaces.Dict)
    finally:
        env.close()


def test_make_env_runs_once() -> None:
    """Single wrapped GridironEnv constructs without hitting the API (reset not called)."""
    root = make_env(
        "http://127.0.0.1:9",
        500,
        reward_config=RewardConfig(),
        verbose=False,
    )
    try:
        assert isinstance(root.unwrapped.action_space, spaces.Discrete)
        assert root.unwrapped.verbose is False
    finally:
        root.close()


def test_env_thunk_verbose_is_keyword_only() -> None:
    """Trailing flags stay keyword-only so forgotten positionals fail loudly at call sites."""
    params = inspect.signature(_env_thunk).parameters
    assert params["verbose"].kind is inspect.Parameter.KEYWORD_ONLY


def test_make_vec_env_signature_accepts_verbose() -> None:
    """``make_vec_env`` must keep forwarding verbose (WorkerPool spawn path)."""
    assert "verbose" in inspect.signature(make_vec_env).parameters
    assert "verbose" in inspect.signature(make_bootstrap_vec_env).parameters
    assert DEFAULT_N_ENVS >= 1
