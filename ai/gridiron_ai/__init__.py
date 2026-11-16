"""Goblins & Gridirons AI training harness (schema 16 legal-row join scores).

Thin Gymnasium / Stable-Baselines3 glue over the Kotlin `/api/ai/...` contract.

Package layout:

  model/   — action encoding + policy + reward + expert clone + ONNX
  debug/   — eval probes / opening diag / verbose PPO evidence
  train/   — gym/HTTP, curriculum, phases, checkpoints, metrics, persistence

Call flow (who calls what):

  scripts/clone_expert.main
      → model.expert_clone: copy packed lookahead moves into the checkpoint zip
  scripts/train_self_play.main
      → MaskablePPO.learn / model.predict   (stock + LegalRowActorCriticPolicy)
          → GridironFeaturesExtractor                        (model/features.py)
          → GridironEnv.reset / .step
              → GridironClient …/step|submit/packed              (train/api/client.py)
              → slice_packed_step (obs → gym Dict)               (model/obs.py)
              → resolve_legal_row_index (menu index + legalRows) (model/actions.py)
              → step_reward via packed ``rewards`` JSON          (model/reward.py)
          → info["bb_episode"] → aggregate_episodes (train/metrics.py)

Kotlin owns match rules + AI DTOs. Python splits those DTO numbers into a gym Dict
(pitch grid vs benches vs cards) and does not invent extra engine facts.
"""

from gridiron_ai.train.checkpoints import install_legacy_pickle_aliases

__version__ = "0.1.0"

install_legacy_pickle_aliases()
