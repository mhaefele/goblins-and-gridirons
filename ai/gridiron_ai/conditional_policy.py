"""Conditional factor policy for stock MaskablePPO (AI2.9).

Owner: pick → remask → pick over ``obs["legal_moves"]`` (+ soft-mask logit bias).
Uses ``actions`` helpers + ``features`` extractor. Not the stock flat ``action_masks``
path. See ``ACTION_SAMPLING.md`` / ``ai/OWNERSHIP.md``.

MaskablePPO → ``forward`` (rollouts) / ``evaluate_actions`` (PPO loss).
"""

from __future__ import annotations

from typing import Any

import numpy as np
import torch as th
from gymnasium import spaces
from sb3_contrib.common.maskable.distributions import MaskableCategorical, MaskableDistribution
from sb3_contrib.common.maskable.policies import MaskableActorCriticPolicy
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor
from stable_baselines3.common.type_aliases import PyTorchObs
from torch import nn

from gridiron_ai.actions import (
    CONDITIONAL_SAMPLE_ORDER,
    action_head_sizes,
    conditional_factor_mask,
    preferred_factor_values,
)
from gridiron_ai.constants import (
    OBS_KEY_LEGAL_MOVE_COUNT,
    OBS_KEY_LEGAL_MOVES,
    OBS_KEY_SOFT_MASK_LEGAL,
    SOFT_MASK_LOGIT_BIAS,
)
from gridiron_ai.features import GridironFeaturesExtractor

# obs (observation) from GridironEnv is a Dict:
#   scalars / challenges / deck / hand / pitch / benches — game state for the extractor
#   legal_moves       — int64 (MAX_LEGAL, FACTORS) encoded legal rows; unused rows are -1
#   legal_move_count  — how many leading rows in legal_moves are real this step
#   soft_mask_legal   — int8 (MAX_LEGAL,) 1 = softMaskScenarioMove (logit bias only)
# SB3 stacks N parallel env steps → leading dimension N on every tensor above.


class ConditionalMultiDiscreteDistribution(MaskableDistribution):
    """SB3 action head: one linear layer → factor logits (one per MultiDiscrete axis).

    SB3 calls ``proba_distribution_net`` when the policy is constructed and
    ``proba_distribution`` via ``policy._get_action_dist_from_latent``. We read the
    resulting per-factor logits in ``ConditionalActorCriticPolicy._pick_factors_*``.
    """

    def __init__(self, action_dims: list[int]) -> None:
        super().__init__()
        self.action_dims = action_dims
        # Filled by proba_distribution each forward pass — one MaskableCategorical per factor.
        self.distributions: list[MaskableCategorical] = []

    def proba_distribution_net(self, latent_dim: int) -> nn.Module:
        # OVERRIDE (MaskableDistribution): actor linear layer SB3 registers on the policy.
        return nn.Linear(latent_dim, sum(self.action_dims))

    def proba_distribution(self, action_logits: th.Tensor) -> ConditionalMultiDiscreteDistribution:
        # OVERRIDE (MaskableDistribution): split concatenated logits into four heads.
        reshaped_logits = action_logits.view(-1, sum(self.action_dims))
        self.distributions = [
            MaskableCategorical(logits=split) for split in th.split(reshaped_logits, list(self.action_dims), dim=1)
        ]
        return self

    # Stock MaskableDistribution sampling API — not used; policy.forward bypasses it.
    def apply_masking(self, masks: np.ndarray | th.Tensor | None) -> None:
        raise NotImplementedError

    def get_actions(self, deterministic: bool = False) -> th.Tensor:
        raise NotImplementedError

    def sample(self) -> th.Tensor:
        raise NotImplementedError

    def mode(self) -> th.Tensor:
        raise NotImplementedError

    def log_prob(self, actions: th.Tensor) -> th.Tensor:
        raise NotImplementedError

    def entropy(self) -> th.Tensor:
        raise NotImplementedError

    def actions_from_params(self, action_logits: th.Tensor, deterministic: bool = False) -> th.Tensor:
        raise NotImplementedError

    def log_prob_from_params(self, action_logits: th.Tensor) -> tuple[th.Tensor, th.Tensor]:
        raise NotImplementedError


class ConditionalActorCriticPolicy(MaskableActorCriticPolicy):
    """MaskablePPO policy with conditional factor picking from ``obs["legal_moves"]``."""

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
                "ConditionalActorCriticPolicy requires Dict observation_space "
                f"(game-state keys + legal_moves + legal_move_count); got {type(observation_space)}"
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
                "ConditionalActorCriticPolicy requires share_features_extractor=True "
                "(one GridironFeaturesExtractor shared by actor and critic)"
            )
        # Replace stock MultiDiscrete distribution — still uses SB3 proba_distribution* hooks above.
        self.action_dist = ConditionalMultiDiscreteDistribution(action_head_sizes())

    # -------------------------------------------------------------------------
    # MaskableActorCriticPolicy overrides — only entry points MaskablePPO calls
    # -------------------------------------------------------------------------

    def forward(
        self,
        obs: th.Tensor,
        deterministic: bool = False,
        action_masks: np.ndarray | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor]:
        # OVERRIDE (MaskableActorCriticPolicy): rollout action selection.
        del action_masks  # ActionMasker flat mask; legals live in obs["legal_moves"].

        values, picked_factors, log_prob, _ = self._network_forward_and_pick_factors(
            obs,
            deterministic=deterministic,
        )
        picked_factors = picked_factors.reshape((-1, *self.action_space.shape))  # type: ignore[misc]
        return picked_factors, values, log_prob

    def predict(
        self,
        observation: th.Tensor | dict[str, th.Tensor] | np.ndarray | dict[str, np.ndarray],
        state: tuple[np.ndarray, ...] | None = None,
        episode_start: np.ndarray | None = None,
        deterministic: bool = False,
        action_masks: np.ndarray | None = None,
    ) -> tuple[np.ndarray, tuple[np.ndarray, ...] | None]:
        # OVERRIDE: stock Maskable path calls distribution.get_actions (not implemented).
        # Eval / deterministic play must use the same forward() as rollouts.
        del episode_start
        self.set_training_mode(False)

        # Stock BasePolicy.predict: only squeeze when the obs was not VecEnv-batched.
        # Blind squeeze of batch=1 breaks SubprocVecEnv(n_envs=1) — zip(remotes, actions)
        # then sends each factor as a separate env action (scalar int64 into step).
        obs_tensor, vectorized_env = self.obs_to_tensor(observation)
        with th.no_grad():
            actions, _values, _log_prob = self.forward(
                obs_tensor,
                deterministic=deterministic,
                action_masks=action_masks,
            )
        action_np = actions.cpu().numpy().reshape((-1, *self.action_space.shape))  # type: ignore[misc]
        if not vectorized_env:
            action_np = action_np.squeeze(axis=0)
        return action_np, state

    def evaluate_actions(
        self,
        obs: th.Tensor,
        actions: th.Tensor,
        action_masks: th.Tensor | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor | None]:
        # OVERRIDE (MaskableActorCriticPolicy): PPO replay — score stored factors, do not resample.
        del action_masks

        values, _, log_prob, entropy = self._network_forward_and_pick_factors(
            obs,
            stored_factors=actions,
        )
        return values, log_prob, entropy

    # -------------------------------------------------------------------------
    # Shared forward path (network + conditional pick)
    # -------------------------------------------------------------------------

    def _network_forward_and_pick_factors(
        self,
        obs: PyTorchObs,
        *,
        deterministic: bool = False,
        stored_factors: th.Tensor | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor, th.Tensor]:
        """Run the net and pick or score four action factors per obs row.

        ``stored_factors`` is None when ``forward`` is sampling a new move.
        It is the rollout-buffer (N, FACTORS) tensor when ``evaluate_actions`` replays stored moves.
        """
        stacked_legal_moves, preferred_legal, legal_move_count_per_obs = self._parse_legal_moves_from_obs(obs)

        # inherited (MaskableActorCriticPolicy): Dict obs → board features → actor/critic latents.
        features = self.extract_features(obs)
        latent_pi, latent_vf = self.mlp_extractor(features)
        values = self.value_net(latent_vf)

        # inherited: latent_pi → linear layer → action_dist.proba_distribution → four head logits.
        self._get_action_dist_from_latent(latent_pi)

        picked_factors, log_prob, entropy = self._pick_factors_for_stacked_obs(
            stacked_legal_moves,
            preferred_legal,
            legal_move_count_per_obs,
            deterministic=deterministic,
            stored_factors=stored_factors,
        )
        return values, picked_factors, log_prob, entropy

    # -------------------------------------------------------------------------
    # obs → legal move tables (numpy for conditional_factor_mask in actions.py)
    # -------------------------------------------------------------------------

    @staticmethod
    def _parse_legal_moves_from_obs(obs: PyTorchObs) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        if isinstance(obs, dict) or hasattr(obs, "keys"):
            stacked_legal_moves = obs[OBS_KEY_LEGAL_MOVES]
            legal_move_count_per_obs = obs[OBS_KEY_LEGAL_MOVE_COUNT]
            try:
                preferred_legal = obs[OBS_KEY_SOFT_MASK_LEGAL]
            except (KeyError, TypeError):
                preferred_legal = None
        else:
            raise TypeError(f"Expected Dict observation with legal moves, got {type(obs)}")

        # conditional_factor_mask runs in NumPy; copy off device if needed.
        if isinstance(stacked_legal_moves, th.Tensor):
            stacked_legal_moves = stacked_legal_moves.detach().cpu().numpy()
        if isinstance(legal_move_count_per_obs, th.Tensor):
            legal_move_count_per_obs = legal_move_count_per_obs.detach().cpu().numpy()
        if preferred_legal is None:
            # (batch, MAX_LEGAL) zeros — soft-mask off when the key is absent.
            preferred_legal = np.zeros(stacked_legal_moves.shape[:2], dtype=np.int8)
        elif isinstance(preferred_legal, th.Tensor):
            preferred_legal = preferred_legal.detach().cpu().numpy()
        preferred_legal = np.asarray(preferred_legal, dtype=np.int8)
        return (
            stacked_legal_moves,
            preferred_legal,
            np.asarray(legal_move_count_per_obs, dtype=np.int32).reshape(-1),
        )

    # -------------------------------------------------------------------------
    # Conditional pick → remask → pick (actorKind, target, card heads)
    # -------------------------------------------------------------------------

    def _pick_factors_for_stacked_obs(
        self,
        stacked_legal_moves: np.ndarray,
        preferred_legal: np.ndarray,
        legal_move_count_per_obs: np.ndarray,
        *,
        deterministic: bool = False,
        stored_factors: th.Tensor | None = None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor]:
        n_obs = stacked_legal_moves.shape[0]
        device = self.action_dist.distributions[0].logits.device
        picked_factors_per_obs = th.zeros((n_obs, len(self.action_dist.action_dims)), dtype=th.long, device=device)
        log_prob_per_obs = th.zeros(n_obs, device=device)
        entropy_per_obs = th.zeros(n_obs, device=device)

        for obs_index in range(n_obs):
            if stored_factors is None:
                stored_factors_for_this_obs = None  # forward: sample each factor
            else:
                stored_factors_for_this_obs = stored_factors[obs_index]  # evaluate_actions: one (4,) row

            preferred_row = preferred_legal[obs_index] if preferred_legal.ndim > 1 else preferred_legal
            picked_factors, log_prob, entropy = self._pick_factors_for_single_decision(
                obs_index,
                stacked_legal_moves[obs_index],
                preferred_row,
                int(legal_move_count_per_obs[obs_index]),
                deterministic=deterministic,
                stored_factors=stored_factors_for_this_obs,
            )
            picked_factors_per_obs[obs_index] = picked_factors
            log_prob_per_obs[obs_index] = log_prob
            entropy_per_obs[obs_index] = entropy

        return picked_factors_per_obs, log_prob_per_obs, entropy_per_obs

    def _pick_factors_for_single_decision(
        self,
        obs_index: int,
        legal_moves_this_step: np.ndarray,
        preferred_legal: np.ndarray,
        legal_move_count: int,
        *,
        deterministic: bool,
        stored_factors: th.Tensor | None,
    ) -> tuple[th.Tensor, th.Tensor, th.Tensor]:
        """Pick factor indices for one decision step.

        ``stored_factors`` None → sample (``forward``). Shape (4,) → score that tuple (``evaluate_actions``).
        Soft-mask: when preferred rows remain, add ``SOFT_MASK_LOGIT_BIAS`` to those factor values
        (still fully legal — not a hard mask).
        """
        device = self.action_dist.distributions[0].logits.device
        picked_factors = th.zeros(len(self.action_dist.action_dims), dtype=th.long, device=device)
        log_prob = th.zeros((), device=device)
        entropy = th.zeros((), device=device)
        picked_so_far: dict[int, int] = {}

        for factor_to_pick in CONDITIONAL_SAMPLE_ORDER:
            # Logits for this obs row and factor head (already computed by the network above).
            logits = self.action_dist.distributions[factor_to_pick].logits[obs_index : obs_index + 1]
            bias = th.zeros_like(logits)
            preferred_values = preferred_factor_values(
                legal_moves_this_step,
                preferred_legal,
                legal_move_count=legal_move_count,
                chosen_factors=picked_so_far,
                factor_slot=factor_to_pick,
            )
            if preferred_values.size > 0:
                bias[0, th.as_tensor(preferred_values, device=device, dtype=th.long)] = SOFT_MASK_LOGIT_BIAS
            head = MaskableCategorical(logits=logits + bias)

            # Narrow legal rows by prior picks, then zero illegal logits on this axis.
            head.apply_masking(
                conditional_factor_mask(
                    legal_moves_this_step,
                    legal_move_count=legal_move_count,
                    chosen_factors=picked_so_far,
                    factor_slot=factor_to_pick,
                )
            )

            if stored_factors is None:
                # forward: draw one legal index on this factor axis.
                # DEVIATION: MaskableCategorical.mode is a Tensor property (not mode()).
                factor_index = int((head.mode if deterministic else head.sample()).item())
            else:
                # evaluate_actions: reuse the index saved during rollout; only score log_prob.
                factor_index = int(stored_factors[factor_to_pick].item())

            picked_factors[factor_to_pick] = factor_index
            picked_so_far[factor_to_pick] = factor_index

            # Categorical.log_prob expects a batch-shaped index tensor (batch size 1 here).
            factor_index_batch = th.tensor([factor_index], dtype=th.long, device=device)
            log_prob = log_prob + head.log_prob(factor_index_batch)[0]
            entropy = entropy + head.entropy()[0]

        return picked_factors, log_prob, entropy
