"""Turn the gym observation Dict into numbers the neural net can score with.

What this file does
-------------------
Kotlin packs the board + cards into a flat float vector. ``obs.py`` slices that
into a Dict (pitch grid, benches, scalars, …). **This module** turns those
Dict tensors into:

1. **Value features** — one fixed-length vector (512 numbers) that Stable-
   Baselines3 (SB3) uses for the critic / "how good is this position?" head.
2. **Join parts** — the uncompressed pitch CNN map plus non-pitch bits
   (benches, cards, …). The **policy** cross-attends each legal-row encoding
   over the map tokens, then scores ``ctx ‖ non_pitch ‖ row_emb``.

Why two outputs from one ``forward``?
-------------------------------------
SB3's stock feature-extractor API may return **only one** tensor. We need a
second blob (the pitch map) for the policy. So ``forward`` returns the value
vector, and **saves** the join parts on the extractor instance. The policy
then calls ``consume_join_board()`` to read and clear that save.

Plain-language glossary
-----------------------
- **Embedding** — a small learned lookup table: enum code → a few floats.
- **Conv2d / CNN** — a small sliding filter over the pitch grid so nearby
  squares can influence each other (still a full H×W map afterward).
- **GAP (global average pool)** — squash that map into one summary vector by
  averaging every square. Used for the **value** path only.
- **Cross-attn join** — each legal row queries all 165 pitch tokens; the
  attended context is glued with non-pitch + the row encoding, then scored.
- **SENTINEL (-1)** — "this field is unused on this row / empty cell."

See ``ai/PPO_STRATEGY.md`` and ``ai/OWNERSHIP.md``.

# DEVIATION: stock SB3 ``CombinedExtractor`` would flatten each Dict Box raw.
# We embed cells and run a pitch CNN instead.
"""

from __future__ import annotations

import torch as th
from gymnasium import spaces
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor
from stable_baselines3.common.type_aliases import TensorDict
from torch import nn

from gridiron_ai.model.constants import (
    ACTIVE_CHALLENGE_SLOTS,
    AI_HELD_CHECK_KIND_VOCAB,
    AI_TEAM_REL_VOCAB,
    AI_TERRAIN_VOCAB,
    BENCH_SLOTS,
    BLOCK_DIE_FACE_VOCAB,
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

# Width of every enum / player-index / pitch-axis embedding vector.
EMBED_DIM = 8
# Pitch emb tables: live coords 0..W-1 / 0..H-1; last row = pad for SENTINEL (-1).
PITCH_X_VOCAB = PLAY_WIDTH + 1
PITCH_Y_VOCAB = PLAY_HEIGHT + 1
# Divide raw magnitudes so they land roughly in 0..1 (coords are embedded, not scaled).
_DIST_TO_BALL_NORM = float(max(PLAY_WIDTH, PLAY_HEIGHT))
_MOVE_NORM = 10.0
_BLOCK_DICE_NORM = 3.0
_THROW_ARMOUR_NORM = 10.0
_CHALLENGE_POINTS_NORM = 5.0
_DECK_COUNT_NORM = 30.0
_D6_NORM = 6.0
# Scalars other than phase (phase goes through an embedding). Order matches the pack.
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
# One square / bench slot after embedding: terrain + ball + xy + dist + player bits.
_OCCUPANT_WIDTH = (
    EMBED_DIM  # terrain
    + 1  # hasBall
    + 2 * EMBED_DIM  # pitch x/y
    + 1  # distToBall
    + 4 * EMBED_DIM  # playerIndex, teamRel, status, ability
    + 6  # turnEnded, move, blockDice, throwStat, armour, canThrowTeammate
)
# Named slices of a legal-row encoding (schema 25 pack order). Used by |W| meters.
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
# Mid-check window: kind emb + scaled d6 + up to 3 block-face embs.
_HELD_CHECK_WIDTH = EMBED_DIM + 1 + MAX_HELD_BLOCK_FACES * EMBED_DIM
# Pitch CNN channel widths. Policy cross-attends the map; value path averages it (GAP).
_PITCH_CNN_WIDTH = 64
_PITCH_CNN_OUT = 128
JOIN_CELL_FEAT = _PITCH_CNN_OUT
PITCH_TOKEN_COUNT = PLAY_HEIGHT * PLAY_WIDTH  # 165
# Move→board cross-attn width (query from row_emb; keys/values from map tokens).
CROSS_ATTN_DIM = 128
CROSS_ATTN_HEADS = 4
# SB3 value-trunk width after Linear(GAP‖non-pitch → this).
_PROJECTED_FEATURES = 512


def _non_pitch_width() -> int:
    """How many floats make up everything that is not the pitch CNN map.

    Benches + turn scalars + challenge slots + deck + hand + held-check window.
    Same width is concatenated for both the value trunk and the policy join.
    """
    benches = 2 * BENCH_SLOTS * _OCCUPANT_WIDTH
    scalars = EMBED_DIM + (SCALAR_COUNT - 1)
    challenges = ACTIVE_CHALLENGE_SLOTS * (EMBED_DIM + 1)
    deck = EMBED_DIM + 2
    hand = BONUS_HAND_SLOTS * EMBED_DIM
    return benches + scalars + challenges + deck + hand + _HELD_CHECK_WIDTH


def _concat_width() -> int:
    """Input size of the value-trunk Linear: GAP'd pitch summary + non-pitch."""
    return _PITCH_CNN_OUT + _non_pitch_width()


# Policy scorer input: attended board context ‖ non-pitch ‖ one legal-row encoding.
JOIN_BOARD_CTX = CROSS_ATTN_DIM
JOIN_SCORER_IN = JOIN_BOARD_CTX + _non_pitch_width() + LEGAL_ROW_FEATURE_DIM


class MoveBoardCrossAttention(nn.Module):
    """Each legal-row encoding queries all pitch CNN tokens (source/target/lanes).

    Named alternative to flatten-join: the full 15×11 map stays addressable; the
    scorer sees a ``CROSS_ATTN_DIM`` context instead of a 21k-d flat vector.
    """

    def __init__(
        self,
        *,
        cell_dim: int = JOIN_CELL_FEAT,
        row_dim: int = LEGAL_ROW_FEATURE_DIM,
        d_model: int = CROSS_ATTN_DIM,
        n_heads: int = CROSS_ATTN_HEADS,
    ) -> None:
        super().__init__()
        if d_model % n_heads != 0:
            raise ValueError(f"d_model={d_model} must divide n_heads={n_heads}")
        self.d_model = d_model
        self.n_heads = n_heads
        self.d_head = d_model // n_heads
        self.q_proj = nn.Linear(row_dim, d_model)
        self.k_proj = nn.Linear(cell_dim, d_model)
        self.v_proj = nn.Linear(cell_dim, d_model)
        self.out_proj = nn.Linear(d_model, d_model)

    def forward(self, pitch_map: th.Tensor, row_emb: th.Tensor) -> th.Tensor:
        """Attend row queries over pitch tokens.

        Parameters
        ----------
        pitch_map
            ``(batch, C, H, W)`` CNN feature map.
        row_emb
            ``(batch, rows, LEGAL_ROW_FEATURE_DIM)`` encoded legal rows.

        Returns
        -------
        ``(batch, rows, d_model)`` attention-weighted board context per row.
        """
        batch, _channels, height, width = pitch_map.shape
        rows = int(row_emb.shape[1])
        tokens = pitch_map.flatten(2).transpose(1, 2)  # (N, H*W, C)
        query = self.q_proj(row_emb)
        key = self.k_proj(tokens)
        value = self.v_proj(tokens)

        def _split_heads(x: th.Tensor) -> th.Tensor:
            length = int(x.shape[1])
            return x.view(batch, length, self.n_heads, self.d_head).transpose(1, 2)

        query_h = _split_heads(query)
        key_h = _split_heads(key)
        value_h = _split_heads(value)
        scores = th.matmul(query_h, key_h.transpose(-2, -1)) * (self.d_head**-0.5)
        weights = th.softmax(scores, dim=-1)
        ctx = th.matmul(weights, value_h)
        ctx = ctx.transpose(1, 2).contiguous().view(batch, rows, self.d_model)
        return self.out_proj(ctx)


class GridironFeaturesExtractor(BaseFeaturesExtractor):
    """Encode the board Dict for both the value head and the legal-row policy.

    Stock SB3 wants one ``features_dim`` vector out of ``forward``. We also need
    the uncompressed pitch map for policy cross-attn, so after building the CNN
    map we keep a copy on ``self`` until ``consume_join_board`` reads it.
    """

    def __init__(self, observation_space: spaces.Dict, features_dim: int = _PROJECTED_FEATURES) -> None:
        """Build embedding tables, the pitch CNN, and the value projection Linear.

        ``features_dim`` is the length of the vector SB3's critic sees (default 512).
        """
        super().__init__(observation_space, features_dim=features_dim)
        # Learned lookup tables: discrete wire codes → EMBED_DIM floats each.
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
        # Three small conv layers over the pitch; pad=1 keeps H×W = 15×11.
        self.pitch_cnn = nn.Sequential(
            nn.Conv2d(_OCCUPANT_WIDTH, _PITCH_CNN_WIDTH, kernel_size=3, padding=1),
            nn.ReLU(),
            nn.Conv2d(_PITCH_CNN_WIDTH, _PITCH_CNN_WIDTH, kernel_size=3, padding=1),
            nn.ReLU(),
            nn.Conv2d(_PITCH_CNN_WIDTH, _PITCH_CNN_OUT, kernel_size=3, padding=1),
            nn.ReLU(),
        )
        # Average every square → one (_PITCH_CNN_OUT,) vector for the value path.
        self.pitch_gap = nn.AdaptiveAvgPool2d(1)
        self.proj = nn.Linear(_concat_width(), features_dim)
        self.proj_act = nn.ReLU()
        # Temporary save for the policy (SB3 ``forward`` can only return one tensor).
        # Cleared by ``consume_join_board`` after the policy reads them.
        self._pitch_feature_map: th.Tensor | None = None
        self._non_pitch_features: th.Tensor | None = None

    def forward(self, observations: TensorDict) -> th.Tensor:
        """Encode the Dict obs into SB3's single value-features vector.

        Also saves the pitch CNN map and non-pitch features on ``self`` so the
        policy can call ``consume_join_board()`` next. That save is the only way
        we can hand the policy a second blob without changing SB3's API.

        Returns
        -------
        Tensor of shape ``(batch, features_dim)`` — critic / value trunk input.
        """
        # Encode each Dict key independently, then fuse.
        scalars = self._encode_scalars(observations[OBS_KEY_SCALARS].float())
        challenges = self._encode_challenges(observations[OBS_KEY_CHALLENGES].float())
        deck = self._encode_deck(observations[OBS_KEY_DECK].float())
        hand = self._encode_hand(observations[OBS_KEY_HAND].float())
        held = self._encode_held_check(observations[OBS_KEY_HELD_CHECK].float())
        # Side effect: fills ``self._pitch_feature_map`` with the full CNN map.
        pitch_gap = self._encode_pitch(observations[OBS_KEY_PITCH].float())
        benches = self._encode_benches(observations[OBS_KEY_BENCHES].float())
        # Same non-pitch block the policy concatenates after cross-attn context.
        non_pitch = th.cat([scalars, challenges, deck, hand, held, benches], dim=1)
        self._non_pitch_features = non_pitch
        # Value path: GAP summary ‖ non-pitch → Linear → ReLU → 512-d features.
        return self.proj_act(self.proj(th.cat([scalars, challenges, deck, hand, held, pitch_gap, benches], dim=1)))

    def consume_join_board(self) -> tuple[th.Tensor, th.Tensor]:
        """Hand the policy ``(pitch CNN map, non_pitch)``, then clear the temporary save.

        Must be called after ``forward`` / ``extract_features`` on this same
        extractor. The map stays spatial ``(batch, C, H, W)`` for cross-attn;
        non-pitch is the benches/cards/scalars vector.

        Why "consume"? The save is one-shot: reading clears it so a later call
        cannot accidentally reuse a stale board from a previous step.

        # DEVIATION: honest API would return ``(value_features, map, non_pitch)``
        # from ``forward``. SB3 only accepts one tensor, so we save+consume instead.
        """
        pitch_map = self._pitch_feature_map
        non_pitch = self._non_pitch_features
        self._pitch_feature_map = None
        self._non_pitch_features = None
        if pitch_map is None or non_pitch is None:
            raise RuntimeError("join board missing — call extract_features / forward first")
        return pitch_map, non_pitch

    def encode_legal_rows(self, legal_moves: th.Tensor, challenges: th.Tensor) -> th.Tensor:
        """Turn each packed legal-move int row into a float vector (``row_emb``).

        Uses the **same** embedding tables as the board (player index, pitch x/y,
        challenge condition, bonus, block faces) so "player 3 on (4,7)" means the
        same thing on a cell and on a legal row.

        Parameters
        ----------
        legal_moves
            Shape ``(batch, rows, 15)``. Fifteen ints per menu row (schema 25).
            Pad rows are all SENTINEL (-1).
        challenges
            Shape ``(batch, 3, 2)`` active challenge slots on the board. Claim
            rows point at a slot index; we copy that slot's condition+points
            into the row encoding.

        Returns
        -------
        Tensor ``(batch, rows, LEGAL_ROW_FEATURE_DIM)`` — currently 93 floats/row.
        """
        # Split the 15 packed ints into named fields (column order = AiLegalPacker).
        kinds = legal_moves[..., 0].long()
        actor_has_ball = legal_moves[..., 1].float().clamp(0.0, 1.0).unsqueeze(-1)
        source_xs = legal_moves[..., 2].long()
        source_ys = legal_moves[..., 3].long()
        actors = legal_moves[..., 4].long()
        target_has_ball = legal_moves[..., 5].float().clamp(0.0, 1.0).unsqueeze(-1)
        target_xs = legal_moves[..., 6].long()
        target_ys = legal_moves[..., 7].long()
        # Block dice: SENTINEL (-1) = "not a block row" → contribute 0; else scale 1..3 → 0..1.
        block_raw = legal_moves[..., 8].float()
        block_dice = th.where(block_raw < 0, th.zeros_like(block_raw), block_raw.clamp(0.0, 3.0) / 3.0).unsqueeze(-1)
        # Throw success: Kotlin packs 0..100 percent (0 = non-throw) → 0..1.
        throw_success = (legal_moves[..., 9].float().clamp(0.0, 100.0) / 100.0).unsqueeze(-1)
        slots = legal_moves[..., 10].long()
        bonuses = legal_moves[..., 11].long()
        faces = legal_moves[..., 12:15].long()

        kind_e = self.kind_emb(kinds.clamp(min=0, max=LEGAL_ACTION_KIND_COUNT - 1))
        source_feat = th.cat([self._embed_pitch_x(source_xs), self._embed_pitch_y(source_ys)], dim=-1)
        actor_e = self.player_index_emb(self._roster_index(actors))
        target_feat = th.cat([self._embed_pitch_x(target_xs), self._embed_pitch_y(target_ys)], dim=-1)

        # Claim/replace rows carry a challenge slot index; look up that slot on the board.
        # (Todo #3: Kotlin should ship the resolved condition/points so we don't gather here.)
        has_challenge = (slots >= 0) & (slots < ACTIVE_CHALLENGE_SLOTS)
        safe_s = slots.clamp(0, ACTIVE_CHALLENGE_SLOTS - 1)
        slot_idx = safe_s.unsqueeze(-1).expand(-1, -1, 2)
        gathered = th.gather(challenges, 1, slot_idx)
        cond_e = self.challenge_emb(self._enum_index(gathered[..., 0], CHALLENGE_CONDITION_VOCAB))
        points = (gathered[..., 1:2].float().clamp(min=0.0) / _CHALLENGE_POINTS_NORM).clamp(0.0, 1.0)
        # Zero out the challenge features when the row is not a claim (gate).
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

        # Order must match LEGAL_ROW_FEATURE_SEGMENTS (debug |W| meters rely on it).
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
        """Encode turn-level numbers: embed phase, scale the other 16 into ~0..1.

        Input shape ``(batch, 17)``. Phase is a discrete enum; the rest are
        counts/scores that we divide by fixed norms so large and small values
        sit on a similar scale for the Linear layers.
        """
        phase = self.phase_emb(self._enum_index(scalars[:, SCALAR_CH_PHASE], TURN_PHASE_VOCAB))
        rest_idx = [i for i in range(SCALAR_COUNT) if i != SCALAR_CH_PHASE]
        rest = scalars[:, rest_idx]
        norms = th.tensor(_SCALAR_NORMS, device=rest.device, dtype=rest.dtype)
        rest = (rest / norms).clamp(0.0, 1.0)
        return th.cat([phase, rest], dim=1)

    def _encode_challenges(self, challenges: th.Tensor) -> th.Tensor:
        """Encode the three active challenge slots: condition emb + scaled points.

        Input shape ``(batch, 3, 2)``. Flattened so they concatenate with other
        non-pitch features into one long vector per batch item.
        """
        cond = self.challenge_emb(self._enum_index(challenges[:, :, 0], CHALLENGE_CONDITION_VOCAB))
        points = (challenges[:, :, 1:2].float() / _CHALLENGE_POINTS_NORM).clamp(0.0, 1.0)
        return th.cat([cond, points], dim=-1).flatten(1)

    def _encode_deck(self, deck: th.Tensor) -> th.Tensor:
        """Encode the challenge deck top card + deck/discard sizes.

        Input shape ``(batch, 3)`` = top condition, deckSize, discardSize.
        """
        cond = self.challenge_emb(self._enum_index(deck[:, 0], CHALLENGE_CONDITION_VOCAB))
        counts = (deck[:, 1:].float() / _DECK_COUNT_NORM).clamp(0.0, 1.0)
        return th.cat([cond, counts], dim=1)

    def _encode_hand(self, hand: th.Tensor) -> th.Tensor:
        """Encode our bonus-play hand (up to 6 card codes; 0 = empty slot).

        Input shape ``(batch, 6)``. Each code → emb, then flatten.
        """
        return self.bonus_emb(self._enum_index(hand, BONUS_PLAY_VOCAB)).flatten(1)

    def _encode_held_check(self, held: th.Tensor) -> th.Tensor:
        """Encode a mid-check dice window (skill / block face offer), if any.

        Input shape ``(batch, 5)`` = kind, d6, face0, face1, face2. When no check
        is held, Kotlin still packs a fixed-width row of SENTINELs / zeros.
        """
        if held.shape[-1] != HELD_CHECK_FIELD_COUNT:
            raise ValueError(f"held_check width {held.shape[-1]} != {HELD_CHECK_FIELD_COUNT}")
        kind = self.held_kind_emb(self._enum_index(held[:, 0], AI_HELD_CHECK_KIND_VOCAB))
        d6 = (held[:, 1:2].clamp(min=0.0) / _D6_NORM).clamp(0.0, 1.0)
        faces = self.block_face_emb(self._enum_index(held[:, 2:], BLOCK_DIE_FACE_VOCAB)).flatten(1)
        return th.cat([kind, d6, faces], dim=1)

    def _encode_pitch(self, pitch: th.Tensor) -> th.Tensor:
        """Run the pitch through embeddings + CNN; save the map; return GAP summary.

        Input shape ``(batch, channels, height, width)`` = ``(N, 15, 15, 11)``.

        Steps:
        1. Embed each square's 15 pack channels into ``_OCCUPANT_WIDTH`` floats.
        2. Conv2d stack → map ``(N, 128, 15, 11)`` — still one vector per square.
        3. **Save** that map on ``self._pitch_feature_map`` for the policy join.
        4. GAP → average all squares → ``(N, 128)`` for the value trunk only.
        """
        if pitch.shape[1] != NUMBERS_PER_BOARD_CELL:
            raise ValueError(f"pitch channel count {pitch.shape[1]} != {NUMBERS_PER_BOARD_CELL}")
        if pitch.shape[2] != PLAY_HEIGHT or pitch.shape[3] != PLAY_WIDTH:
            raise ValueError(
                f"pitch spatial shape {tuple(pitch.shape[2:])} != ({PLAY_HEIGHT}, {PLAY_WIDTH})"
            )
        # Pack is channel-first for Conv; emb helper wants channel-last per square.
        emb = self._encode_occupants(pitch.permute(0, 2, 3, 1))
        # Back to NCHW for Conv2d; contiguous() makes memory layout happy for ONNX.
        spatial = self.pitch_cnn(emb.permute(0, 3, 1, 2).contiguous())
        self._pitch_feature_map = spatial
        return self.pitch_gap(spatial).flatten(1)

    def _encode_benches(self, benches: th.Tensor) -> th.Tensor:
        """Encode dugout slots (us then them). Not a spatial neighbor of the pitch.

        Input shape ``(batch, 2, 10, channels)``. Same per-slot encoding as a
        pitch square, then flattened into the non-pitch vector.
        """
        return self._encode_occupants(benches).flatten(1)

    def _encode_occupants(self, cell_last: th.Tensor) -> th.Tensor:
        """Encode one packed cell (pitch square or bench slot) into floats.

        ``cell_last`` ends with the channel axis (15 pack ints). Shared by pitch
        and benches so "standing ally with the ball" looks the same in both places.
        """
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
        # Empty-square stats are SENTINEL (-1) on the wire — clamp to 0 then scale.
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
        """Map a perspective column (or SENTINEL) to the shared pitch-x embedding.

        Live values are ``0 .. PLAY_WIDTH-1``. SENTINEL / out-of-range use the
        last embedding row (pad), so empty or "no coordinate" is one learned vector.
        """
        xi = xs.round().long()
        valid = (xi >= 0) & (xi < PLAY_WIDTH)
        idx = th.where(valid, xi, th.full_like(xi, PLAY_WIDTH))
        return self.pitch_x_emb(idx)

    def _embed_pitch_y(self, ys: th.Tensor) -> th.Tensor:
        """Map a perspective row (or SENTINEL) to the shared pitch-y embedding.

        Same pad rule as ``_embed_pitch_x``.
        """
        yi = ys.round().long()
        valid = (yi >= 0) & (yi < PLAY_HEIGHT)
        idx = th.where(valid, yi, th.full_like(yi, PLAY_HEIGHT))
        return self.pitch_y_emb(idx)

    @staticmethod
    def _roster_index(codes: th.Tensor) -> th.Tensor:
        """Wire roster id → embedding row. SENTINEL → row 0 (pad); else 1..MAX."""
        c = codes.round().long()
        return th.where(c < 0, th.zeros_like(c), c.clamp(0, PLAYER_INDEX_VOCAB - 1))

    @staticmethod
    def _enum_index(codes: th.Tensor, vocab: int) -> th.Tensor:
        """Wire SENTINEL (-1) or ordinal 0..n-1 → embedding row (last row = pad)."""
        c = codes.round().long()
        pad = vocab - 1
        live_max = max(vocab - 2, 0)
        return th.where(c < 0, th.full_like(c, pad), c.clamp(0, live_max))
