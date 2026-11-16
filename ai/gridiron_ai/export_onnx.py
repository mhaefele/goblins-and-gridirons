"""Export MaskablePPO actor → ONNX (packed obs + legal rows → chosen row index).

Owner: freeze a profile checkpoint into backend ``resources/ai-opponent/`` for
``ComputerOpponentKind.ONNX``. Soft-mask bias is omitted for product play.
Mirrors deterministic ``MaskableCategoricalDistribution.get_actions`` (masked argmax).
See ``ai/OWNERSHIP.md`` / BACKLOG AI2.6.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
import torch as th
from torch import nn

from gridiron_ai.constants import (
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
from gridiron_ai.features import GridironFeaturesExtractor
from gridiron_ai.paths import AI_ROOT, OUTPUT_DIR

# Backend classpath: ``resources/ai-opponent/{profile}/`` (Maven resources).
BACKEND_AI_OPPONENT_ROOT = AI_ROOT.parent / "backend" / "src" / "main" / "resources" / "ai-opponent"
EXPORTED_DIR = OUTPUT_DIR / "exported"
POLICY_ONNX_NAME = "policy.onnx"
MANIFEST_NAME = "manifest.json"
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
    """SB3 legal-row trunk + masked argmax → Discrete row index (deterministic predict)."""

    def __init__(self, policy: nn.Module) -> None:
        super().__init__()
        if not isinstance(policy.features_extractor, GridironFeaturesExtractor):
            raise TypeError(
                "ONNX export requires GridironFeaturesExtractor "
                f"(got {type(policy.features_extractor)})"
            )
        self.features_extractor = policy.features_extractor
        self.row_scorer = policy.row_scorer

    def forward(self, packed_obs: th.Tensor, legal_moves: th.Tensor) -> th.Tensor:
        # packed_obs: (N, OBSERVATION_VECTOR_LENGTH)
        # legal_moves: (N, MAX_LEGAL_MOVES, FACTORS) — pad rows are -1
        obs = _slice_packed_obs(packed_obs)
        fe = self.features_extractor
        _ = fe(obs)  # stash join board
        board = fe.consume_join_board()
        row_emb = fe.encode_legal_rows(legal_moves, obs[OBS_KEY_CHALLENGES])
        batch, n_rows, _ = row_emb.shape
        board_b = board.unsqueeze(1).expand(batch, n_rows, -1)
        scores = self.row_scorer(th.cat([board_b, row_emb], dim=-1)).squeeze(-1)
        # Pad rows are all-SENTINEL; live rows have kind ≥ 0.
        live = legal_moves[..., 0] >= 0
        scores = scores.masked_fill(~live, -1.0e9)
        return scores.argmax(dim=-1)


def _slice_packed_obs(packed_obs: th.Tensor) -> dict[str, th.Tensor]:
    """Mirror ``obs.obs_flat_to_dict`` in Torch (ONNX-friendly fixed slices)."""
    if packed_obs.dim() != 2 or packed_obs.shape[1] != OBSERVATION_VECTOR_LENGTH:
        raise ValueError(
            f"packed_obs shape {tuple(packed_obs.shape)} != (N, {OBSERVATION_VECTOR_LENGTH})"
        )
    i = 0

    def take(n: int) -> th.Tensor:
        nonlocal i
        chunk = packed_obs[:, i : i + n]
        i += n
        return chunk

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

    pitch_hwc = cells[:, :PITCH_SQUARE_COUNT].reshape(
        -1, PLAY_HEIGHT, PLAY_WIDTH, NUMBERS_PER_BOARD_CELL
    )
    pitch = pitch_hwc.permute(0, 3, 1, 2).contiguous()
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
    return BACKEND_AI_OPPONENT_ROOT / profile


def export_policy_onnx(
    policy: nn.Module,
    *,
    profile: str,
    checkpoint_key: str,
    checkpoint_zip: Path,
    out_dir: Path | None = None,
) -> Path:
    """Write ``policy.onnx`` + ``manifest.json`` to backend ``resources/ai-opponent/{profile}/``."""
    destination = out_dir or export_dir_for(profile)
    destination.mkdir(parents=True, exist_ok=True)
    onnx_path = destination / POLICY_ONNX_NAME

    module = PackedObsAndLegalsToAction(policy).eval()
    dummy_obs = th.zeros((1, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    dummy_legal = th.full((1, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=th.int64)
    dummy_legal[0, 0] = 0
    with th.no_grad():
        action = module(dummy_obs, dummy_legal)
    if action.shape != (1,):
        raise RuntimeError(f"action shape {tuple(action.shape)} != (batch,)")

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

    scratch = EXPORTED_DIR / profile
    scratch.mkdir(parents=True, exist_ok=True)
    (scratch / POLICY_ONNX_NAME).write_bytes(onnx_path.read_bytes())
    scratch_manifest = {**manifest, "checkpointZip": str(checkpoint_zip.resolve())}
    (scratch / MANIFEST_NAME).write_text(json.dumps(scratch_manifest, indent=2) + "\n", encoding="utf-8")
    return destination


def smoke_compare_action(policy: nn.Module, onnx_path: Path) -> None:
    """Fail if ONNX chosen row diverges from Torch masked-argmax on a random batch."""
    import onnxruntime as ort

    module = PackedObsAndLegalsToAction(policy).eval()
    packed = th.randn((2, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    legal = th.full((2, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=th.int64)
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
