"""--skip-scenarios routing in train_self_play."""

from __future__ import annotations

import argparse

from scripts.train_self_play import _skip_scenario_eval


def _args(**kwargs: object) -> argparse.Namespace:
    base = {
        "skip_scenarios": False,
        "eval_only": False,
    }
    base.update(kwargs)
    return argparse.Namespace(**base)


def test_skip_scenario_eval_only_when_eval_only() -> None:
    assert not _skip_scenario_eval(_args())
    assert not _skip_scenario_eval(_args(skip_scenarios=True))
    assert not _skip_scenario_eval(_args(eval_only=True))
    assert _skip_scenario_eval(_args(skip_scenarios=True, eval_only=True))
