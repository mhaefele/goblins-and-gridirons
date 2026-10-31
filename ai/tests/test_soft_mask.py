"""Soft-mask preferred factor values (expectedScenarioMove rows)."""

from __future__ import annotations

import numpy as np

from gridiron_ai.actions import FACTOR_ACTOR_KIND, FACTOR_BOARD_TARGET, preferred_factor_values
from gridiron_ai.constants import encode_actor_kind


def test_preferred_factor_values_empty_without_flags() -> None:
    legal = np.asarray(
        [
            [encode_actor_kind(1, 0), 10, 3],
            [encode_actor_kind(1, 1), 11, 3],
        ],
        dtype=np.int64,
    )
    preferred = np.zeros(2, dtype=np.int8)
    values = preferred_factor_values(
        legal,
        preferred,
        legal_move_count=2,
        chosen_factors={},
        factor_slot=FACTOR_ACTOR_KIND,
    )
    assert values.size == 0


def test_preferred_factor_values_boosts_flagged_kind_and_target() -> None:
    good_kind = encode_actor_kind(1, 0)
    bad_kind = encode_actor_kind(1, 1)
    legal = np.asarray(
        [
            [good_kind, 10, 3],
            [bad_kind, 11, 3],
            [good_kind, 12, 3],
        ],
        dtype=np.int64,
    )
    preferred = np.asarray([1, 0, 0], dtype=np.int8)
    kinds = preferred_factor_values(
        legal,
        preferred,
        legal_move_count=3,
        chosen_factors={},
        factor_slot=FACTOR_ACTOR_KIND,
    )
    assert list(kinds) == [good_kind]

    targets = preferred_factor_values(
        legal,
        preferred,
        legal_move_count=3,
        chosen_factors={FACTOR_ACTOR_KIND: int(good_kind)},
        factor_slot=FACTOR_BOARD_TARGET,
    )
    assert list(targets) == [10]
