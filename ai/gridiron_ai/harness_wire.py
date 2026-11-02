"""Named shapes for harness JSON — beachhead for [AI2.22].

Runtime values are still plain dicts from Jackson; these ``TypedDict`` names
document which Kotlin DTO a payload matches. Expand call-site annotations
under AI2.22 rather than inventing a second parallel schema.
"""

from __future__ import annotations

from typing import NotRequired, TypedDict


class CoordinateDict(TypedDict):
    """Absolute playable ``Coordinate`` (1-based)."""

    x: int
    y: int


class HarnessPlayerDict(TypedDict):
    """Kotlin ``HarnessPlayerDto`` — no ball flag; see snapshot ``ballCoordinate``."""

    playerId: str
    playerIndex: int
    teamRel: str
    coordinate: CoordinateDict | None


class HarnessRewardSnapshotDict(TypedDict):
    """Kotlin ``HarnessRewardSnapshotDto`` — one ball max (null if none on pitch)."""

    turnNumber: int
    scoreUs: int
    scoreThem: int
    touchdownsUs: int
    touchdownsThem: int
    challengeCardsUs: int
    challengeCardsThem: int
    players: list[HarnessPlayerDict]
    ballCoordinate: NotRequired[CoordinateDict | None]


class AiLegalChoiceDict(TypedDict):
    """Kotlin ``AiLegalChoiceDto`` — action is engine LegalAction JSON."""

    action: dict  # LegalAction sealed JSON; refine under AI2.22
    passingLegal: bool
    softMaskLegal: bool
