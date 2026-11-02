"""Ragged legal rows in expert npz (no MAX_LEGAL pad on disk)."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from gridiron_ai.constants import (
    FACTORS_PER_LEGAL_MOVE,
    MAX_LEGAL_MOVES,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
    OBSERVATION_VECTOR_LENGTH,
    SCHEMA_VERSION,
)
from gridiron_ai.expert import (
    NPZ_KEY_ACTION,
    NPZ_KEY_LEGAL_MOVES_FLAT,
    NPZ_KEY_LEGAL_ROW_OFFSET,
    NPZ_KEY_SOFT_MASK_LEGAL_FLAT,
    batch_gym_obs,
    jsonl_to_npz,
    load_npz,
)


def _record(legal_rows: list[list[int]], *, preferred: list[bool] | None = None) -> dict:
    pref = preferred if preferred is not None else [False] * len(legal_rows)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "obs": [0.0] * OBSERVATION_VECTOR_LENGTH,
        "legalMoves": legal_rows,
        "softMaskLegal": pref,
        "chosenFactors": legal_rows[0],
        "chosenLegalActionId": 0,
    }


def test_jsonl_to_npz_stores_ragged_legals(tmp_path: Path) -> None:
    row_a = [1, 0, 10, 0, 3, 5, 0, 0, 0, 2, 3]
    row_b = [2, 1, 0, 1, 3, 5, 0, 0, 0, 4, 5]
    row_c = [1, 2, 1, 1, 4, 5, 0, 0, 0, 6, 7]
    jsonl = tmp_path / "tiny.jsonl"
    lines = [
        _record([row_a, row_b], preferred=[True, False]),
        _record([row_c], preferred=[False]),
    ]
    jsonl.write_text("\n".join(json.dumps(line) for line in lines) + "\n", encoding="utf-8")
    npz = tmp_path / "tiny.npz"
    jsonl_to_npz(jsonl, npz)
    arrays = load_npz(npz)

    assert arrays[NPZ_KEY_ACTION].shape == (2,)
    assert arrays[NPZ_KEY_ACTION].tolist() == [0, 0]
    assert arrays[NPZ_KEY_LEGAL_MOVES_FLAT].shape == (3, FACTORS_PER_LEGAL_MOVE)
    assert arrays[NPZ_KEY_SOFT_MASK_LEGAL_FLAT].tolist() == [1, 0, 0]
    assert arrays[NPZ_KEY_LEGAL_ROW_OFFSET].tolist() == [0, 2, 3]
    assert OBS_KEY_LEGAL_MOVES not in arrays

    obs = batch_gym_obs(arrays, np.asarray([0, 1], dtype=np.int64))
    assert obs[OBS_KEY_LEGAL_MOVES].shape == (2, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE)
    assert obs[OBS_KEY_SOFT_MASK_LEGAL].shape == (2, MAX_LEGAL_MOVES)
    assert obs[OBS_KEY_LEGAL_MOVE_COUNT].tolist() == [[2], [1]]
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][0, :2], [row_a, row_b])
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][0, 2], [-1] * FACTORS_PER_LEGAL_MOVE)
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][1, :1], [row_c])
    assert obs[OBS_KEY_SOFT_MASK_LEGAL][0, 0] == 1
