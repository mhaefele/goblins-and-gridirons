"""Freeze a trained MaskablePPO policy into an ONNX file for the Java product.

What this file does
-------------------
Training uses Python + Stable-Baselines3. The browser/game product needs a
small portable graph (ONNX) that Kotlin can run without Python.

We wrap the trained pieces we care about for **play** (not training):

1. Feature extractor — board Dict → join-board numbers (same as training).
2. Row scorer — score each legal menu row given that board.
3. Argmax — pick the highest-scoring **live** row (pad rows forced very low).

Soft-mask preference bias is **omitted** here: product seats should play the
learned preference, not the curriculum "look here" tilt.

See ``ai/OWNERSHIP.md`` / BACKLOG AI2.6.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
import torch as th
from torch import nn

from gridiron_ai.model.constants import (
    ACTION_FACTOR_WIDTHS,
    ACTIVE_CHALLENGE_SLOTS,
    BENCH_SLOTS,
    BOARD_SLOT_COUNT,
    BONUS_HAND_SLOTS,
    DECK_FIELD_COUNT,
    FACTORS_PER_LEGAL_MOVE,
    HELD_CHECK_FIELD_COUNT,
    LEGAL_ROW_SCORE_WIDTH,
    MAX_LEGAL_MOVES,
    NUMBERS_PER_BOARD_CELL,
    OBSERVATION_VECTOR_LENGTH,
    OBS_KEY_BENCHES,
    OBS_KEY_CHALLENGES,
    OBS_KEY_DECK,
    OBS_KEY_HAND,
    OBS_KEY_HELD_CHECK,
    OBS_KEY_PITCH,
    OBS_KEY_SCALARS,
    PITCH_SQUARE_COUNT,
    PLAY_HEIGHT,
    PLAY_WIDTH,
    SCALAR_COUNT,
    SCHEMA_VERSION,
)
from gridiron_ai.model.features import GridironFeaturesExtractor
from gridiron_ai.train.paths import AI_ROOT, OUTPUT_DIR

# Where Maven packs opponent artifacts: backend/.../resources/ai-opponent/{profile}/
BACKEND_AI_OPPONENT_ROOT = AI_ROOT.parent / "backend" / "src" / "main" / "resources" / "ai-opponent"
# Scratch copy under ai/output/exported/ (handy for local smoke tests).
EXPORTED_DIR = OUTPUT_DIR / "exported"
POLICY_ONNX_NAME = "policy.onnx"
MANIFEST_NAME = "manifest.json"
# ONNX graph I/O names — Kotlin must use the same strings when loading the file.
INPUT_OBS_NAME = "packed_obs"
INPUT_LEGAL_NAME = "legal_moves"
OUTPUT_NAME = "action"

PROFILE_TITLES: dict[str, str] = {
    "baseline": "Baseline",
    "general": "General",
    "spread": "Spread",
    "td": "Touchdown",
    "defense": "Defense",
}
PROFILE_DESCRIPTIONS: dict[str, str] = {
    "baseline": "Unshaped PPO control — score margin and win/loss only.",
    "general": "Balanced trained policy — all-round play.",
    "spread": "Trained for field coverage and ball progress together.",
    "td": "Trained to push scoring and ball progress.",
    "defense": "Trained to knock opponents prone, send them off, and free the ball.",
}


class PackedObsAndLegalsToAction(nn.Module):
    """ONNX-friendly module: packed board + legal menu → chosen menu index.

    Mirrors deterministic play in training (masked argmax over row scores).
    Does not include the soft-mask logit bias used during curriculum training.
    """

    def __init__(self, policy: nn.Module) -> None:
        """Copy the trained feature extractor and row scorer out of an SB3 policy."""
        super().__init__()
        if not isinstance(policy.features_extractor, GridironFeaturesExtractor):
            raise TypeError(
                "ONNX export requires GridironFeaturesExtractor "
                f"(got {type(policy.features_extractor)})"
            )
        # Same weights as training — not re-initialized.
        self.features_extractor = policy.features_extractor
        self.row_scorer = policy.row_scorer

    def forward(self, packed_obs: th.Tensor, legal_moves: th.Tensor) -> th.Tensor:
        """Run one forward pass: score every legal row, return the best index.

        Parameters
        ----------
        packed_obs
            Shape ``(batch, OBSERVATION_VECTOR_LENGTH)`` — the same flat float
            vector Kotlin already builds for the harness / product AI.
        legal_moves
            Shape ``(batch, MAX_LEGAL_MOVES, FACTORS)`` — padded menu. Unused
            rows are all SENTINEL (-1); live rows have ``kind >= 0``.

        Returns
        -------
        Long tensor shape ``(batch,)`` — index into ``legal_moves`` for each item.
        """
        # 1) Split the flat obs vector into the Dict keys the extractor expects
        #    (pitch grid, benches, scalars, …) — same layout as obs.py in Torch.
        obs = _slice_packed_obs(packed_obs)

        # 2) Encode the board. ``forward`` returns the value vector we ignore here;
        #    the important part is the temporary save of the full pitch map +
        #    non-pitch features for the join (see features.consume_join_board).
        fe = self.features_extractor
        _ = fe(obs)

        # 3) Read that temporary save: flatten(pitch CNN map) ‖ non-pitch.
        board = fe.consume_join_board()

        # 4) Encode every packed legal row with the same emb tables as the board.
        row_emb = fe.encode_legal_rows(legal_moves, obs[OBS_KEY_CHALLENGES])

        # 5) Glue board ‖ row for each menu slot. ``unsqueeze/expand`` repeats the
        #    same board vector next to every row without copying memory.
        batch, n_rows, _ = row_emb.shape
        board_b = board.unsqueeze(1).expand(batch, n_rows, -1)

        # 6) One score per row. ``squeeze(-1)`` drops the trailing size-1 dim from
        #    Linear → 1 so we get shape (batch, n_rows).
        scores = self.row_scorer(th.cat([board_b, row_emb], dim=-1)).squeeze(-1)

        # 7) Pad / empty rows must never win: force their scores to a huge negative.
        live = legal_moves[..., 0] >= 0
        scores = scores.masked_fill(~live, -1.0e9)

        # 8) Deterministic pick = index of the max score (product play, no sampling).
        return scores.argmax(dim=-1)


def _slice_packed_obs(packed_obs: th.Tensor) -> dict[str, th.Tensor]:
    """Cut the flat Kotlin obs vector into the named tensors the extractor uses.

    Must stay byte-for-byte aligned with ``obs.obs_flat_to_dict`` / AiObsLayout.
    Done in pure Torch ops so the whole graph can export to ONNX (no NumPy).
    """
    if packed_obs.dim() != 2 or packed_obs.shape[1] != OBSERVATION_VECTOR_LENGTH:
        raise ValueError(
            f"packed_obs shape {tuple(packed_obs.shape)} != (N, {OBSERVATION_VECTOR_LENGTH})"
        )
    # Cursor into the flat vector; each ``take`` advances it.
    i = 0

    def take(n: int) -> th.Tensor:
        """Return the next ``n`` floats along the vector and advance the cursor."""
        nonlocal i
        chunk = packed_obs[:, i : i + n]
        i += n
        return chunk

    # Order matches AiObsLayout packing: scalars → challenges → deck → hand →
    # held-check → all board cells (pitch squares then bench slots).
    scalars = take(SCALAR_COUNT)
    challenges = take(ACTIVE_CHALLENGE_SLOTS * 2).reshape(-1, ACTIVE_CHALLENGE_SLOTS, 2)
    deck = take(DECK_FIELD_COUNT)
    hand = take(BONUS_HAND_SLOTS)
    held_check = take(HELD_CHECK_FIELD_COUNT)
    cells = take(BOARD_SLOT_COUNT * NUMBERS_PER_BOARD_CELL).reshape(
        -1, BOARD_SLOT_COUNT, NUMBERS_PER_BOARD_CELL
    )
    if i != OBSERVATION_VECTOR_LENGTH:
        raise RuntimeError(f"obs slice cursor {i} != {OBSERVATION_VECTOR_LENGTH}")

    # Pitch: H×W squares → channel-first (N, C, H, W) for the CNN.
    pitch_hwc = cells[:, :PITCH_SQUARE_COUNT].reshape(
        -1, PLAY_HEIGHT, PLAY_WIDTH, NUMBERS_PER_BOARD_CELL
    )
    pitch = pitch_hwc.permute(0, 3, 1, 2).contiguous()
    # Remaining cells are the two dugouts (us, them) × BENCH_SLOTS.
    benches = cells[:, PITCH_SQUARE_COUNT:].reshape(-1, 2, BENCH_SLOTS, NUMBERS_PER_BOARD_CELL)
    return {
        OBS_KEY_SCALARS: scalars,
        OBS_KEY_CHALLENGES: challenges,
        OBS_KEY_DECK: deck,
        OBS_KEY_HAND: hand,
        OBS_KEY_HELD_CHECK: held_check,
        OBS_KEY_PITCH: pitch,
        OBS_KEY_BENCHES: benches,
    }


def export_dir_for(profile: str) -> Path:
    """Backend resource folder for one reward-profile opponent (``…/ai-opponent/{profile}``)."""
    return BACKEND_AI_OPPONENT_ROOT / profile


def export_policy_onnx(
    policy: nn.Module,
    *,
    profile: str,
    checkpoint_key: str,
    checkpoint_zip: Path,
    out_dir: Path | None = None,
) -> Path:
    """Write ``policy.onnx`` + ``manifest.json`` next to the Java classpath resources.

    Also copies the same pair under ``ai/output/exported/{profile}/`` for local
    smoke tests. Returns the backend destination directory.
    """
    destination = out_dir or export_dir_for(profile)
    destination.mkdir(parents=True, exist_ok=True)
    onnx_path = destination / POLICY_ONNX_NAME

    # Eval mode: disable dropout/etc. (we don't use dropout, but this is the rail).
    module = PackedObsAndLegalsToAction(policy).eval()
    # Dummy batch so Torch can trace the graph. One live legal row (kind=0), rest pad.
    dummy_obs = th.zeros((1, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    dummy_legal = th.full((1, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=th.int64)
    dummy_legal[0, 0] = 0
    with th.no_grad():
        action = module(dummy_obs, dummy_legal)
    if action.shape != (1,):
        raise RuntimeError(f"action shape {tuple(action.shape)} != (batch,)")

    # Trace/export the module. ``dynamic_axes`` lets batch size vary at runtime.
    th.onnx.export(
        module,
        (dummy_obs, dummy_legal),
        str(onnx_path),
        input_names=[INPUT_OBS_NAME, INPUT_LEGAL_NAME],
        output_names=[OUTPUT_NAME],
        dynamic_axes={
            INPUT_OBS_NAME: {0: "batch"},
            INPUT_LEGAL_NAME: {0: "batch"},
            OUTPUT_NAME: {0: "batch"},
        },
        opset_version=17,
        dynamo=False,
    )

    # Manifest tells Kotlin the schema + tensor names + factor widths for this file.
    manifest: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "profile": profile,
        "title": PROFILE_TITLES.get(profile, profile.replace("_", " ").title()),
        "description": PROFILE_DESCRIPTIONS.get(profile, f"Trained policy ({profile})."),
        "checkpointKey": checkpoint_key,
        "observationVectorLength": OBSERVATION_VECTOR_LENGTH,
        "maxLegalMoves": MAX_LEGAL_MOVES,
        "actionFactorCount": FACTORS_PER_LEGAL_MOVE,
        "actionFactorWidths": list(ACTION_FACTOR_WIDTHS),
        "actionSpaceN": LEGAL_ROW_SCORE_WIDTH,
        "inputObsName": INPUT_OBS_NAME,
        "inputLegalName": INPUT_LEGAL_NAME,
        "outputName": OUTPUT_NAME,
        "onnxFile": POLICY_ONNX_NAME,
    }
    (destination / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    # Local scratch copy (includes checkpoint zip path for humans debugging exports).
    scratch = EXPORTED_DIR / profile
    scratch.mkdir(parents=True, exist_ok=True)
    (scratch / POLICY_ONNX_NAME).write_bytes(onnx_path.read_bytes())
    scratch_manifest = {**manifest, "checkpointZip": str(checkpoint_zip.resolve())}
    (scratch / MANIFEST_NAME).write_text(json.dumps(scratch_manifest, indent=2) + "\n", encoding="utf-8")
    return destination


def smoke_compare_action(policy: nn.Module, onnx_path: Path) -> None:
    """Fail loudly if ONNX and Torch disagree on the chosen row for a random batch.

    Catches export/graph bugs before the artifact is treated as product-ready.
    """
    import onnxruntime as ort

    module = PackedObsAndLegalsToAction(policy).eval()
    packed = th.randn((2, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    legal = th.full((2, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=th.int64)
    # First five rows live with small random ints; rest stay SENTINEL pad.
    legal[:, :5] = th.randint(0, 3, (2, 5, FACTORS_PER_LEGAL_MOVE), dtype=th.int64)
    with th.no_grad():
        torch_action = module(packed, legal).numpy()

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_action = session.run(
        None,
        {INPUT_OBS_NAME: packed.numpy(), INPUT_LEGAL_NAME: legal.numpy()},
    )[0]
    if not np.array_equal(torch_action, onnx_action.reshape(-1)):
        raise RuntimeError(f"ONNX action {onnx_action} != Torch action {torch_action}")
