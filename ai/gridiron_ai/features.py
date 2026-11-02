"""Game-state + legal-row feature encoding for LegalRowActorCriticPolicy.

Owner: gym Dict → embedding/MLP board trunk; same emb/coord style for each legal
row (actor / kind / target xy / source xy / challenge condition). Policy join-scores
board latent ⊕ row features. See ``ai/OWNERSHIP.md``.

# DEVIATION: stock CombinedExtractor would Flatten each Dict Box. We embed enums,
add pitch (x, y, dist-to-ball), keep benches as a table, then Linear-project.
"""

from __future__ import annotations

import torch as th
from gymnasium import spaces
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor
from stable_baselines3.common.type_aliases import TensorDict
from torch import nn

from gridiron_ai.constants import (
    ACTIVE_CHALLENGE_SLOTS,
    AI_HELD_CHECK_KIND_VOCAB,
    AI_TEAM_REL_VOCAB,
    AI_TERRAIN_VOCAB,
    ATTACHED_BONUS_UNUSED,
    BENCH_SLOTS,
    BLOCK_DIE_FACE_VOCAB,
    BLOCK_FACE_PACK_SIZE,
    BLOCK_FACE_PACK_UNUSED,
    BONUS_HAND_SLOTS,
    BONUS_PLAY_VOCAB,
    LEGAL_ACTION_KIND_COUNT,
    CELL_CH_ABILITY,
    CELL_CH_ARMOUR,
    CELL_CH_BLOCK_DICE,
    CELL_CH_CAN_PICKUP,
    CELL_CH_CAN_REACH_BALL,
    CELL_CH_CAN_SCORE_TD,
    CELL_CH_CAN_THROW_TEAMMATE,
    CELL_CH_CERTAIN_CLAIM,
    CELL_CH_HAS_BALL,
    CELL_CH_MIGHT_CLAIM,
    CELL_CH_MOVE,
    CELL_CH_PLAYER_INDEX,
    CELL_CH_REACH_MAX_X,
    CELL_CH_REACH_MAX_Y,
    CELL_CH_REACH_MIN_X,
    CELL_CH_REACH_MIN_Y,
    CELL_CH_STATUS,
    CELL_CH_TEAM_REL,
    CELL_CH_TERRAIN,
    CELL_CH_THROW_STAT,
    CELL_CH_TURN_ENDED,
    CHALLENGE_CONDITION_VOCAB,
    HELD_CHECK_FIELD_COUNT,
    MAX_HELD_BLOCK_FACES,
    NUMBERS_PER_BOARD_CELL,
    OBS_KEY_BENCHES,
    OBS_KEY_CHALLENGES,
    OBS_KEY_DECK,
    OBS_KEY_HAND,
    OBS_KEY_HELD_CHECK,
    OBS_KEY_PITCH,
    OBS_KEY_SCALARS,
    PITCH_SQUARE_COUNT,
    PLAYER_ABILITY_VOCAB,
    PLAYER_INDEX_VOCAB,
    PLAYER_STATUS_VOCAB,
    PLAY_HEIGHT,
    PLAY_WIDTH,
    SCALAR_CH_PHASE,
    SCALAR_COUNT,
    TURN_PHASE_VOCAB,
)

# Small shared width for every enum / player-index embedding (board + legal rows).
EMBED_DIM = 8
# Occupant block: terrain + playerIndex + team + status + ability + 16 scalars (stats/affordances/reach).
_OCCUPANT_WIDTH = 5 * EMBED_DIM + 16
# Pitch extras: x_norm, y_norm, chebyshev-to-ball / max(H,W).
_PITCH_EXTRA = 3
# heldCheck: kind emb + d6/6 + 3 face embs.
_HELD_CHECK_WIDTH = EMBED_DIM + 1 + MAX_HELD_BLOCK_FACES * EMBED_DIM
# Legal row: actor + kind + (x,y,has_tgt) + (src_x,src_y,has_src) + challenge + bonus + faces.
LEGAL_ROW_FEATURE_DIM = (
    EMBED_DIM  # actor
    + EMBED_DIM  # kind
    + 3  # target xy + gate (decoded from pitch index)
    + 3  # source xy + gate (0-based ints from Kotlin; no index decode)
    + (EMBED_DIM + 1)  # challenge condition + points
    + EMBED_DIM  # attached bonus
    + MAX_HELD_BLOCK_FACES * EMBED_DIM  # face0..2
)
_PROJECTED_FEATURES = 256


def _concat_width() -> int:
    """Flattened size before the projection Linear — keep in sync with forward()."""
    pitch = PITCH_SQUARE_COUNT * (_OCCUPANT_WIDTH + _PITCH_EXTRA)
    benches = 2 * BENCH_SLOTS * _OCCUPANT_WIDTH
    scalars = EMBED_DIM + (SCALAR_COUNT - 1)  # phase embedded, other scalars raw
    challenges = ACTIVE_CHALLENGE_SLOTS * (EMBED_DIM + 1)
    deck = EMBED_DIM + 2
    hand = BONUS_HAND_SLOTS * EMBED_DIM
    return pitch + benches + scalars + challenges + deck + hand + _HELD_CHECK_WIDTH


class GridironFeaturesExtractor(BaseFeaturesExtractor):
    """Embed + coord channels + flatten concat + Linear. Pitch is (C, H, W); benches are not on the grid."""

    def __init__(self, observation_space: spaces.Dict, features_dim: int = _PROJECTED_FEATURES) -> None:
        super().__init__(observation_space, features_dim=features_dim)
        self.phase_emb = nn.Embedding(TURN_PHASE_VOCAB, EMBED_DIM)
        self.terrain_emb = nn.Embedding(AI_TERRAIN_VOCAB, EMBED_DIM)
        self.player_index_emb = nn.Embedding(PLAYER_INDEX_VOCAB, EMBED_DIM)
        self.team_emb = nn.Embedding(AI_TEAM_REL_VOCAB, EMBED_DIM)
        self.status_emb = nn.Embedding(PLAYER_STATUS_VOCAB, EMBED_DIM)
        self.ability_emb = nn.Embedding(PLAYER_ABILITY_VOCAB, EMBED_DIM)
        self.challenge_emb = nn.Embedding(CHALLENGE_CONDITION_VOCAB, EMBED_DIM)
        self.bonus_emb = nn.Embedding(BONUS_PLAY_VOCAB, EMBED_DIM)
        self.held_kind_emb = nn.Embedding(AI_HELD_CHECK_KIND_VOCAB, EMBED_DIM)
        self.block_face_emb = nn.Embedding(BLOCK_DIE_FACE_VOCAB, EMBED_DIM)
        # Action-menu kind / attached-bonus index — same emb width as board enums.
        self.kind_emb = nn.Embedding(LEGAL_ACTION_KIND_COUNT, EMBED_DIM)
        self.attached_bonus_emb = nn.Embedding(ATTACHED_BONUS_UNUSED + 1, EMBED_DIM)

        # Pitch (row, col) in 0..H-1 / 0..W-1 — buffers move with .to(device).
        # clone(): expand() aliases memory; SB3 load_state_dict cannot copy into that view.
        rows = th.arange(PLAY_HEIGHT, dtype=th.float32).view(PLAY_HEIGHT, 1).expand(PLAY_HEIGHT, PLAY_WIDTH).clone()
        cols = th.arange(PLAY_WIDTH, dtype=th.float32).view(1, PLAY_WIDTH).expand(PLAY_HEIGHT, PLAY_WIDTH).clone()
        self.register_buffer("pitch_row", rows)
        self.register_buffer("pitch_col", cols)
        self.register_buffer("pitch_y_norm", rows / float(PLAY_HEIGHT - 1))
        self.register_buffer("pitch_x_norm", cols / float(PLAY_WIDTH - 1))

        self.proj = nn.Linear(_concat_width(), features_dim)
        self.proj_act = nn.ReLU()

    def forward(self, observations: TensorDict) -> th.Tensor:
        # OVERRIDE (BaseFeaturesExtractor): split Dict → one vector for the stock MLP.
        pieces = [
            self._encode_scalars(observations[OBS_KEY_SCALARS].float()),
            self._encode_challenges(observations[OBS_KEY_CHALLENGES].float()),
            self._encode_deck(observations[OBS_KEY_DECK].float()),
            self._encode_hand(observations[OBS_KEY_HAND].float()),
            self._encode_held_check(observations[OBS_KEY_HELD_CHECK].float()),
            self._encode_pitch(observations[OBS_KEY_PITCH].float()),
            self._encode_benches(observations[OBS_KEY_BENCHES].float()),
        ]
        return self.proj_act(self.proj(th.cat(pieces, dim=1)))

    def encode_legal_rows(self, legal_moves: th.Tensor, challenges: th.Tensor) -> th.Tensor:
        """Encode each legal-row int tuple like board channels — shared embs + pitch xy.

        ``legal_moves``: (N, R, 10) actor, kind, boardTarget, challengeSlot, attachedBonus,
        face0..2, sourceX, sourceY (−1 pad rows). ``challenges``: (N, 3, 2) board challenge slots.
        Unused axes → zeros (no pad-id embedding).
        """
        actors = legal_moves[..., 0].long()
        kinds = legal_moves[..., 1].long()
        targets = legal_moves[..., 2].long()
        slots = legal_moves[..., 3].long()
        bonuses = legal_moves[..., 4].long()
        faces = legal_moves[..., 5:8].long()
        source_xs = legal_moves[..., 8].long()
        source_ys = legal_moves[..., 9].long()

        actor_e = self.player_index_emb(self._enum_index(actors.clamp(min=0).float(), PLAYER_INDEX_VOCAB))
        kind_e = self.kind_emb(kinds.clamp(min=0, max=LEGAL_ACTION_KIND_COUNT - 1))

        has_target = (targets >= 0) & (targets < PITCH_SQUARE_COUNT)
        safe_t = targets.clamp(0, PITCH_SQUARE_COUNT - 1)
        y_norm = (safe_t // PLAY_WIDTH).float() / float(PLAY_HEIGHT - 1)
        x_norm = (safe_t % PLAY_WIDTH).float() / float(PLAY_WIDTH - 1)
        gate_t = has_target.to(dtype=x_norm.dtype)
        target_feat = th.stack([x_norm * gate_t, y_norm * gate_t, gate_t], dim=-1)

        # Metadata (pack tail): 0-based pitch x/y shipped from Kotlin — normalize only.
        has_source = (source_xs >= 0) & (source_xs < PLAY_WIDTH) & (source_ys >= 0) & (source_ys < PLAY_HEIGHT)
        src_x = source_xs.clamp(0, PLAY_WIDTH - 1).float() / float(PLAY_WIDTH - 1)
        src_y = source_ys.clamp(0, PLAY_HEIGHT - 1).float() / float(PLAY_HEIGHT - 1)
        gate_src = has_source.to(dtype=src_x.dtype)
        source_feat = th.stack([src_x * gate_src, src_y * gate_src, gate_src], dim=-1)

        has_challenge = (slots >= 0) & (slots < ACTIVE_CHALLENGE_SLOTS)
        safe_s = slots.clamp(0, ACTIVE_CHALLENGE_SLOTS - 1)
        slot_idx = safe_s.unsqueeze(-1).expand(-1, -1, 2)
        gathered = th.gather(challenges, 1, slot_idx)
        cond_e = self.challenge_emb(self._enum_index(gathered[..., 0], CHALLENGE_CONDITION_VOCAB))
        points = gathered[..., 1:2]
        gate_c = has_challenge.to(dtype=cond_e.dtype).unsqueeze(-1)
        challenge_feat = th.cat([cond_e, points], dim=-1) * gate_c

        has_bonus = (bonuses >= 0) & (bonuses < ATTACHED_BONUS_UNUSED)
        bonus_e = self.attached_bonus_emb(bonuses.clamp(min=0, max=ATTACHED_BONUS_UNUSED))
        bonus_feat = bonus_e * has_bonus.to(dtype=bonus_e.dtype).unsqueeze(-1)

        # Face pack codes: 0 unused; 1..5 → block_face_emb rows (same vocab as held-check faces).
        has_face = (faces > BLOCK_FACE_PACK_UNUSED) & (faces < BLOCK_FACE_PACK_SIZE)
        face_codes = faces.clamp(min=0, max=BLOCK_DIE_FACE_VOCAB - 1)
        face_e = self.block_face_emb(face_codes) * has_face.to(dtype=bonus_e.dtype).unsqueeze(-1)
        face_feat = face_e.flatten(-2, -1)

        return th.cat(
            [actor_e, kind_e, target_feat, source_feat, challenge_feat, bonus_feat, face_feat],
            dim=-1,
        )

    def _encode_scalars(self, scalars: th.Tensor) -> th.Tensor:
        """scalars: (N, 17). Embed phase; keep the other 16 as numbers."""
        phase = self.phase_emb(self._enum_index(scalars[:, SCALAR_CH_PHASE], TURN_PHASE_VOCAB))
        rest_idx = [i for i in range(SCALAR_COUNT) if i != SCALAR_CH_PHASE]
        rest = scalars[:, rest_idx]
        return th.cat([phase, rest], dim=1)

    def _encode_challenges(self, challenges: th.Tensor) -> th.Tensor:
        """challenges: (N, 3, 2) = (condition code, points) per slot."""
        cond = self.challenge_emb(self._enum_index(challenges[:, :, 0], CHALLENGE_CONDITION_VOCAB))
        points = challenges[:, :, 1:2]
        return th.cat([cond, points], dim=-1).flatten(1)

    def _encode_deck(self, deck: th.Tensor) -> th.Tensor:
        """deck: (N, 3) = top condition, deckSize, discardSize."""
        cond = self.challenge_emb(self._enum_index(deck[:, 0], CHALLENGE_CONDITION_VOCAB))
        return th.cat([cond, deck[:, 1:]], dim=1)

    def _encode_hand(self, hand: th.Tensor) -> th.Tensor:
        """hand: (N, 6) bonus-play codes (0 = empty slot)."""
        return self.bonus_emb(self._enum_index(hand, BONUS_PLAY_VOCAB)).flatten(1)

    def _encode_held_check(self, held: th.Tensor) -> th.Tensor:
        """held: (N, 5) = kind, d6, face0, face1, face2."""
        if held.shape[-1] != HELD_CHECK_FIELD_COUNT:
            raise ValueError(f"held_check width {held.shape[-1]} != {HELD_CHECK_FIELD_COUNT}")
        kind = self.held_kind_emb(self._enum_index(held[:, 0], AI_HELD_CHECK_KIND_VOCAB))
        d6 = (held[:, 1:2].clamp(min=0.0) / 6.0)
        faces = self.block_face_emb(self._enum_index(held[:, 2:], BLOCK_DIE_FACE_VOCAB)).flatten(1)
        return th.cat([kind, d6, faces], dim=1)

    def _encode_pitch(self, pitch: th.Tensor) -> th.Tensor:
        """pitch: (N, C, H, W) packed cell channels. Adds x, y, dist-to-ball; still no conv."""
        if pitch.shape[1] != NUMBERS_PER_BOARD_CELL:
            raise ValueError(f"pitch channel count {pitch.shape[1]} != {NUMBERS_PER_BOARD_CELL}")
        n = pitch.shape[0]
        occupants = self._encode_occupants(pitch.permute(0, 2, 3, 1))  # (N, H, W, occupant)
        x = self.pitch_x_norm.unsqueeze(0).unsqueeze(-1).expand(n, -1, -1, 1)
        y = self.pitch_y_norm.unsqueeze(0).unsqueeze(-1).expand(n, -1, -1, 1)
        dist = self._dist_to_ball(pitch[:, CELL_CH_HAS_BALL]).unsqueeze(-1)
        return th.cat([occupants, x, y, dist], dim=-1).flatten(1)

    def _encode_benches(self, benches: th.Tensor) -> th.Tensor:
        """benches: (N, 2, 10, C) — us then them; not spatial neighbors of the pitch."""
        return self._encode_occupants(benches).flatten(1)

    def _encode_occupants(self, cell_last: th.Tensor) -> th.Tensor:
        """cell_last: (..., 21) channel-last packed cell. Shared by pitch squares and bench slots."""
        terrain = self.terrain_emb(self._enum_index(cell_last[..., CELL_CH_TERRAIN], AI_TERRAIN_VOCAB))
        player_index = self.player_index_emb(self._enum_index(cell_last[..., CELL_CH_PLAYER_INDEX], PLAYER_INDEX_VOCAB))
        team = self.team_emb(self._enum_index(cell_last[..., CELL_CH_TEAM_REL], AI_TEAM_REL_VOCAB))
        status = self.status_emb(self._enum_index(cell_last[..., CELL_CH_STATUS], PLAYER_STATUS_VOCAB))
        ability = self.ability_emb(self._enum_index(cell_last[..., CELL_CH_ABILITY], PLAYER_ABILITY_VOCAB))
        has_ball = cell_last[..., CELL_CH_HAS_BALL].unsqueeze(-1)
        turn_ended = cell_last[..., CELL_CH_TURN_ENDED].clamp(min=0.0).unsqueeze(-1)
        # Empty-square stats are -1 on the wire; clamp then /8 so they sit near embedding scale.
        move = (cell_last[..., CELL_CH_MOVE].clamp(min=0.0) / 8.0).unsqueeze(-1)
        block_dice = (cell_last[..., CELL_CH_BLOCK_DICE].clamp(min=0.0) / 8.0).unsqueeze(-1)
        throw_stat = (cell_last[..., CELL_CH_THROW_STAT].clamp(min=0.0) / 8.0).unsqueeze(-1)
        armour = (cell_last[..., CELL_CH_ARMOUR].clamp(min=0.0) / 8.0).unsqueeze(-1)
        can_throw = cell_last[..., CELL_CH_CAN_THROW_TEAMMATE].clamp(min=0.0).unsqueeze(-1)
        can_score_td = cell_last[..., CELL_CH_CAN_SCORE_TD].clamp(min=0.0).unsqueeze(-1)
        might_claim = cell_last[..., CELL_CH_MIGHT_CLAIM].clamp(min=0.0).unsqueeze(-1)
        certain_claim = cell_last[..., CELL_CH_CERTAIN_CLAIM].clamp(min=0.0).unsqueeze(-1)
        can_reach_ball = cell_last[..., CELL_CH_CAN_REACH_BALL].clamp(min=0.0).unsqueeze(-1)
        can_pickup = cell_last[..., CELL_CH_CAN_PICKUP].clamp(min=0.0).unsqueeze(-1)
        # Reach bbox: 1..W/H on the wire; SENTINEL (-1) → 0 via has_reach gate.
        reach_min_x_raw = cell_last[..., CELL_CH_REACH_MIN_X]
        has_reach = (reach_min_x_raw >= 0.0).float().unsqueeze(-1)
        reach_min_x = (reach_min_x_raw.clamp(min=1.0) - 1.0) / float(PLAY_WIDTH - 1)
        reach_max_x = (cell_last[..., CELL_CH_REACH_MAX_X].clamp(min=1.0) - 1.0) / float(PLAY_WIDTH - 1)
        reach_min_y = (cell_last[..., CELL_CH_REACH_MIN_Y].clamp(min=1.0) - 1.0) / float(PLAY_HEIGHT - 1)
        reach_max_y = (cell_last[..., CELL_CH_REACH_MAX_Y].clamp(min=1.0) - 1.0) / float(PLAY_HEIGHT - 1)
        return th.cat(
            [
                terrain,
                player_index,
                team,
                status,
                ability,
                has_ball,
                turn_ended,
                move,
                block_dice,
                throw_stat,
                armour,
                can_throw,
                can_score_td,
                might_claim,
                certain_claim,
                can_reach_ball,
                can_pickup,
                reach_min_x.unsqueeze(-1) * has_reach,
                reach_max_x.unsqueeze(-1) * has_reach,
                reach_min_y.unsqueeze(-1) * has_reach,
                reach_max_y.unsqueeze(-1) * has_reach,
            ],
            dim=-1,
        )

    def _dist_to_ball(self, has_ball: th.Tensor) -> th.Tensor:
        """Chebyshev distance to the ball square, 0..1. All-ones if no ball (empty map).

        The base game has one ball in play; argmax is enough. Distance is *computed* from hasBall
        — not an extra DTO channel.
        """
        n = has_ball.shape[0]
        flat = has_ball.reshape(n, -1)
        ball_index = flat.argmax(dim=1)
        has_any = flat.max(dim=1).values > 0.5
        ball_row = (ball_index // PLAY_WIDTH).float().view(n, 1, 1)
        ball_col = (ball_index % PLAY_WIDTH).float().view(n, 1, 1)
        cheb = th.maximum((self.pitch_row - ball_row).abs(), (self.pitch_col - ball_col).abs())
        scaled = cheb / float(max(PLAY_HEIGHT, PLAY_WIDTH))
        # No ball → 1.0 (as far as the scaled grid goes).
        return scaled * has_any.view(n, 1, 1).float() + (~has_any).view(n, 1, 1).float()

    @staticmethod
    def _enum_index(codes: th.Tensor, vocab: int) -> th.Tensor:
        """Packed obs enum codes (0 = absent) → embedding rows. Clamp so a new ordinal cannot crash a run."""
        return codes.round().long().clamp(0, vocab - 1)
