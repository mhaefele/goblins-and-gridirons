"""Named shapes for harness JSON — [AI2.22].

Runtime values are still plain dicts from Jackson; these ``TypedDict`` / ``StrEnum``
names document which Kotlin DTO (or closed wire set) a payload matches. Prefer these
over bare ``dict[str, Any]`` / ``str`` at harness call sites.

Scenario *names* are catalog-backed (``ScenarioName`` alias) — do **not** mirror
``TrainingScenarioEnum`` membership here; see ``ai/OWNERSHIP.md``.
"""

from __future__ import annotations

from enum import StrEnum
from typing import NotRequired, TypeAlias, TypedDict

# Catalog-backed drill id (server ``GET /api/ai/training/scenarios``). Not a closed Enum.
ScenarioName: TypeAlias = str


class TeamRel(StrEnum):
    """Kotlin ``AiTeamRel`` — relative to perspective seat."""

    US = "US"
    THEM = "THEM"


class TeamSide(StrEnum):
    """Kotlin ``TeamSide`` — absolute seat on the match."""

    HOME = "HOME"
    AWAY = "AWAY"


class TrainingOutcome(StrEnum):
    """Kotlin ``AiTrainingOutcome`` — coach-window drill grade."""

    US = "US"
    THEM = "THEM"
    DISTRACTED = "DISTRACTED"


class MatchResultKind(StrEnum):
    """Kotlin ``MatchResultKind`` — how a finished match was decided."""

    SUDDEN_DEATH = "suddenDeath"
    POINTS_VICTORY = "pointsVictory"


class EpisodeMode(StrEnum):
    """Training-match create ``episodeMode`` (TRAIN/EVAL)."""

    TRAIN = "TRAIN"
    EVAL = "EVAL"


class ComputerOpponentKind(StrEnum):
    """Wire ``ComputerOpponentKind`` on match create."""

    LOOKAHEAD = "LOOKAHEAD"
    HEURISTIC = "HEURISTIC"
    ONNX = "ONNX"


class ControlledBy(StrEnum):
    """Seat control on match create."""

    HUMAN = "HUMAN"
    HARNESS = "HARNESS"
    COMPUTER = "COMPUTER"


class ScenarioFamily(StrEnum):
    """Kotlin ``TrainingScenarioFamily`` on catalog entries."""

    BOARD = "BOARD"
    CLAIM = "CLAIM"


class CoordinateDict(TypedDict):
    """Absolute playable ``Coordinate`` (1-based)."""

    x: int
    y: int


class HarnessPlayerDict(TypedDict):
    """Kotlin ``HarnessPlayerDto`` — no ball flag; see snapshot ``ballCoordinate``."""

    playerId: str
    playerIndex: int
    teamRel: TeamRel
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
    ballCoordinate: CoordinateDict | None


class PotentialChallengeClaimDict(TypedDict, total=False):
    """Subset of engine ``PotentialChallengeClaim`` JSON that Python reads."""

    certainty: str
    slotIndex: int
    challengeTitle: str
    claimCondition: str


class LegalActionDict(TypedDict):
    """Thin engine ``LegalAction`` JSON — fields the harness actually joins on.

    ``id`` / ``kind`` always present; other keys depend on the subtype.
    Not a full sealed mirror of Kotlin variants.
    """

    id: str
    kind: str
    playerId: NotRequired[str]
    targetCoordinate: NotRequired[CoordinateDict]
    requiredBonusCard: NotRequired[str]
    slotIndex: NotRequired[int]
    potentialChallengeClaims: NotRequired[list[PotentialChallengeClaimDict]]


class AiLegalChoiceDict(TypedDict):
    """Kotlin ``AiLegalChoiceDto`` — action is engine LegalAction JSON."""

    action: LegalActionDict
    passingLegal: bool
    softMaskLegal: bool


class AiScoringDeltaDict(TypedDict):
    """Kotlin ``AiScoringDeltaDto`` — per-submit TD / challenge-card deltas."""

    touchdownsUs: int
    touchdownsThem: int
    challengeCardsUs: int
    challengeCardsThem: int


class SubmitTerminalMetaDict(TypedDict):
    """Episode meta pulled from a packed submit for ``step_reward``."""

    resultKind: MatchResultKind | None
    resultWinnerRel: TeamRel | None
    trainingEpisodeEnded: bool
    trainingOutcome: TrainingOutcome | None


class PackedStepDict(TypedDict):
    """Flattened packed step — policy + harness fields Jackson unwraps on the wire."""

    schemaVersion: int
    obs: list[float]
    legalMoves: list[list[int]]
    rewards: HarnessRewardSnapshotDict
    legalRows: list[AiLegalChoiceDict]
    actingSeat: TeamSide
    perspectiveSeat: TeamSide
    resultKind: MatchResultKind | None
    resultWinnerRel: TeamRel | None
    trainingEpisodeEnded: bool
    trainingOutcome: TrainingOutcome | None


class PackedSubmitDict(TypedDict):
    """Kotlin ``AiSubmitResultDto`` (packed) — ``next`` still nested before flatten."""

    schemaVersion: int
    chosenLegalActionId: str
    scoring: AiScoringDeltaDict
    afterSubmitRewards: HarnessRewardSnapshotDict | None
    next: PackedStepDict


class ScenarioCatalogEntryDict(TypedDict):
    """One row from ``GET /api/ai/training/scenarios`` (and catalog cache)."""

    name: ScenarioName
    family: ScenarioFamily
    difficultyProxy: NotRequired[ScenarioName]
    gatesProgress: NotRequired[bool]


class ScenarioCatalogCacheDict(TypedDict):
    """``output/scenario_catalog.json`` envelope."""

    schemaVersion: int
    scenarios: list[ScenarioCatalogEntryDict]
