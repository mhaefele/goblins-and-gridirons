"""Load Kotlin expert JSONL dumps into gym Dict + ragged legals for clone.

Owner: dump line → npz tensors (``scripts/import_expert``). Kotlin writes packed
``ExpertStepRecord`` lines; clone pads per minibatch. No HTTP. See ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

import gzip
import json
import logging
from pathlib import Path
from typing import Any, Iterator

import numpy as np

from gridiron_ai.actions import match_legal_row_index
from gridiron_ai.constants import (
    FACTORS_PER_LEGAL_MOVE,
    MAX_LEGAL_MOVES,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_PITCH,
    OBS_KEY_SOFT_MASK_LEGAL,
    OBS_KEYS_GAME_STATE,
    SCHEMA_VERSION,
)
from gridiron_ai.obs import dict_observation_space, obs_flat_to_dict

logger = logging.getLogger(__name__)

NPZ_KEY_ACTION = "action"
NPZ_KEY_SCHEMA_VERSION = "schema_version"
# Ragged legal rows: flat[offset[i]:offset[i+1]] belongs to step i.
NPZ_KEY_LEGAL_MOVES_FLAT = "legal_moves_flat"
NPZ_KEY_SOFT_MASK_LEGAL_FLAT = "preferred_legal_flat"
NPZ_KEY_LEGAL_ROW_OFFSET = "legal_row_offset"


def load_jsonl_records(path: Path) -> Iterator[dict[str, Any]]:
    """Yield one ExpertStepRecord dict per line. ``.gz`` / ``.jsonl.gz`` is decompressed."""
    opener = gzip.open if _is_gzip(path) else open
    with opener(path, "rt", encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, start=1):
            line = raw.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number} is not JSON: {exc}") from None
            if not isinstance(record, dict):
                raise ValueError(f"{path}:{line_number} is not a JSON object")
            yield record


def pack_record(record: dict[str, Any]) -> tuple[dict[str, np.ndarray], np.ndarray, np.ndarray, np.ndarray]:
    """One dump line → (game-state Dict, ragged legal rows, preferred flags, chosen row index)."""
    schema = int(record.get("schemaVersion") or 0)
    if schema != SCHEMA_VERSION:
        raise ValueError(f"expert schemaVersion={schema} (harness expects {SCHEMA_VERSION})")
    if "state" in record or "legals" in record:
        raise ValueError(
            "expert JSONL is nested (pre-packed-dump format); re-run --export-expert after the "
            "packed ExpertStepRecord change"
        )
    game_obs = obs_flat_to_dict(record["obs"])
    legal_rows, preferred = _ragged_legals(record)
    factors = np.asarray(record["chosenFactors"], dtype=np.int64).reshape(-1)
    if factors.size != FACTORS_PER_LEGAL_MOVE:
        raise ValueError(f"chosenFactors length {factors.size} != {FACTORS_PER_LEGAL_MOVE}")
    action = np.asarray([match_legal_row_index(factors, legal_rows)], dtype=np.int64)
    return game_obs, legal_rows, preferred, action


def count_dump(path: Path) -> tuple[int, int]:
    """Pass 1: ``(n_steps, n_legal_rows)`` so pass 2 can preallocate (no list→stack peak)."""
    n_steps = 0
    n_rows = 0
    for record in load_jsonl_records(path):
        if "state" in record or "legals" in record:
            raise ValueError(
                "expert JSONL is nested (pre-packed-dump format); re-run --export-expert after the "
                "packed ExpertStepRecord change"
            )
        moves = record.get("legalMoves")
        if not isinstance(moves, list):
            raise ValueError("expert record missing legalMoves list")
        n_steps += 1
        n_rows += len(moves)
        if n_steps % 50_000 == 0:
            logger.info("counted %s expert steps (%s legal rows)", n_steps, n_rows)
    if n_steps == 0:
        raise ValueError("expert dump contained no steps")
    logger.info("count done: %s steps, %s legal rows", n_steps, n_rows)
    return n_steps, n_rows


def records_to_arrays(jsonl_path: Path) -> dict[str, np.ndarray]:
    """Two-pass pack: preallocate game-state + ragged legal flats, then fill."""
    n_steps, n_rows = count_dump(jsonl_path)
    space = dict_observation_space()
    arrays: dict[str, np.ndarray] = {
        key: np.empty((n_steps, *space.spaces[key].shape), dtype=space.spaces[key].dtype)
        for key in OBS_KEYS_GAME_STATE
    }
    legal_flat = np.empty((n_rows, FACTORS_PER_LEGAL_MOVE), dtype=np.int64)
    preferred_flat = np.empty((n_rows,), dtype=np.int8)
    offsets = np.empty((n_steps + 1,), dtype=np.int64)
    actions = np.empty((n_steps,), dtype=np.int64)
    offsets[0] = 0
    row_cursor = 0
    for index, record in enumerate(load_jsonl_records(jsonl_path)):
        game_obs, legal_rows, preferred, action = pack_record(record)
        for key in OBS_KEYS_GAME_STATE:
            arrays[key][index] = game_obs[key]
        n = int(legal_rows.shape[0])
        end = row_cursor + n
        if n:
            legal_flat[row_cursor:end] = legal_rows
            preferred_flat[row_cursor:end] = preferred
        row_cursor = end
        offsets[index + 1] = row_cursor
        actions[index] = int(action.reshape(-1)[0])
        if (index + 1) % 500 == 0:
            logger.info("packed %s expert steps", index + 1)
    if row_cursor != n_rows:
        raise RuntimeError(f"legal row count drifted: filled {row_cursor} vs counted {n_rows}")
    arrays[NPZ_KEY_LEGAL_MOVES_FLAT] = legal_flat
    arrays[NPZ_KEY_SOFT_MASK_LEGAL_FLAT] = preferred_flat
    arrays[NPZ_KEY_LEGAL_ROW_OFFSET] = offsets
    arrays[NPZ_KEY_ACTION] = actions
    arrays[NPZ_KEY_SCHEMA_VERSION] = np.int32(SCHEMA_VERSION)
    return arrays


def jsonl_to_npz(jsonl_path: Path, npz_path: Path) -> dict[str, np.ndarray]:
    """Read Kotlin JSONL, slice tensors, write ``npz_path`` (creates parent dirs)."""
    arrays = records_to_arrays(jsonl_path)
    npz_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(npz_path, **arrays)
    n = int(arrays[NPZ_KEY_ACTION].shape[0])
    pitch = arrays[OBS_KEY_PITCH].shape
    n_rows = int(arrays[NPZ_KEY_LEGAL_MOVES_FLAT].shape[0])
    logger.info(
        "wrote %s steps pitch=%s legal_rows=%s action=%s → %s",
        n,
        pitch,
        n_rows,
        arrays[NPZ_KEY_ACTION].shape,
        npz_path,
    )
    return arrays


def load_npz(path: Path) -> dict[str, np.ndarray]:
    """Read an import_expert npz (ragged legals). Validates schema_version and keys."""
    with np.load(path) as packed:
        arrays = {key: packed[key] for key in packed.files}
    schema = int(arrays[NPZ_KEY_SCHEMA_VERSION])
    if schema != SCHEMA_VERSION:
        raise ValueError(f"{path} schema_version={schema} (harness expects {SCHEMA_VERSION})")
    if "board" in arrays and "pitch" not in arrays:
        raise ValueError(f"{path} is a pre-split 2067-vector dump; re-run import_expert")
    if OBS_KEY_LEGAL_MOVES in arrays and NPZ_KEY_LEGAL_MOVES_FLAT not in arrays:
        raise ValueError(f"{path} has padded legal_moves; re-run import_expert for ragged legals")
    missing_game = [key for key in OBS_KEYS_GAME_STATE if key not in arrays]
    if missing_game:
        raise ValueError(f"{path} missing game-state keys {missing_game}")
    for key in (NPZ_KEY_LEGAL_MOVES_FLAT, NPZ_KEY_SOFT_MASK_LEGAL_FLAT, NPZ_KEY_LEGAL_ROW_OFFSET, NPZ_KEY_ACTION):
        if key not in arrays:
            raise ValueError(f"{path} missing ragged-legal key {key!r}; re-run import_expert")
    n = int(arrays[NPZ_KEY_ACTION].shape[0])
    offsets = arrays[NPZ_KEY_LEGAL_ROW_OFFSET]
    if offsets.shape != (n + 1,):
        raise ValueError(f"{path} legal_row_offset shape {offsets.shape} != ({n + 1},)")
    if int(offsets[0]) != 0 or int(offsets[-1]) != int(arrays[NPZ_KEY_LEGAL_MOVES_FLAT].shape[0]):
        raise ValueError(f"{path} legal_row_offset does not cover legal_moves_flat")
    return arrays


def batch_gym_obs(arrays: dict[str, np.ndarray], idx: np.ndarray) -> dict[str, np.ndarray]:
    """Index steps and pad ragged legals to ``MAX_LEGAL_MOVES`` for the policy Dict."""
    obs = {key: arrays[key][idx] for key in OBS_KEYS_GAME_STATE}
    offsets = arrays[NPZ_KEY_LEGAL_ROW_OFFSET]
    flat = arrays[NPZ_KEY_LEGAL_MOVES_FLAT]
    preferred_flat = arrays[NPZ_KEY_SOFT_MASK_LEGAL_FLAT]
    batch = int(idx.shape[0])
    legal = np.full((batch, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=np.int64)
    preferred = np.zeros((batch, MAX_LEGAL_MOVES), dtype=np.int8)
    counts = np.zeros((batch, 1), dtype=np.int32)
    for j, step_i in enumerate(idx):
        start = int(offsets[step_i])
        end = int(offsets[step_i + 1])
        n = end - start
        if n > MAX_LEGAL_MOVES:
            raise ValueError(f"step {int(step_i)} has {n} legal rows > MAX_LEGAL_MOVES={MAX_LEGAL_MOVES}")
        if n:
            legal[j, :n] = flat[start:end]
            preferred[j, :n] = preferred_flat[start:end]
        counts[j, 0] = n
    obs[OBS_KEY_LEGAL_MOVES] = legal
    obs[OBS_KEY_SOFT_MASK_LEGAL] = preferred
    obs[OBS_KEY_LEGAL_MOVE_COUNT] = counts
    return obs


def _ragged_legals(record: dict[str, Any]) -> tuple[np.ndarray, np.ndarray]:
    encoded = np.asarray(record["legalMoves"], dtype=np.int64).reshape(-1, FACTORS_PER_LEGAL_MOVE)
    if encoded.shape[0] == 0:
        raise ValueError("expert step has zero legal moves")
    if encoded.shape[0] > MAX_LEGAL_MOVES:
        raise ValueError(
            f"Step has {encoded.shape[0]} legal moves > MAX_LEGAL_MOVES={MAX_LEGAL_MOVES}"
        )
    mask = record.get("softMaskLegal")
    if not isinstance(mask, list):
        raise ValueError("expert record missing softMaskLegal list")
    if len(mask) != encoded.shape[0]:
        raise ValueError(
            f"softMaskLegal length {len(mask)} != legalMoves rows {encoded.shape[0]}"
        )
    preferred_src = np.asarray(mask, dtype=np.bool_).reshape(-1)
    preferred = np.zeros((encoded.shape[0],), dtype=np.int8)
    n_pref = min(encoded.shape[0], preferred_src.size)
    if n_pref:
        preferred[:n_pref] = preferred_src[:n_pref]
    return encoded, preferred


def _is_gzip(path: Path) -> bool:
    name = path.name.lower()
    return name.endswith(".gz") or path.suffix == ".gz"
