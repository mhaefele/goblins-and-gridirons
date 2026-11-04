"""Load Kotlin expert JSONL dumps into gym Dict + ragged legals for clone.

Owner: dump line → npz tensors (``scripts/import_expert``). Kotlin writes packed
``ExpertStepRecord`` lines; clone pads per minibatch. No HTTP. See ``ai/OWNERSHIP.md``.

Soft-mask is not part of the expert dump (or is ignored if an old line still has
``softMaskLegal``). ``episodeFamily`` is optional (stats only); missing → kind mix
**total** only. Clone scores plain logits; PPO soft-mask comes from the live env.
"""

from __future__ import annotations

import gzip
import json
import logging
from collections import Counter
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
from gridiron_ai.expert_stats import (
    add_kind_to_breakdown,
    bucket_for_episode_family,
    empty_kind_breakdown,
    format_kind_breakdown,
    wire_kind_from_action_id,
    write_kind_stats,
)
from gridiron_ai.obs import dict_observation_space, obs_flat_to_dict

logger = logging.getLogger(__name__)

NPZ_KEY_ACTION = "action"
NPZ_KEY_SCHEMA_VERSION = "schema_version"
# Ragged legal rows: flat[offset[i]:offset[i+1]] belongs to step i.
NPZ_KEY_LEGAL_MOVES_FLAT = "legal_moves_flat"
# Legacy npz key — ignored on load; clone never applies soft-mask from the dump.
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


def pack_record(record: dict[str, Any]) -> tuple[dict[str, np.ndarray], np.ndarray, np.ndarray]:
    """One dump line → (game-state Dict, ragged legal rows, chosen row index)."""
    schema = int(record.get("schemaVersion") or 0)
    if schema != SCHEMA_VERSION:
        raise ValueError(f"expert schemaVersion={schema} (harness expects {SCHEMA_VERSION})")
    if "state" in record or "legals" in record:
        raise ValueError(
            "expert JSONL is nested (pre-packed-dump format); re-run --export-expert after the "
            "packed ExpertStepRecord change"
        )
    game_obs = obs_flat_to_dict(record["obs"])
    legal_rows = _ragged_legals(record)
    factors = np.asarray(record["chosenFactors"], dtype=np.int64).reshape(-1)
    if factors.size != FACTORS_PER_LEGAL_MOVE:
        raise ValueError(f"chosenFactors length {factors.size} != {FACTORS_PER_LEGAL_MOVE}")
    action = np.asarray([match_legal_row_index(factors, legal_rows)], dtype=np.int64)
    return game_obs, legal_rows, action


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


def records_to_arrays(jsonl_path: Path) -> tuple[dict[str, np.ndarray], dict[str, Counter[str]]]:
    """Two-pass pack: preallocate game-state + ragged legal flats, then fill.

    Also tallies chosen-move wire kinds into scenarios / games / total for pack summary.
    """
    n_steps, n_rows = count_dump(jsonl_path)
    space = dict_observation_space()
    arrays: dict[str, np.ndarray] = {
        key: np.empty((n_steps, *space.spaces[key].shape), dtype=space.spaces[key].dtype)
        for key in OBS_KEYS_GAME_STATE
    }
    legal_flat = np.empty((n_rows, FACTORS_PER_LEGAL_MOVE), dtype=np.int64)
    offsets = np.empty((n_steps + 1,), dtype=np.int64)
    actions = np.empty((n_steps,), dtype=np.int64)
    kind_breakdown = empty_kind_breakdown()
    offsets[0] = 0
    row_cursor = 0
    for index, record in enumerate(load_jsonl_records(jsonl_path)):
        game_obs, legal_rows, action = pack_record(record)
        for key in OBS_KEYS_GAME_STATE:
            arrays[key][index] = game_obs[key]
        n = int(legal_rows.shape[0])
        end = row_cursor + n
        if n:
            legal_flat[row_cursor:end] = legal_rows
        row_cursor = end
        offsets[index + 1] = row_cursor
        actions[index] = int(action.reshape(-1)[0])
        add_kind_to_breakdown(
            kind_breakdown,
            wire_kind_from_action_id(record.get("chosenLegalActionId")),
            bucket=bucket_for_episode_family(record.get("episodeFamily")),
        )
        if (index + 1) % 500 == 0:
            logger.info("packed %s expert steps", index + 1)
    if row_cursor != n_rows:
        raise RuntimeError(f"legal row count drifted: filled {row_cursor} vs counted {n_rows}")
    arrays[NPZ_KEY_LEGAL_MOVES_FLAT] = legal_flat
    arrays[NPZ_KEY_LEGAL_ROW_OFFSET] = offsets
    arrays[NPZ_KEY_ACTION] = actions
    arrays[NPZ_KEY_SCHEMA_VERSION] = np.int32(SCHEMA_VERSION)
    return arrays, kind_breakdown


def jsonl_to_npz(jsonl_path: Path, npz_path: Path) -> dict[str, np.ndarray]:
    """Read Kotlin JSONL, slice tensors, write ``npz_path`` (creates parent dirs).

    Also writes ``{stem}_kind_stats.txt`` beside the npz and logs scenarios / games / total.
    """
    arrays, kind_breakdown = records_to_arrays(jsonl_path)
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
    stats_path = npz_path.with_name(f"{npz_path.stem}_kind_stats.txt")
    write_kind_stats(stats_path, kind_breakdown)
    logger.info("expert chosen-move kinds → %s\n%s", stats_path, format_kind_breakdown(kind_breakdown))
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
    for key in (NPZ_KEY_LEGAL_MOVES_FLAT, NPZ_KEY_LEGAL_ROW_OFFSET, NPZ_KEY_ACTION):
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
    """Index steps and pad ragged legals to ``MAX_LEGAL_MOVES`` for the policy Dict.

    Soft-mask obs is always zeros — expert dumps do not carry preference flags into clone.

    # DEVIATION: scatter ragged flat rows with vectorized repeat/arange instead of a
    # Python per-step copy loop — clone was host-bound on that pad at millions of steps.
    """
    idx = np.asarray(idx, dtype=np.int64)
    obs = {key: arrays[key][idx] for key in OBS_KEYS_GAME_STATE}
    offsets = arrays[NPZ_KEY_LEGAL_ROW_OFFSET]
    flat = arrays[NPZ_KEY_LEGAL_MOVES_FLAT]
    batch = int(idx.shape[0])
    legal = np.full((batch, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=np.int64)
    preferred = np.zeros((batch, MAX_LEGAL_MOVES), dtype=np.int8)
    if batch == 0:
        obs[OBS_KEY_LEGAL_MOVES] = legal
        obs[OBS_KEY_SOFT_MASK_LEGAL] = preferred
        obs[OBS_KEY_LEGAL_MOVE_COUNT] = np.zeros((0, 1), dtype=np.int32)
        return obs
    starts = np.asarray(offsets[idx], dtype=np.int64)
    ends = np.asarray(offsets[idx + 1], dtype=np.int64)
    lens = ends - starts
    widest = int(lens.max())
    if widest > MAX_LEGAL_MOVES:
        bad = int(idx[int(np.argmax(lens))])
        raise ValueError(f"step {bad} has {widest} legal rows > MAX_LEGAL_MOVES={MAX_LEGAL_MOVES}")
    counts = lens.astype(np.int32, copy=False).reshape(batch, 1)
    total = int(lens.sum())
    if total:
        out_row = np.repeat(np.arange(batch, dtype=np.int64), lens)
        flat_pos = np.arange(total, dtype=np.int64)
        group_base = np.repeat(np.cumsum(lens, dtype=np.int64) - lens, lens)
        out_col = flat_pos - group_base
        src = np.repeat(starts, lens) + out_col
        legal[out_row, out_col] = flat[src]
    obs[OBS_KEY_LEGAL_MOVES] = legal
    obs[OBS_KEY_SOFT_MASK_LEGAL] = preferred
    obs[OBS_KEY_LEGAL_MOVE_COUNT] = counts
    return obs


def _ragged_legals(record: dict[str, Any]) -> np.ndarray:
    encoded = np.asarray(record["legalMoves"], dtype=np.int64).reshape(-1, FACTORS_PER_LEGAL_MOVE)
    if encoded.shape[0] == 0:
        raise ValueError("expert step has zero legal moves")
    if encoded.shape[0] > MAX_LEGAL_MOVES:
        raise ValueError(
            f"Step has {encoded.shape[0]} legal moves > MAX_LEGAL_MOVES={MAX_LEGAL_MOVES}"
        )
    # Old dumps may still include softMaskLegal; ignore — not used for clone.
    return encoded


def _is_gzip(path: Path) -> bool:
    name = path.name.lower()
    return name.endswith(".gz") or path.suffix == ".gz"
