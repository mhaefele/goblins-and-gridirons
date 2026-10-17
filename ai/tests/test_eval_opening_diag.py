"""Tests for scenario opening-mispick policy diagnostics."""

from __future__ import annotations

import numpy as np

from gridiron_ai.actions import FACTOR_ACTOR_KIND
from gridiron_ai.constants import encode_actor_kind
from gridiron_ai.eval_opening_diag import (
    ball_carrier_player_index,
    decode_actor_kind,
    format_opening_mispick_diag,
    kind_name,
    kind_names_by_index,
)


def test_decode_actor_kind_round_trip() -> None:
    encoded = encode_actor_kind(3, 2)
    assert decode_actor_kind(encoded) == (3, 2)


def test_kind_names_come_from_legal_rows() -> None:
    legal_moves = np.zeros((2, FACTOR_ACTOR_KIND + 1), dtype=np.int64)
    legal_moves[0, FACTOR_ACTOR_KIND] = encode_actor_kind(3, 2)
    legal_moves[1, FACTOR_ACTOR_KIND] = encode_actor_kind(0, 19)
    names = kind_names_by_index(legal_moves, [{"kind": "sidestep"}, {"kind": "playMomentum"}])
    assert kind_name(2, names) == "sidestep"
    assert kind_name(19, names) == "playMomentum"
    assert kind_name(7, names) == "?7"


def test_ball_carrier_player_index_from_cells() -> None:
    obs = {
        "cells": [
            {"hasBall": False, "player": {"teamRel": 0, "playerIndex": 1}},
            {"hasBall": True, "player": {"teamRel": 0, "playerIndex": 4}},
        ]
    }
    assert ball_carrier_player_index(obs) == 4


def test_format_opening_mispick_diag_includes_carrier_mass() -> None:
    line = format_opening_mispick_diag(
        {
            "chosen": "B(9,13->10,12)",
            "chosen_actor_kind": {"actor": 4, "kind": "block", "p_plain": 0.4, "p_biased": 0.35},
            "ball_carrier_player_index": 4,
            "carrier_kind_mass": {
                "sidestep": {"p_plain": 0.2, "p_biased": 0.45},
                "block": {"p_plain": 0.4, "p_biased": 0.35},
            },
            "top_actor_kind": [{"actor": 2, "kind": "block", "p_biased": 0.5}],
            "soft_mask_row_count": 12,
            "legal_count": 80,
            "preferred_actor_kind_count": 3,
            "legal_actor_kind_count": 20,
        }
    )
    assert "policy_diag=" in line
    assert "carrier_p4:" in line
    assert "sidestep=0.45/0.20" in line
    assert "p=0.350(0.400plain)" in line
    assert "sm=12/80" in line
    assert "ak_sm=3/20" in line
