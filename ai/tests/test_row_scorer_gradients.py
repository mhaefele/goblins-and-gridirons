"""Diagnostic: can ``row_scorer`` learn from ``targetHasBall`` alone?

Isolated procedural check — does not change training. Builds a tiny 2-state /
2-row menu where the *only* discriminating signal is the packed targetHasBall
bit, then Adam + CE for 50 steps. Loss→0 means gradients can flow from that
feature into action selection; flat loss would implicate the architecture.
"""

from __future__ import annotations

import numpy as np
import torch as th
from gymnasium import spaces
from torch import nn

from gridiron_ai.actions import FACTOR_TARGET_HAS_BALL
from gridiron_ai.constants import (
    FACTORS_PER_LEGAL_MOVE,
    LEGAL_ROW_SCORE_WIDTH,
    MAX_LEGAL_MOVES,
    OBS_KEY_BENCHES,
    OBS_KEY_CHALLENGES,
    OBS_KEY_DECK,
    OBS_KEY_HAND,
    OBS_KEY_HELD_CHECK,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_PITCH,
    OBS_KEY_SCALARS,
    OBS_KEY_SOFT_MASK_LEGAL,
)
from gridiron_ai.features import EMBED_DIM, GridironFeaturesExtractor
from gridiron_ai.legal_row_policy import LegalRowActorCriticPolicy
from gridiron_ai.obs import dict_observation_space, empty_step_observation

# encode_legal_rows concat: actor(8) + kind(8) + target(3) + source(3) + actorHasBall + targetHasBall
_TARGET_HAS_BALL_FEAT = EMBED_DIM + EMBED_DIM + 3 + 3 + 1


def _noise_board(rng: np.random.Generator) -> dict[str, np.ndarray]:
    base = empty_step_observation()
    base[OBS_KEY_PITCH] = rng.standard_normal(base[OBS_KEY_PITCH].shape).astype(np.float32)
    base[OBS_KEY_BENCHES] = rng.standard_normal(base[OBS_KEY_BENCHES].shape).astype(np.float32)
    base[OBS_KEY_SCALARS] = rng.standard_normal(base[OBS_KEY_SCALARS].shape).astype(np.float32)
    base[OBS_KEY_CHALLENGES] = rng.random(base[OBS_KEY_CHALLENGES].shape).astype(np.float32)
    base[OBS_KEY_DECK] = rng.random(base[OBS_KEY_DECK].shape).astype(np.float32)
    base[OBS_KEY_HAND] = rng.random(base[OBS_KEY_HAND].shape).astype(np.float32)
    base[OBS_KEY_HELD_CHECK] = rng.random(base[OBS_KEY_HELD_CHECK].shape).astype(np.float32)
    return base


def _two_row_menu(*, ball_on_row: int, rng: np.random.Generator) -> np.ndarray:
    """Two identical packed rows except ``targetHasBall`` on ``ball_on_row``."""
    legal = np.full((MAX_LEGAL_MOVES, FACTORS_PER_LEGAL_MOVE), -1, dtype=np.int64)
    shared = rng.integers(0, 5, size=(FACTORS_PER_LEGAL_MOVE,), dtype=np.int64)
    shared[4:9] = -1  # challenge / bonus / faces unused
    for row_i in range(2):
        legal[row_i] = shared
        legal[row_i, FACTOR_TARGET_HAS_BALL] = 1 if row_i == ball_on_row else 0
    return legal


def _stack_obs(batch: list[dict[str, np.ndarray]]) -> dict[str, th.Tensor]:
    keys = batch[0].keys()
    return {key: th.as_tensor(np.stack([item[key] for item in batch])) for key in keys}


def test_row_scorer_learns_target_has_ball_toggle() -> None:
    rng = np.random.default_rng(0)
    th.manual_seed(0)

    obs_space = dict_observation_space()
    policy = LegalRowActorCriticPolicy(
        obs_space,
        spaces.Discrete(LEGAL_ROW_SCORE_WIDTH),
        lr_schedule=lambda _: 1e-2,
        features_extractor_class=GridironFeaturesExtractor,
        net_arch=dict(pi=[32], vf=[32]),
    )
    policy.soft_mask_strength = 0.0
    policy.train()

    # State 0 → pick row 0 (ball flag on 0); state 1 → pick row 1 (ball flag on 1).
    samples = []
    for ball_row, _label in ((0, 0), (1, 1)):
        step = _noise_board(rng)
        step[OBS_KEY_LEGAL_MOVES] = _two_row_menu(ball_on_row=ball_row, rng=rng)
        step[OBS_KEY_LEGAL_MOVE_COUNT] = np.asarray([2], dtype=np.int32)
        step[OBS_KEY_SOFT_MASK_LEGAL] = np.zeros(MAX_LEGAL_MOVES, dtype=np.int8)
        samples.append(step)
    labels = th.tensor([0, 1], dtype=th.long)
    obs = _stack_obs(samples)

    optimizer = th.optim.Adam(policy.parameters(), lr=1e-2)
    loss_fn = nn.CrossEntropyLoss()
    latent_dim = int(policy.mlp_extractor.latent_dim_pi)
    ball_weight_col = latent_dim + _TARGET_HAS_BALL_FEAT
    first_linear = policy.row_scorer[0]
    assert isinstance(first_linear, nn.Linear)

    last_loss = None
    for step in range(50):
        optimizer.zero_grad(set_to_none=True)
        _values, logits = policy._board_and_row_logits(obs)
        # CE over the live 2-row menu only (pad logits are -1e8).
        loss = loss_fn(logits[:, :2], labels)
        loss.backward()

        if step in (0, 49):
            grad = first_linear.weight.grad
            assert grad is not None
            ball_grad = float(grad[:, ball_weight_col].abs().mean().item())
            print(
                f"iter={step} loss={float(loss.item()):.6f} "
                f"logits0={logits[0, :2].detach().tolist()} "
                f"logits1={logits[1, :2].detach().tolist()} "
                f"ball_flag_grad_mag={ball_grad:.6e}"
            )

        optimizer.step()
        last_loss = float(loss.item())

    assert last_loss is not None
    assert last_loss < 0.05, f"row_scorer failed to fit targetHasBall toggle; loss={last_loss}"
