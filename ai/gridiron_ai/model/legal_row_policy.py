"""Policy that scores every legal menu row against the board via cross-attn.

Owner: ``row_scorer(cross_attn(map, row) ‖ non_pitch ‖ encode(row))``.

How it fits Stable-Baselines3
-----------------------------
Stock MaskablePPO expects a Discrete action = "which of N fixed buttons?".
Our N is ``MAX_LEGAL_MOVES`` (1024): an index into **this step's** legal menu.

Stock would do ``Linear(board_summary → 1024)``. We instead:

1. Let SB3 build the **value** estimate from the usual 512-d features vector.
2. Separately score each live legal row: the row queries all pitch CNN tokens
   (cross-attn), then glue ``ctx ‖ non_pitch ‖ row_emb`` into ``row_scorer``.
3. Soft-mask (curriculum "look at these rows") is only a post-score bias —
   not a feature and not a reward.

See ``ai/PPO_STRATEGY.md``.
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

from gridiron_ai.model.constants import (
    LEGAL_ROW_SCORE_WIDTH,
    OBS_KEY_CHALLENGES,
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
    PLAY_HEIGHT,
    PLAY_WIDTH,
    SOFT_MASK_LOGIT_BIAS,
)
from gridiron_ai.model.features import (
    JOIN_CELL_FEAT,
    JOIN_SCORER_IN,
    LEGAL_ROW_FEATURE_DIM,
    MoveBoardCrossAttention,
    GridironFeaturesExtractor,
    _PROJECTED_FEATURES,
    _non_pitch_width,
)

_SCORE_HIDDEN = 512
# DEVIATION: stock SB3 ``net_arch`` is ``pi/vf=[64,64]``; empty keeps value features intact.
POLICY_NET_ARCH: dict[str, list[int]] = dict(pi=[], vf=[])


def maskable_ppo_policy_kwargs() -> dict[str, Any]:
    """Kwargs passed into MaskablePPO so create/resume share the same architecture knobs."""
    return {
        "net_arch": POLICY_NET_ARCH,
        "features_extractor_kwargs": {"features_dim": _PROJECTED_FEATURES},
    }


class LegalRowActorCriticPolicy(MaskableActorCriticPolicy):
    """MaskablePPO policy: cross-attend each legal row over the pitch map, then score."""

    def __init__(
        self,
        observation_space: spaces.Space,
        action_space: spaces.Space,
        lr_schedule: Any,
        *,
        features_extractor_class: type[BaseFeaturesExtractor] = GridironFeaturesExtractor,
        **kwargs: Any,
    ) -> None:
        """Build the stock Maskable actor-critic, then replace action scoring with join MLP.

        Requires a Dict observation space, Discrete(1024) actions, and our
        ``GridironFeaturesExtractor`` (shared between actor and critic).
        """
        if not isinstance(observation_space, spaces.Dict):
            raise TypeError(
                "LegalRowActorCriticPolicy requires Dict observation_space "
                f"(got {type(observation_space)})"
            )
        if not isinstance(action_space, spaces.Discrete):
            raise TypeError(
                "LegalRowActorCriticPolicy requires Discrete action_space "
                f"(got {type(action_space)})"
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
        # Curriculum knob: 1.0 = full soft-mask bias; 0.0 = off (eval / product).
        self.soft_mask_strength = 1.0
        if not self.share_features_extractor:
            raise ValueError("LegalRowActorCriticPolicy requires share_features_extractor=True")
        if not isinstance(self.features_extractor, GridironFeaturesExtractor):
            raise TypeError(
                "LegalRowActorCriticPolicy requires GridironFeaturesExtractor "
                f"(got {type(self.features_extractor)})"
            )
        # Stock ``action_net`` (Linear → 1024) is unused — scores come from attn + join MLP.
        self.move_board_attn = MoveBoardCrossAttention(
            cell_dim=JOIN_CELL_FEAT,
            row_dim=LEGAL_ROW_FEATURE_DIM,
        )
        self.row_scorer = nn.Sequential(
            nn.Linear(JOIN_SCORER_IN, _SCORE_HIDDEN),
            nn.ReLU(),
            nn.Linear(_SCORE_HIDDEN, 1),
        )

    def forward(
        self,
        obs: th.Tensor,
        deterministic: bool = False,
        action_masks: np.ndarray | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor]:
        """Training/rollout step: pick a legal-row index, return value + log-prob.

        Returns ``(actions, values, log_prob)`` — the SB3 actor-critic contract.
        """
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
        """PPO update path: re-score stored actions for log-prob and entropy.

        Same board/row scoring as ``forward``, but uses the actions already in
        the rollout buffer instead of sampling new ones.
        """
        values, logits = self._board_and_row_logits(obs)
        distribution = self._masked_distribution(logits, action_masks)
        flat_actions = actions.reshape((-1,)).long()
        return values, distribution.log_prob(flat_actions), distribution.entropy()

    def predict(
        self,
        observation: th.Tensor | dict[str, th.Tensor] | np.ndarray | dict[str, np.ndarray],
        state: tuple[np.ndarray, ...] | None = None,
        episode_start: np.ndarray | None = None,
        deterministic: bool = False,
        action_masks: np.ndarray | None = None,
    ) -> tuple[np.ndarray, tuple[np.ndarray, ...] | None]:
        """Inference helper used by eval / play scripts (NumPy in, NumPy out)."""
        del episode_start
        self.set_training_mode(False)
        obs_tensor, vectorized_env = self.obs_to_tensor(observation)
        with th.no_grad():
            actions, _values, _log_prob = self.forward(
                obs_tensor, deterministic=deterministic, action_masks=action_masks
            )
        action_np = actions.cpu().numpy().reshape((-1,))
        if not vectorized_env:
            action_np = action_np.squeeze(axis=0)
        return action_np, state

    def _board_and_row_logits(self, obs: PyTorchObs) -> tuple[th.Tensor, th.Tensor]:
        """Run the value head and the legal-row scorer on one observation batch.

        ``extract_features`` fills the feature extractor's temporary board save;
        ``_score_legal_rows`` consumes it. Returns ``(values, logits)`` where
        logits are one score per Discrete index (pad / illegal → huge negative).
        """
        features = self.extract_features(obs)
        # Empty pi MLP: latent_pi is unused; latent_vf feeds the critic.
        _latent_pi, latent_vf = self.mlp_extractor(features)
        values = self.value_net(latent_vf)
        logits = self._score_legal_rows(obs)
        return values, logits

    def _score_legal_rows(self, obs: PyTorchObs) -> th.Tensor:
        """Score each live legal menu row; leave pad slots at −1e8.

        Reads the pitch map + non-pitch from the feature extractor's temporary
        save (filled by ``extract_features`` / ``forward`` just before). Encodes
        only up to this batch's max live row count so we don't waste work on pad.
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

        fe = self.features_extractor
        assert isinstance(fe, GridironFeaturesExtractor)
        device = next(self.row_scorer.parameters()).device
        if not isinstance(legal_moves, th.Tensor):
            legal_moves = th.as_tensor(legal_moves, device=device)
        else:
            legal_moves = legal_moves.to(device=device)
        if not isinstance(legal_count, th.Tensor):
            legal_count = th.as_tensor(legal_count, device=device)
        else:
            legal_count = legal_count.to(device=device)
        legal_count = legal_count.reshape(-1).long()
        if not isinstance(challenges, th.Tensor):
            challenges = th.as_tensor(challenges, device=device)
        else:
            challenges = challenges.to(device=device)

        batch = int(legal_moves.shape[0])
        pad_rows = int(legal_moves.shape[1])
        # Default every Discrete slot to "illegal" until we fill live scores.
        logits = legal_moves.new_full((batch, LEGAL_ROW_SCORE_WIDTH), -1.0e8).float()
        # Unit tests may call this without extract_features — invent a zero board.
        if fe._pitch_feature_map is None or fe._non_pitch_features is None:
            fe._pitch_feature_map = logits.new_zeros(batch, JOIN_CELL_FEAT, PLAY_HEIGHT, PLAY_WIDTH)
            fe._non_pitch_features = logits.new_zeros(batch, _non_pitch_width())
        pitch_map, non_pitch = fe.consume_join_board()
        if batch == 0 or pad_rows == 0:
            return logits
        max_count = int(legal_count.max().item()) if legal_count.numel() else 0
        max_count = max(0, min(max_count, pad_rows, LEGAL_ROW_SCORE_WIDTH))
        if max_count == 0:
            return logits

        # Encode + score only the prefix that might be live for someone in the batch.
        live_moves = legal_moves[:, :max_count]
        row_emb = fe.encode_legal_rows(live_moves, challenges.float())
        board_ctx = self.move_board_attn(pitch_map, row_emb)
        non_pitch_b = non_pitch.unsqueeze(1).expand(batch, max_count, -1)
        live_logits = self.row_scorer(th.cat([board_ctx, non_pitch_b, row_emb], dim=-1)).squeeze(-1)

        # Soft-mask: add a fixed bias to preferred rows (sampling tilt, not a grade).
        if soft is not None:
            if not isinstance(soft, th.Tensor):
                soft = th.as_tensor(soft, device=live_logits.device)
            else:
                soft = soft.to(device=live_logits.device)
            soft = soft.reshape(batch, -1)[:, :max_count].to(dtype=live_logits.dtype)
            live_logits = live_logits + soft * (SOFT_MASK_LOGIT_BIAS * float(self.soft_mask_strength))

        # Per-env legal_count may be shorter than batch max — re-mask the tail.
        row_ids = th.arange(max_count, device=live_logits.device).unsqueeze(0)
        live_logits = live_logits.masked_fill(~(row_ids < legal_count.unsqueeze(1)), -1.0e8)
        logits[:, :max_count] = live_logits
        return logits

    def _masked_distribution(
        self,
        logits: th.Tensor,
        action_masks: np.ndarray | th.Tensor | None,
    ) -> MaskableCategoricalDistribution:
        """Wrap row scores as an SB3-Contrib categorical that respects hard legality masks."""
        distribution = MaskableCategoricalDistribution(LEGAL_ROW_SCORE_WIDTH)
        distribution.proba_distribution(action_logits=logits)
        if action_masks is not None:
            distribution.apply_masking(action_masks)
        return distribution
