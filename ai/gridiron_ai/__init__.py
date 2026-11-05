"""Goblins & Gridirons AI training harness (schema 16 legal-row join scores).

Thin Gymnasium / Stable-Baselines3 glue over the Kotlin `/api/ai/...` contract.

Call flow (who calls what):

  scripts/clone_expert.main
      → expert_clone: copy packed lookahead moves into the checkpoint zip (no HTTP, no PPO learn())
  scripts/train_self_play.main
      → MaskablePPO.learn / model.predict   (stock + LegalRowActorCriticPolicy)
          → GridironFeaturesExtractor                        (features.py)
          → GridironEnv.reset / .step   (vs COMPUTER or browser human; server waits on handoff)
              → GridironClient …/step|submit/packed              (api/client.py)
              → slice_packed_step (obs → gym Dict)               (obs.py)
              → resolve_legal_row_index (menu index + legalRows) (actions.py)
              → step_reward via packed ``rewards`` JSON                 (reward.py)
          → info["bb_episode"] → aggregate_episodes (metrics.py) — train TB + eval

Kotlin owns match rules + AI DTOs. Python splits those DTO numbers into a gym Dict
(pitch grid vs benches vs cards) and does not invent extra engine facts.
"""

from gridiron_ai.checkpoints import install_legacy_pickle_aliases

__version__ = "0.1.0"

install_legacy_pickle_aliases()
