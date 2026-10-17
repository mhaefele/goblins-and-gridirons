"""Export MaskablePPO actor → ONNX (packed obs → factor logits).

Owner: freeze a profile checkpoint into backend ``resources/ai-opponent/`` for
``ComputerOpponentKind.ONNX``. Conditional pick→remask stays in Kotlin — this graph
is logits only. See ``ai/OWNERSHIP.md`` / BACKLOG AI2.6.
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
    HELD_CHECK_FIELD_COUNT,
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
INPUT_NAME = "packed_obs"
OUTPUT_NAME = "factor_logits"

# Product blurbs for MatchupSplash / GET /api/computer-opponents (written into manifest).
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


class PackedObsToFactorLogits(nn.Module):
    """SB3 policy trunk: flat packed obs → concatenated MultiDiscrete logits."""

    def __init__(self, policy: nn.Module) -> None:
        super().__init__()
        self.features_extractor = policy.features_extractor
        self.mlp_extractor = policy.mlp_extractor
        self.action_net = policy.action_net

    def forward(self, packed_obs: th.Tensor) -> th.Tensor:
        # packed_obs: (N, OBSERVATION_VECTOR_LENGTH)
        obs = _slice_packed_obs(packed_obs)
        features = self.features_extractor(obs)
        latent_pi, _latent_vf = self.mlp_extractor(features)
        return self.action_net(latent_pi)


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

    module = PackedObsToFactorLogits(policy).eval()
    dummy = th.zeros((1, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    with th.no_grad():
        logits = module(dummy)
    expected_logit_width = int(sum(ACTION_HEAD_SIZES))
    if int(logits.shape[-1]) != expected_logit_width:
        raise RuntimeError(
            f"action_net width {int(logits.shape[-1])} != sum(ACTION_HEAD_SIZES)={expected_logit_width}"
        )

    th.onnx.export(
        module,
        dummy,
        str(onnx_path),
        input_names=[INPUT_NAME],
        output_names=[OUTPUT_NAME],
        dynamic_axes={INPUT_NAME: {0: "batch"}, OUTPUT_NAME: {0: "batch"}},
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
        "actionHeadSizes": list(ACTION_HEAD_SIZES),
        "logitWidth": expected_logit_width,
        "inputName": INPUT_NAME,
        "outputName": OUTPUT_NAME,
        "onnxFile": POLICY_ONNX_NAME,
    }
    # Classpath manifest ships with policy.onnx. The training zip is gitignored, so its path
    # stays on the scratch copy under output/exported/ only.
    (destination / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    scratch = EXPORTED_DIR / profile
    scratch.mkdir(parents=True, exist_ok=True)
    (scratch / POLICY_ONNX_NAME).write_bytes(onnx_path.read_bytes())
    scratch_manifest = {**manifest, "checkpointZip": str(checkpoint_zip.resolve())}
    (scratch / MANIFEST_NAME).write_text(json.dumps(scratch_manifest, indent=2) + "\n", encoding="utf-8")
    return destination


def smoke_compare_logits(policy: nn.Module, onnx_path: Path, *, rtol: float = 1e-4) -> None:
    """Fail if ONNX logits diverge from Torch on a random packed obs."""
    import onnxruntime as ort

    module = PackedObsToFactorLogits(policy).eval()
    packed = th.randn((2, OBSERVATION_VECTOR_LENGTH), dtype=th.float32)
    with th.no_grad():
        torch_logits = module(packed).numpy()

    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    onnx_logits = session.run(None, {INPUT_NAME: packed.numpy()})[0]
    if not np.allclose(torch_logits, onnx_logits, rtol=rtol, atol=1e-4):
        max_abs = float(np.max(np.abs(torch_logits - onnx_logits)))
        raise RuntimeError(f"ONNX logits diverge from Torch (max abs {max_abs})")
