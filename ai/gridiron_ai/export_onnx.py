"""Export MaskablePPO actor → ONNX (packed obs + legal rows → row scores).

Owner: freeze a profile checkpoint into backend ``resources/ai-opponent/`` for
``ComputerOpponentKind.ONNX``. Soft-mask bias is omitted for product play.
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
    ACTION_HEAD_SIZES,
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
from gridiron_ai.paths import AI_ROOT, OUTPUT_DIR

# Backend classpath: ``resources/ai-opponent/{profile}/`` (Maven resources).
BACKEND_AI_OPPONENT_ROOT = AI_ROOT.parent / "backend" / "src" / "main" / "resources" / "ai-opponent"
EXPORTED_DIR = OUTPUT_DIR / "exported"
POLICY_ONNX_NAME = "policy.onnx"
MANIFEST_NAME = "manifest.json"
INPUT_OBS_NAME = "packed_obs"
INPUT_LEGAL_NAME = "legal_moves"
OUTPUT_NAME = "row_scores"

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
    "defense": "Trained to contest the ball and punish carriers.",
}


class PackedObsAndLegalsToRowScores(nn.Module):
    """SB3 legal-row policy trunk: packed obs + padded legal matrix → row scores."""

    def __init__(self, policy: nn.Module) -> None:
        super().__init__()
        self.features_extractor = policy.features_extractor
        self.mlp_extractor = policy.mlp_extractor
        self.actor_kind_emb = policy.actor_kind_emb
        self.target_emb = policy.target_emb
        self.challenge_emb = policy.challenge_emb
        self.row_scorer = policy.row_scorer

    def forward(self, packed_obs: th.Tensor, legal_moves: th.Tensor) -> th.Tensor:
        # packed_obs: (N, OBSERVATION_VECTOR_LENGTH)
        # legal_moves: (N, MAX_LEGAL_MOVES, FACTORS) — pad rows may be -1
        obs = _slice_packed_obs(packed_obs)
        features = self.features_extractor(obs)
        latent_pi, _latent_vf = self.mlp_extractor(features)
        clamped = legal_moves.long().clamp(min=0)
        row_emb = th.cat(
            [
                self.actor_kind_emb(clamped[..., 0]),
                self.target_emb(clamped[..., 1]),
                self.challenge_emb(clamped[..., 2]),
            ],
            dim=-1,
        )
        batch, n_rows, _ = row_emb.shape
        lat = latent_pi.unsqueeze(1).expand(batch, n_rows, -1)
        return self.row_scorer(th.cat([lat, row_emb], dim=-1)).squeeze(-1)


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

    module = PackedObsAndLegalsToRowScores(policy).eval()
    dummy_obs = th.zeros((1, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    dummy_legal = th.zeros((1, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), dtype=th.int64)
    with th.no_grad():
        scores = module(dummy_obs, dummy_legal)
    if int(scores.shape[-1]) != LEGAL_ROW_SCORE_WIDTH:
        raise RuntimeError(
            f"row score width {int(scores.shape[-1])} != LEGAL_ROW_SCORE_WIDTH={LEGAL_ROW_SCORE_WIDTH}"
        )

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
        "actionHeadSizes": list(ACTION_HEAD_SIZES),
        "logitWidth": LEGAL_ROW_SCORE_WIDTH,
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


def smoke_compare_logits(policy: nn.Module, onnx_path: Path, *, rtol: float = 1e-4) -> None:
    """Fail if ONNX row scores diverge from Torch on a random packed obs + legals."""
    import onnxruntime as ort

    module = PackedObsAndLegalsToRowScores(policy).eval()
    packed = th.randn((2, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    legal = th.randint(0, 3, (2, MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), dtype=th.int64)
    with th.no_grad():
        torch_scores = module(packed, legal).numpy()

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_scores = session.run(
        None,
        {INPUT_OBS_NAME: packed.numpy(), INPUT_LEGAL_NAME: legal.numpy()},
    )[0]
    if not np.allclose(torch_scores, onnx_scores, rtol=rtol, atol=1e-4):
        max_abs = float(np.max(np.abs(torch_scores - onnx_scores)))
        raise RuntimeError(f"ONNX row scores diverge from Torch (max abs {max_abs})")
