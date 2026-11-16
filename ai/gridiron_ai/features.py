"""Game-state + legal-row feature encoding for LegalRowActorCriticPolicy.

Owner: gym Dict → board features + legal-row encode. Policy join =
``flatten(pitch CNN map) ‖ non_pitch ‖ row_emb`` (no spatial summary on the
join). Value path still GAP+projects for SB3's single features vector.
See ``ai/OWNERSHIP.md`` / ``ai/PPO_STRATEGY.md``.

# DEVIATION: stock CombinedExtractor flattens each Dict Box. We embed cells,
# Conv2d the pitch (keep the map for the join), GAP only for the value trunk.
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
    BENCH_SLOTS,
    BLOCK_DIE_FACE_VOCAB,
    BLOCK_FACE_PACK_SIZE,
    BONUS_HAND_SLOTS,
    BONUS_PLAY_VOCAB,
    LEGAL_ACTION_KIND_COUNT,
    CELL_CH_ABILITY,
    CELL_CH_ARMOUR,
    CELL_CH_BLOCK_DICE,
    CELL_CH_CAN_THROW_TEAMMATE,
    CELL_CH_DIST_TO_BALL,
    CELL_CH_HAS_BALL,
    CELL_CH_MOVE,
    CELL_CH_PITCH_X,
    CELL_CH_PITCH_Y,
    CELL_CH_PLAYER_INDEX,
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
    PLAYER_ABILITY_VOCAB,
    PLAYER_INDEX_VOCAB,
    PLAYER_STATUS_VOCAB,
    PLAY_HEIGHT,
    PLAY_WIDTH,
    SCALAR_CH_PHASE,
    SCALAR_COUNT,
    TURN_PHASE_VOCAB,
)

# Small shared width for every enum / player-index / pitch-axis embedding.
EMBED_DIM = 8
# Pitch emb: live 0..W-1 / 0..H-1; last row = pad for wire SENTINEL (-1).
PITCH_X_VOCAB = PLAY_WIDTH + 1
PITCH_Y_VOCAB = PLAY_HEIGHT + 1
# Scale true magnitudes into ~0..1 (coords are embedded, not scaled).
_DIST_TO_BALL_NORM = float(max(PLAY_WIDTH, PLAY_HEIGHT))
_MOVE_NORM = 10.0
_BLOCK_DICE_NORM = 3.0
_THROW_ARMOUR_NORM = 10.0
_CHALLENGE_POINTS_NORM = 5.0
_DECK_COUNT_NORM = 30.0
_D6_NORM = 6.0
# Scalars (phase embedded separately): turn, actionsLeft, scores, TDs, cards, …
_SCALAR_NORMS = (
    20.0,  # turnNumber
    3.0,  # actionsRemaining
    10.0,  # scoreUs
    10.0,  # scoreThem
    5.0,  # touchdownsUs
    5.0,  # touchdownsThem
    10.0,  # challengeCardsUs
    10.0,  # challengeCardsThem
    10.0,  # pointsVictoryTurnsRemaining (code / count-ish)
    5.0,  # emergencyReservesUs
    5.0,  # emergencyReservesThem
    5.0,  # claimsThisTurn
    6.0,  # oppHandSize
    1.0,  # modHoldTheLine (0/1 code)
    1.0,  # modGangUp
    1.0,  # modAllInTackleProneAfterBlock
)
# Occupant vector: terrain + hasBall + pitch xy + dist + player enums/stats (no menu affordances).
_OCCUPANT_WIDTH = (
    EMBED_DIM  # terrain
    + 1  # hasBall
    + 2 * EMBED_DIM  # pitch x/y
    + 1  # distToBall
    + 4 * EMBED_DIM  # playerIndex, teamRel, status, ability
    + 6  # turnEnded, move, blockDice, throwStat, armour, canThrowTeammate
)
# Legal row segments — order matches pack (schema 25) and rhymes with cell channels.
# Verbose |W| meters use these names; must match ``encode_legal_rows`` cat order.
LEGAL_ROW_FEATURE_SEGMENTS: tuple[tuple[str, int], ...] = (
    ("kind", EMBED_DIM),
    ("actorHasBall", 1),
    ("sourceXY", 2 * EMBED_DIM),
    ("actor", EMBED_DIM),
    ("targetHasBall", 1),
    ("targetXY", 2 * EMBED_DIM),
    ("blockDiceCount", 1),
    ("throwSuccessChance", 1),
    ("challenge", EMBED_DIM + 1),
    ("attachedBonus", EMBED_DIM),
    ("faces", MAX_HELD_BLOCK_FACES * EMBED_DIM),
)
LEGAL_ROW_FEATURE_DIM = sum(width for _, width in LEGAL_ROW_FEATURE_SEGMENTS)
# heldCheck: kind emb + scaled d6 + 3 face embs.
_HELD_CHECK_WIDTH = EMBED_DIM + 1 + MAX_HELD_BLOCK_FACES * EMBED_DIM
# Pitch: emb map → Conv2d → (N, C, H, W). Join flattens the map; value path GAPs it.
_PITCH_CNN_WIDTH = 64
_PITCH_CNN_OUT = 128
JOIN_CELL_FEAT = _PITCH_CNN_OUT
JOIN_PITCH_FLAT = JOIN_CELL_FEAT * PLAY_HEIGHT * PLAY_WIDTH  # 128×15×11
# SB3 value trunk width (GAP pitch ‖ non-pitch → Linear).
_PROJECTED_FEATURES = 512


def _non_pitch_width() -> int:
    benches = 2 * BENCH_SLOTS * _OCCUPANT_WIDTH
    scalars = EMBED_DIM + (SCALAR_COUNT - 1)
    challenges = ACTIVE_CHALLENGE_SLOTS * (EMBED_DIM + 1)
    deck = EMBED_DIM + 2
    hand = BONUS_HAND_SLOTS * EMBED_DIM
    return benches + scalars + challenges + deck + hand + _HELD_CHECK_WIDTH


def _concat_width() -> int:
    """GAP pitch + non-pitch — value-trunk Linear in_features."""
    return _PITCH_CNN_OUT + _non_pitch_width()


# row_scorer input: flatten(pitch map) ‖ non-pitch ‖ row_emb (no latent / attn / gather).
JOIN_BOARD_FEAT = JOIN_PITCH_FLAT + _non_pitch_width()
JOIN_SCORER_IN = JOIN_BOARD_FEAT + LEGAL_ROW_FEATURE_DIM


class GridironFeaturesExtractor(BaseFeaturesExtractor):
    """Embed cells; Conv2d pitch map; GAP+proj for SB3 value features; stash map for join."""

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
        self.kind_emb = nn.Embedding(LEGAL_ACTION_KIND_COUNT, EMBED_DIM)
        self.pitch_x_emb = nn.Embedding(PITCH_X_VOCAB, EMBED_DIM)
        self.pitch_y_emb = nn.Embedding(PITCH_Y_VOCAB, EMBED_DIM)
        self.pitch_cnn = nn.Sequential(
            nn.Conv2d(_OCCUPANT_WIDTH, _PITCH_CNN_WIDTH, kernel_size=3, padding=1),
            nn.ReLU(),
            nn.Conv2d(_PITCH_CNN_WIDTH, _PITCH_CNN_WIDTH, kernel_size=3, padding=1),
            nn.ReLU(),
            nn.Conv2d(_PITCH_CNN_WIDTH, _PITCH_CNN_OUT, kernel_size=3, padding=1),
            nn.ReLU(),
        )
        self.pitch_gap = nn.AdaptiveAvgPool2d(1)
        self.proj = nn.Linear(_concat_width(), features_dim)
        self.proj_act = nn.ReLU()
        # Stash for policy join (SB3 forward can only return the value features vector).
        self._pitch_feature_map: th.Tensor | None = None
        self._non_pitch_features: th.Tensor | None = None

    def forward(self, observations: TensorDict) -> th.Tensor:
        # OVERRIDE: one vector for SB3 value / mlp_extractor. Join board is stashed.
        scalars = self._encode_scalars(observations[OBS_KEY_SCALARS].float())
        challenges = self._encode_challenges(observations[OBS_KEY_CHALLENGES].float())
        deck = self._encode_deck(observations[OBS_KEY_DECK].float())
        hand = self._encode_hand(observations[OBS_KEY_HAND].float())
        held = self._encode_held_check(observations[OBS_KEY_HELD_CHECK].float())
        pitch_gap = self._encode_pitch(observations[OBS_KEY_PITCH].float())
        benches = self._encode_benches(observations[OBS_KEY_BENCHES].float())
        non_pitch = th.cat([scalars, challenges, deck, hand, held, benches], dim=1)
        self._non_pitch_features = non_pitch
        return self.proj_act(self.proj(th.cat([scalars, challenges, deck, hand, held, pitch_gap, benches], dim=1)))

    def consume_join_board(self) -> th.Tensor:
        """``flatten(pitch map) ‖ non_pitch`` from the last ``forward``; clears the stash.

        # DEVIATION: SB3 ``BaseFeaturesExtractor.forward`` returns one tensor (value trunk).
        # Honest API would return ``(value_features, join_board)``; we stash join_board instead.
        """
        pitch_map = self._pitch_feature_map
        non_pitch = self._non_pitch_features
        self._pitch_feature_map = None
        self._non_pitch_features = None
        if pitch_map is None or non_pitch is None:
            raise RuntimeError("join board missing — call extract_features / forward first")
        return th.cat([pitch_map.flatten(1), non_pitch], dim=1)

    def encode_legal_rows(self, legal_moves: th.Tensor, challenges: th.Tensor) -> th.Tensor:
        """Encode each legal-row int tuple like board channels — shared embs + pitch xy ids.

        ``legal_moves``: (N, R, 15) kind, actorHasBall, sourceX/Y, actor, targetHasBall,
        targetX/Y, blockDiceCount, throwSuccessChancePercent, challengeSlot,
        attachedBonus, face0..2 (−1 pad rows).
        ``challenges``: (N, 3, 2) board challenge slots. Unused axes → unused emb row.
        """
        kinds = legal_moves[..., 0].long()
        actor_has_ball = legal_moves[..., 1].float().clamp(0.0, 1.0).unsqueeze(-1)
        source_xs = legal_moves[..., 2].long()
        source_ys = legal_moves[..., 3].long()
        actors = legal_moves[..., 4].long()
        target_has_ball = legal_moves[..., 5].float().clamp(0.0, 1.0).unsqueeze(-1)
        target_xs = legal_moves[..., 6].long()
        target_ys = legal_moves[..., 7].long()
        # Block dice: SENTINEL (-1) = non-block; else scale only.
        block_raw = legal_moves[..., 8].float()
        block_dice = th.where(block_raw < 0, th.zeros_like(block_raw), block_raw.clamp(0.0, 3.0) / 3.0).unsqueeze(-1)
        # Throw: Kotlin packs 0..100 (0 = non-throw) — scale only.
        throw_success = (legal_moves[..., 9].float().clamp(0.0, 100.0) / 100.0).unsqueeze(-1)
        slots = legal_moves[..., 10].long()
        bonuses = legal_moves[..., 11].long()
        faces = legal_moves[..., 12:15].long()

        kind_e = self.kind_emb(kinds.clamp(min=0, max=LEGAL_ACTION_KIND_COUNT - 1))
        source_feat = th.cat([self._embed_pitch_x(source_xs), self._embed_pitch_y(source_ys)], dim=-1)
        actor_e = self.player_index_emb(self._roster_index(actors))
        target_feat = th.cat([self._embed_pitch_x(target_xs), self._embed_pitch_y(target_ys)], dim=-1)

        has_challenge = (slots >= 0) & (slots < ACTIVE_CHALLENGE_SLOTS)
        safe_s = slots.clamp(0, ACTIVE_CHALLENGE_SLOTS - 1)
        slot_idx = safe_s.unsqueeze(-1).expand(-1, -1, 2)
        gathered = th.gather(challenges, 1, slot_idx)
        cond_e = self.challenge_emb(self._enum_index(gathered[..., 0], CHALLENGE_CONDITION_VOCAB))
        points = (gathered[..., 1:2].float().clamp(min=0.0) / _CHALLENGE_POINTS_NORM).clamp(0.0, 1.0)
        gate_c = has_challenge.to(dtype=cond_e.dtype).unsqueeze(-1)
        challenge_feat = th.cat([cond_e, points], dim=-1) * gate_c

        has_bonus = bonuses >= 0
        bonus_e = self.bonus_emb(self._enum_index(bonuses.float(), BONUS_PLAY_VOCAB))
        bonus_feat = bonus_e * has_bonus.to(dtype=bonus_e.dtype).unsqueeze(-1)

        # Face pack: SENTINEL unused; 0..4 = BlockDieFace.ordinal (same vocab as held-check).
        has_face = faces >= 0
        face_e = self.block_face_emb(self._enum_index(faces.float(), BLOCK_DIE_FACE_VOCAB))
        face_e = face_e * has_face.to(dtype=face_e.dtype).unsqueeze(-1)
        face_feat = face_e.flatten(-2, -1)

        return th.cat(
            [
                kind_e,
                actor_has_ball,
                source_feat,
                actor_e,
                target_has_ball,
                target_feat,
                block_dice,
                throw_success,
                challenge_feat,
                bonus_feat,
                face_feat,
            ],
            dim=-1,
        )

    def _encode_scalars(self, scalars: th.Tensor) -> th.Tensor:
        """scalars: (N, 17). Embed phase; scale the other 16 into ~0..1."""
        phase = self.phase_emb(self._enum_index(scalars[:, SCALAR_CH_PHASE], TURN_PHASE_VOCAB))
        rest_idx = [i for i in range(SCALAR_COUNT) if i != SCALAR_CH_PHASE]
        rest = scalars[:, rest_idx]
        norms = th.tensor(_SCALAR_NORMS, device=rest.device, dtype=rest.dtype)
        rest = (rest / norms).clamp(0.0, 1.0)
        return th.cat([phase, rest], dim=1)

    def _encode_challenges(self, challenges: th.Tensor) -> th.Tensor:
        """challenges: (N, 3, 2) = (condition code, points) per slot."""
        cond = self.challenge_emb(self._enum_index(challenges[:, :, 0], CHALLENGE_CONDITION_VOCAB))
        points = (challenges[:, :, 1:2].float() / _CHALLENGE_POINTS_NORM).clamp(0.0, 1.0)
        return th.cat([cond, points], dim=-1).flatten(1)

    def _encode_deck(self, deck: th.Tensor) -> th.Tensor:
        """deck: (N, 3) = top condition, deckSize, discardSize."""
        cond = self.challenge_emb(self._enum_index(deck[:, 0], CHALLENGE_CONDITION_VOCAB))
        counts = (deck[:, 1:].float() / _DECK_COUNT_NORM).clamp(0.0, 1.0)
        return th.cat([cond, counts], dim=1)

    def _encode_hand(self, hand: th.Tensor) -> th.Tensor:
        """hand: (N, 6) bonus-play codes (0 = empty slot)."""
        return self.bonus_emb(self._enum_index(hand, BONUS_PLAY_VOCAB)).flatten(1)

    def _encode_held_check(self, held: th.Tensor) -> th.Tensor:
        """held: (N, 5) = kind, d6, face0, face1, face2."""
        if held.shape[-1] != HELD_CHECK_FIELD_COUNT:
            raise ValueError(f"held_check width {held.shape[-1]} != {HELD_CHECK_FIELD_COUNT}")
        kind = self.held_kind_emb(self._enum_index(held[:, 0], AI_HELD_CHECK_KIND_VOCAB))
        d6 = (held[:, 1:2].clamp(min=0.0) / _D6_NORM).clamp(0.0, 1.0)
        faces = self.block_face_emb(self._enum_index(held[:, 2:], BLOCK_DIE_FACE_VOCAB)).flatten(1)
        return th.cat([kind, d6, faces], dim=1)

    def _encode_pitch(self, pitch: th.Tensor) -> th.Tensor:
        """pitch: (N, C, H, W) → Conv map (stashed) → GAP → (_PITCH_CNN_OUT,) for trunk."""
        if pitch.shape[1] != NUMBERS_PER_BOARD_CELL:
            raise ValueError(f"pitch channel count {pitch.shape[1]} != {NUMBERS_PER_BOARD_CELL}")
        if pitch.shape[2] != PLAY_HEIGHT or pitch.shape[3] != PLAY_WIDTH:
            raise ValueError(
                f"pitch spatial shape {tuple(pitch.shape[2:])} != ({PLAY_HEIGHT}, {PLAY_WIDTH})"
            )
        # Channel-last emb, then NCHW for Conv2d (occupancy-rich planes, not raw pack ints).
        emb = self._encode_occupants(pitch.permute(0, 2, 3, 1))
        spatial = self.pitch_cnn(emb.permute(0, 3, 1, 2).contiguous())
        self._pitch_feature_map = spatial
        return self.pitch_gap(spatial).flatten(1)

    def _encode_benches(self, benches: th.Tensor) -> th.Tensor:
        """benches: (N, 2, 10, C) — us then them; not spatial neighbors of the pitch."""
        return self._encode_occupants(benches).flatten(1)

    def _encode_occupants(self, cell_last: th.Tensor) -> th.Tensor:
        """cell_last: (..., 15) channel-last packed cell. Shared by pitch squares and bench slots."""
        terrain = self.terrain_emb(self._enum_index(cell_last[..., CELL_CH_TERRAIN], AI_TERRAIN_VOCAB))
        has_ball = cell_last[..., CELL_CH_HAS_BALL].unsqueeze(-1)
        pitch_x = self._embed_pitch_x(cell_last[..., CELL_CH_PITCH_X])
        pitch_y = self._embed_pitch_y(cell_last[..., CELL_CH_PITCH_Y])
        dist_to_ball = (cell_last[..., CELL_CH_DIST_TO_BALL].clamp(min=0.0).unsqueeze(-1) / _DIST_TO_BALL_NORM).clamp(0.0, 1.0)
        player_index = self.player_index_emb(self._roster_index(cell_last[..., CELL_CH_PLAYER_INDEX]))
        team = self.team_emb(self._enum_index(cell_last[..., CELL_CH_TEAM_REL], AI_TEAM_REL_VOCAB))
        status = self.status_emb(self._enum_index(cell_last[..., CELL_CH_STATUS], PLAYER_STATUS_VOCAB))
        ability = self.ability_emb(self._enum_index(cell_last[..., CELL_CH_ABILITY], PLAYER_ABILITY_VOCAB))
        turn_ended = cell_last[..., CELL_CH_TURN_ENDED].clamp(min=0.0).unsqueeze(-1)
        # Empty-square stats are SENTINEL (-1) on the wire — clamp then scale.
        move = (cell_last[..., CELL_CH_MOVE].clamp(min=0.0).unsqueeze(-1) / _MOVE_NORM).clamp(0.0, 1.0)
        block_dice = (cell_last[..., CELL_CH_BLOCK_DICE].clamp(min=0.0).unsqueeze(-1) / _BLOCK_DICE_NORM).clamp(0.0, 1.0)
        throw_stat = (cell_last[..., CELL_CH_THROW_STAT].clamp(min=0.0).unsqueeze(-1) / _THROW_ARMOUR_NORM).clamp(0.0, 1.0)
        armour = (cell_last[..., CELL_CH_ARMOUR].clamp(min=0.0).unsqueeze(-1) / _THROW_ARMOUR_NORM).clamp(0.0, 1.0)
        can_throw = cell_last[..., CELL_CH_CAN_THROW_TEAMMATE].clamp(min=0.0).unsqueeze(-1)
        return th.cat(
            [
                terrain,
                has_ball,
                pitch_x,
                pitch_y,
                dist_to_ball,
                player_index,
                team,
                status,
                ability,
                turn_ended,
                move,
                block_dice,
                throw_stat,
                armour,
                can_throw,
            ],
            dim=-1,
        )

    def _embed_pitch_x(self, xs: th.Tensor) -> th.Tensor:
        """Perspective 0-based x (or SENTINEL) → shared pitch-x embedding (last row = pad)."""
        xi = xs.round().long()
        valid = (xi >= 0) & (xi < PLAY_WIDTH)
        idx = th.where(valid, xi, th.full_like(xi, PLAY_WIDTH))
        return self.pitch_x_emb(idx)

    def _embed_pitch_y(self, ys: th.Tensor) -> th.Tensor:
        """Perspective 0-based y (or SENTINEL) → shared pitch-y embedding (last row = pad)."""
        yi = ys.round().long()
        valid = (yi >= 0) & (yi < PLAY_HEIGHT)
        idx = th.where(valid, yi, th.full_like(yi, PLAY_HEIGHT))
        return self.pitch_y_emb(idx)

    @staticmethod
    def _roster_index(codes: th.Tensor) -> th.Tensor:
        """Wire roster id: SENTINEL → emb 0 (pad); 1..MAX → same row."""
        c = codes.round().long()
        return th.where(c < 0, th.zeros_like(c), c.clamp(0, PLAYER_INDEX_VOCAB - 1))

    @staticmethod
    def _enum_index(codes: th.Tensor, vocab: int) -> th.Tensor:
        """Wire SENTINEL (-1) / ordinal 0..n-1 → emb rows (last row = pad)."""
        c = codes.round().long()
        pad = vocab - 1
        live_max = max(vocab - 2, 0)
        return th.where(c < 0, th.full_like(c, pad), c.clamp(0, live_max))
