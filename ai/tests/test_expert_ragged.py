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
    OBS_KEYS_GAME_STATE,
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
from gridiron_ai.obs import dict_observation_space


def _record(
    legal_rows: list[list[int]],
    *,
    soft_mask: list[bool] | None = None,
    action_id: str = "RUN_h0_1x1",
) -> dict:
    out: dict = {
        "schemaVersion": SCHEMA_VERSION,
        "obs": [0.0] * OBSERVATION_VECTOR_LENGTH,
        "legalMoves": legal_rows,
        "chosenFactors": legal_rows[0],
        "chosenLegalActionId": action_id,
    }
    if soft_mask is not None:
        out["softMaskLegal"] = soft_mask
    return out


def test_jsonl_to_npz_stores_ragged_legals(tmp_path: Path) -> None:
    row_a = [1, 0, 10, 0, 3, 5, 0, 0, 0, 2, 3]
    row_b = [2, 1, 0, 1, 3, 5, 0, 0, 0, 4, 5]
    row_c = [1, 2, 1, 1, 4, 5, 0, 0, 0, 6, 7]
    jsonl = tmp_path / "tiny.jsonl"
    lines = [
        _record([row_a, row_b], action_id="RUN_h0_1x1"),
        _record([row_c], action_id="BLOCK_h1_2x2"),
    ]
    jsonl.write_text("\n".join(json.dumps(line) for line in lines) + "\n", encoding="utf-8")
    npz = tmp_path / "tiny.npz"
    jsonl_to_npz(jsonl, npz)
    arrays = load_npz(npz)

    assert arrays[NPZ_KEY_ACTION].shape == (2,)
    assert arrays[NPZ_KEY_ACTION].tolist() == [0, 0]
    assert arrays[NPZ_KEY_LEGAL_MOVES_FLAT].shape == (3, FACTORS_PER_LEGAL_MOVE)
    assert NPZ_KEY_SOFT_MASK_LEGAL_FLAT not in arrays
    assert arrays[NPZ_KEY_LEGAL_ROW_OFFSET].tolist() == [0, 2, 3]
    assert OBS_KEY_LEGAL_MOVES not in arrays

    obs = batch_gym_obs(arrays, np.asarray([0, 1], dtype=np.int64))
    assert obs[OBS_KEY_LEGAL_MOVES].shape == (2, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE)
    assert obs[OBS_KEY_SOFT_MASK_LEGAL].shape == (2, MAX_LEGAL_MOVES)
    assert obs[OBS_KEY_LEGAL_MOVE_COUNT].tolist() == [[2], [1]]
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][0, :2], [row_a, row_b])
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][0, 2], [-1] * FACTORS_PER_LEGAL_MOVE)
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][1, :1], [row_c])
    assert int(obs[OBS_KEY_SOFT_MASK_LEGAL].sum()) == 0

    stats = (tmp_path / "tiny_kind_stats.txt").read_text(encoding="utf-8")
    # Untagged legacy lines → scenarios/games empty; kinds only under total.
    assert "=== scenarios ===" in stats and "=== games ===" in stats and "=== total ===" in stats
    assert stats.index("=== scenarios ===") < stats.index("=== total ===")
    scenarios_block = stats.split("=== games ===")[0]
    assert "(no steps)" in scenarios_block
    assert "RUN" in stats and "BLOCK" in stats
    assert "50.00%" in stats
    assert "TOTAL" in stats


def test_jsonl_ignores_legacy_soft_mask_legal(tmp_path: Path) -> None:
    row_a = [1, 0, 10, 0, 3, 5, 0, 0, 0, 2, 3]
    jsonl = tmp_path / "legacy.jsonl"
    jsonl.write_text(
        json.dumps(_record([row_a], soft_mask=[True])) + "\n",
        encoding="utf-8",
    )
    npz = tmp_path / "legacy.npz"
    jsonl_to_npz(jsonl, npz)
    arrays = load_npz(npz)
    obs = batch_gym_obs(arrays, np.asarray([0], dtype=np.int64))
    assert int(obs[OBS_KEY_SOFT_MASK_LEGAL].sum()) == 0


def test_batch_gym_obs_pads_uneven_ragged_lengths() -> None:
    """Vectorized pad must match the old per-step copy for uneven menus."""
    flat = np.asarray(
        [
            [1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            [2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            [3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            [4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
            [5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        ],
        dtype=np.int64,
    )
    assert flat.shape[1] == FACTORS_PER_LEGAL_MOVE
    arrays = {
        NPZ_KEY_LEGAL_MOVES_FLAT: flat,
        NPZ_KEY_LEGAL_ROW_OFFSET: np.asarray([0, 2, 2, 5], dtype=np.int64),  # lens 2, 0, 3
        NPZ_KEY_ACTION: np.zeros((3,), dtype=np.int64),
    }
    space = dict_observation_space()
    for key in OBS_KEYS_GAME_STATE:
        shape = space.spaces[key].shape
        arrays[key] = np.zeros((3, *shape), dtype=space.spaces[key].dtype)

    obs = batch_gym_obs(arrays, np.asarray([0, 2, 1], dtype=np.int64))
    assert obs[OBS_KEY_LEGAL_MOVE_COUNT].tolist() == [[2], [3], [0]]
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][0, :2], flat[0:2])
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][0, 2], [-1] * FACTORS_PER_LEGAL_MOVE)
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][1, :3], flat[2:5])
    np.testing.assert_array_equal(obs[OBS_KEY_LEGAL_MOVES][2, 0], [-1] * FACTORS_PER_LEGAL_MOVE)


def test_load_npz_tolerates_legacy_preferred_flat(tmp_path: Path) -> None:
    row_a = [1, 0, 10, 0, 3, 5, 0, 0, 0, 2, 3]
    jsonl = tmp_path / "tiny.jsonl"
    jsonl.write_text(json.dumps(_record([row_a])) + "\n", encoding="utf-8")
    npz = tmp_path / "tiny.npz"
    jsonl_to_npz(jsonl, npz)
    arrays = dict(load_npz(npz))
    n_rows = int(arrays[NPZ_KEY_LEGAL_MOVES_FLAT].shape[0])
    arrays[NPZ_KEY_SOFT_MASK_LEGAL_FLAT] = np.ones((n_rows,), dtype=np.int8)
    np.savez_compressed(npz, **arrays)
    loaded = load_npz(npz)
    obs = batch_gym_obs(loaded, np.asarray([0], dtype=np.int64))
    assert int(obs[OBS_KEY_SOFT_MASK_LEGAL].sum()) == 0
