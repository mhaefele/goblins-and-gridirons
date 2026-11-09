"""Join-score policy: rank this step's legal rows given the board (schema 16).

Owner: one score per packed legal row = ``f(board_latent, encode(row))``.
Row features use the same emb/coord style as the board trunk
(``GridironFeaturesExtractor.encode_legal_rows``). Gym action is
``Discrete(MAX_LEGAL_MOVES)``. Soft-mask adds a logit bias on preferred rows.
Encode+score truncates to this batch's max ``legal_move_count`` (pad tail → ``-1e8``).

MaskablePPO → ``forward`` (rollouts) / ``evaluate_actions`` (PPO + clone);
pick via stock ``MaskableCategoricalDistribution.get_actions``.
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
    LEGAL_ROW_SCORE_WIDTH,
    OBS_KEY_CHALLENGES,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
    SOFT_MASK_LOGIT_BIAS,
)
from gridiron_ai.features import LEGAL_ROW_FEATURE_DIM, GridironFeaturesExtractor

# Join MLP width — 256 so ball/spatial row bits have more room than the old 128 bottleneck.
_SCORE_HIDDEN = 256


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
        # Train-segment knob (e.g. bottleneck ×2). Plain attr — not a buffer/parameter, not in
        # observation_space (SB3 would pickle a changed Box into the zip and break resume).
        self.soft_mask_strength = 1.0
        if not self.share_features_extractor:
            raise ValueError(
                "LegalRowActorCriticPolicy requires share_features_extractor=True "
                "(one GridironFeaturesExtractor shared by actor and critic)"
            )
        if not isinstance(self.features_extractor, GridironFeaturesExtractor):
            raise TypeError(
                "LegalRowActorCriticPolicy requires GridironFeaturesExtractor "
                f"(got {type(self.features_extractor)})"
            )
        # Stock action_net is Linear(latent, MAX_LEGAL) — unused; scores come from the join MLP.
        latent_dim = int(self.mlp_extractor.latent_dim_pi)
        self.row_scorer = nn.Sequential(
            nn.Linear(latent_dim + LEGAL_ROW_FEATURE_DIM, _SCORE_HIDDEN),
            nn.ReLU(),
            nn.Linear(_SCORE_HIDDEN, 1),
        )

    def forward(
        self,
        obs: th.Tensor,
        deterministic: bool = False,
        action_masks: np.ndarray | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor]:
        # OVERRIDE: custom row logits; pick via stock MaskableCategoricalDistribution.
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
        """Join-score legal rows; return width ``LEGAL_ROW_SCORE_WIDTH``.

        Obs still pads to ``MAX_LEGAL_MOVES``, but encode+score only run through this
        batch's busiest menu (``legal_move_count.max()``). Tail logits stay ``-1e8`` —
        same end state as scoring pads then masking, without paying for empty slots.
        """
        if not (isinstance(obs, dict) or hasattr(obs, "keys")):
            raise TypeError(f"Expected Dict observation with legal moves, got {type(obs)}")
        legal_moves = obs[OBS_KEY_LEGAL_MOVES]
        legal_count = obs[OBS_KEY_LEGAL_MOVE_COUNT]
        challenges = obs[OBS_KEY_CHALLENGES]
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
        if not isinstance(challenges, th.Tensor):
            challenges = th.as_tensor(challenges, device=latent_pi.device)
        else:
            challenges = challenges.to(device=latent_pi.device)

        fe = self.features_extractor
        assert isinstance(fe, GridironFeaturesExtractor)
        batch = int(latent_pi.shape[0])
        pad_rows = int(legal_moves.shape[1])
        logits = latent_pi.new_full((batch, LEGAL_ROW_SCORE_WIDTH), -1.0e8)
        if batch == 0 or pad_rows == 0:
            return logits
        max_count = int(legal_count.max().item()) if legal_count.numel() else 0
        max_count = max(0, min(max_count, pad_rows, LEGAL_ROW_SCORE_WIDTH))
        if max_count == 0:
            return logits

        live_moves = legal_moves[:, :max_count]
        row_emb = fe.encode_legal_rows(live_moves, challenges.float())
        lat = latent_pi.unsqueeze(1).expand(batch, max_count, -1)
        live_logits = self.row_scorer(th.cat([lat, row_emb], dim=-1)).squeeze(-1)

        if soft is not None:
            if not isinstance(soft, th.Tensor):
                soft = th.as_tensor(soft, device=live_logits.device)
            else:
                soft = soft.to(device=live_logits.device)
            # Obs flags are 0/1; juice is soft_mask_strength (train knob), not the gym Box.
            soft = soft.reshape(batch, -1)[:, :max_count].to(dtype=live_logits.dtype)
            bias = SOFT_MASK_LOGIT_BIAS * float(self.soft_mask_strength)
            live_logits = live_logits + soft * bias

        row_ids = th.arange(max_count, device=live_logits.device).unsqueeze(0)
        valid = row_ids < legal_count.unsqueeze(1)
        live_logits = live_logits.masked_fill(~valid, -1.0e8)
        logits[:, :max_count] = live_logits
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
