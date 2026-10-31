"""Join-score policy: rank this step's legal rows given the board (schema 16).

Owner: one score per packed legal row = ``f(board_latent, encode(row))``.
Gym action is ``Discrete(MAX_LEGAL_MOVES)``. Soft-mask adds a logit bias on
preferred rows. Replaces factor MultiDiscrete + remask (``conditional_policy``).

MaskablePPO → ``forward`` (rollouts) / ``evaluate_actions`` (PPO + clone).
"""

from __future__ import annotations

from typing import Any

import numpy as np
import torch as th
from gymnasium import spaces
from sb3_contrib.common.maskable.distributions import MaskableCategoricalDistribution
from sb3_contrib.common.maskable.policies import MaskableActorCriticPolicy
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor
from stable_baselines3.common.type_aliases import PyTorchObs
from torch import nn

from gridiron_ai.constants import (
    ACTION_HEAD_SIZES,
    LEGAL_ROW_SCORE_WIDTH,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
    SOFT_MASK_LOGIT_BIAS,
)
from gridiron_ai.features import GridironFeaturesExtractor

_ROW_EMB_AK = 32
_ROW_EMB_TARGET = 32
_ROW_EMB_CHALLENGE = 16
_ROW_EMB_DIM = _ROW_EMB_AK + _ROW_EMB_TARGET + _ROW_EMB_CHALLENGE
_SCORE_HIDDEN = 128


class LegalRowActorCriticPolicy(MaskableActorCriticPolicy):
    """MaskablePPO policy that scores legal rows joined to the board latent."""

    def __init__(
        self,
        observation_space: spaces.Space,
        action_space: spaces.Space,
        lr_schedule: Any,
        *,
        features_extractor_class: type[BaseFeaturesExtractor] = GridironFeaturesExtractor,
        **kwargs: Any,
    ) -> None:
        if not isinstance(observation_space, spaces.Dict):
            raise TypeError(
                "LegalRowActorCriticPolicy requires Dict observation_space "
                f"(game-state keys + legal_moves); got {type(observation_space)}"
            )
        if not isinstance(action_space, spaces.Discrete):
            raise TypeError(
                "LegalRowActorCriticPolicy requires Discrete action_space "
                f"(legal-row index); got {type(action_space)}"
            )
        if int(action_space.n) != LEGAL_ROW_SCORE_WIDTH:
            raise ValueError(
                f"Discrete.n={action_space.n} != LEGAL_ROW_SCORE_WIDTH={LEGAL_ROW_SCORE_WIDTH}"
            )
        super().__init__(
            observation_space,
            action_space,
            lr_schedule,
            features_extractor_class=features_extractor_class,
            **kwargs,
        )
        if not self.share_features_extractor:
            raise ValueError(
                "LegalRowActorCriticPolicy requires share_features_extractor=True "
                "(one GridironFeaturesExtractor shared by actor and critic)"
            )
        # Stock action_net is Linear(latent, MAX_LEGAL) — unused; scores come from the join MLP.
        self.actor_kind_emb = nn.Embedding(ACTION_HEAD_SIZES[0], _ROW_EMB_AK)
        self.target_emb = nn.Embedding(ACTION_HEAD_SIZES[1], _ROW_EMB_TARGET)
        self.challenge_emb = nn.Embedding(ACTION_HEAD_SIZES[2], _ROW_EMB_CHALLENGE)
        latent_dim = int(self.mlp_extractor.latent_dim_pi)
        self.row_scorer = nn.Sequential(
            nn.Linear(latent_dim + _ROW_EMB_DIM, _SCORE_HIDDEN),
            nn.ReLU(),
            nn.Linear(_SCORE_HIDDEN, 1),
        )

    def forward(
        self,
        obs: th.Tensor,
        deterministic: bool = False,
        action_masks: np.ndarray | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor]:
        # OVERRIDE: score legal rows, then masked sample (not stock action_net logits).
        values, logits = self._board_and_row_logits(obs)
        distribution = self._masked_distribution(logits, action_masks)
        actions = distribution.get_actions(deterministic=deterministic)
        log_prob = distribution.log_prob(actions)
        return actions.reshape((-1,)), values, log_prob

    def evaluate_actions(
        self,
        obs: th.Tensor,
        actions: th.Tensor,
        action_masks: th.Tensor | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor | None]:
        # OVERRIDE: PPO / clone — score stored row indices.
        values, logits = self._board_and_row_logits(obs)
        distribution = self._masked_distribution(logits, action_masks)
        flat_actions = actions.reshape((-1,)).long()
        log_prob = distribution.log_prob(flat_actions)
        entropy = distribution.entropy()
        return values, log_prob, entropy

    def predict(
        self,
        observation: th.Tensor | dict[str, th.Tensor] | np.ndarray | dict[str, np.ndarray],
        state: tuple[np.ndarray, ...] | None = None,
        episode_start: np.ndarray | None = None,
        deterministic: bool = False,
        action_masks: np.ndarray | None = None,
    ) -> tuple[np.ndarray, tuple[np.ndarray, ...] | None]:
        # OVERRIDE: same path as rollouts (stock Maskable uses distribution.get_actions).
        del episode_start
        self.set_training_mode(False)
        obs_tensor, vectorized_env = self.obs_to_tensor(observation)
        with th.no_grad():
            actions, _values, _log_prob = self.forward(
                obs_tensor,
                deterministic=deterministic,
                action_masks=action_masks,
            )
        action_np = actions.cpu().numpy().reshape((-1,))
        if not vectorized_env:
            action_np = action_np.squeeze(axis=0)
        return action_np, state

    def _board_and_row_logits(self, obs: PyTorchObs) -> tuple[th.Tensor, th.Tensor]:
        features = self.extract_features(obs)
        latent_pi, latent_vf = self.mlp_extractor(features)
        values = self.value_net(latent_vf)
        logits = self._score_legal_rows(latent_pi, obs)
        return values, logits

    def _score_legal_rows(self, latent_pi: th.Tensor, obs: PyTorchObs) -> th.Tensor:
        if not (isinstance(obs, dict) or hasattr(obs, "keys")):
            raise TypeError(f"Expected Dict observation with legal moves, got {type(obs)}")
        legal_moves = obs[OBS_KEY_LEGAL_MOVES]
        legal_count = obs[OBS_KEY_LEGAL_MOVE_COUNT]
        try:
            soft = obs[OBS_KEY_SOFT_MASK_LEGAL]
        except (KeyError, TypeError):
            soft = None

        if not isinstance(legal_moves, th.Tensor):
            legal_moves = th.as_tensor(legal_moves, device=latent_pi.device)
        else:
            legal_moves = legal_moves.to(device=latent_pi.device)
        if not isinstance(legal_count, th.Tensor):
            legal_count = th.as_tensor(legal_count, device=latent_pi.device)
        else:
            legal_count = legal_count.to(device=latent_pi.device)
        legal_count = legal_count.reshape(-1).long()

        # Pad sentinels are -1; clamp for embedding, then mask those slots out.
        clamped = legal_moves.long().clamp(min=0)
        ak = self.actor_kind_emb(clamped[..., 0])
        tgt = self.target_emb(clamped[..., 1])
        ch = self.challenge_emb(clamped[..., 2])
        row_emb = th.cat([ak, tgt, ch], dim=-1)
        batch, n_rows, _ = row_emb.shape
        lat = latent_pi.unsqueeze(1).expand(batch, n_rows, -1)
        logits = self.row_scorer(th.cat([lat, row_emb], dim=-1)).squeeze(-1)

        row_ids = th.arange(n_rows, device=logits.device).unsqueeze(0)
        valid = row_ids < legal_count.unsqueeze(1)
        logits = logits.masked_fill(~valid, -1.0e8)

        if soft is not None:
            if not isinstance(soft, th.Tensor):
                soft = th.as_tensor(soft, device=logits.device)
            else:
                soft = soft.to(device=logits.device)
            soft = soft.reshape(batch, n_rows) > 0
            logits = logits + soft.to(dtype=logits.dtype) * SOFT_MASK_LOGIT_BIAS
        return logits

    def _masked_distribution(
        self,
        logits: th.Tensor,
        action_masks: np.ndarray | th.Tensor | None,
    ) -> MaskableCategoricalDistribution:
        distribution = MaskableCategoricalDistribution(LEGAL_ROW_SCORE_WIDTH)
        distribution.proba_distribution(action_logits=logits)
        if action_masks is not None:
            distribution.apply_masking(action_masks)
        return distribution
