package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.api.dto.response.PieceSnapshotDto
import com.mhaefele.gridiron.api.dto.response.PlayerPieceDto
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId

/**
 * Test oracle for [DomainEvent] → [PieceSnapshotDto] patches — mirrors what clients apply locally.
 *
 * | Event | Piece snapshot patch |
 * |-------|----------------------|
 * | ActionStarted | no-op (action bracket) |
 * | ActionComplete | no-op (action bracket) |
 * | DiceRolled | no-op (check UI only) |
 * | PlayerMoved | move `at` for [DomainEvent.PlayerMoved.playerId] |
 * | PlayerStatusChanged | set `status` for player |
 * | PlayerDeployedFromReserves | `STANDING` at deployment square |
 * | PlayerInjured | `RESERVES`, `at = null` |
 * | PlayerRemovedAfterTouchdown | `RESERVES`, `at = null` |
 * | PlayerPlacedOnPitch | `STANDING` at square |
 * | BallPickedUp | `holdsBall = true`; remove loose ball at square |
 * | BallDropped | `holdsBall = false`; add loose ball at square |
 * | BallThrown | clear `holdsBall` at `from`, set at `to` |
 * | BallThrowMissed | clear `holdsBall` at `from`; loose ball at `to` |
 * | BallBounced | move loose ball `from` → `to` when they differ |
 * | BallPlacedLoose | add loose ball at `at` |
 * | BallRemovedFromPlay | clear `holdsBall` for [DomainEvent.BallRemovedFromPlay.playerId] or loose at [DomainEvent.BallRemovedFromPlay.at] |
 * | TouchdownScored | no-op on pieces (score only; ball + dugout follow as separate events) |
 * | PlayerTurnEnded | no-op (turn clock — not on piece snapshot) |
 * | TeamActionConsumed / PhaseStarted / MatchEnded | no-op (HUD — not on piece snapshot) |
 * | InputRequired | no-op (protocol — not a board patch) |
 */
fun applyPieceSnapshotPatch(snapshot: PieceSnapshotDto, event: DomainEvent): PieceSnapshotDto =
    when (event) {
        is DomainEvent.ActionStarted,
        is DomainEvent.ActionComplete,
        is DomainEvent.DiceRolled,
        is DomainEvent.TouchdownScored,
        is DomainEvent.ChallengeTableauRefreshed,
        is DomainEvent.ChallengeCardReplaced,
        is DomainEvent.ChallengeCardClaimed,
        is DomainEvent.CleanSweep,
        is DomainEvent.ChallengeHandTrimmed,
        is DomainEvent.BonusPlayed,
        is DomainEvent.BonusReturned,
        is DomainEvent.PointsVictoryArmed,
        is DomainEvent.PointsVictoryCountdown,
        is DomainEvent.PlayerTurnEnded,
        is DomainEvent.TeamActionConsumed,
        is DomainEvent.PhaseStarted,
        is DomainEvent.MatchEnded,
        is DomainEvent.InputRequired -> snapshot

        is DomainEvent.PlayerMoved                -> snapshot.updatePlayer(event.playerId) { it.copy(at = event.to) }

        is DomainEvent.PlayerStatusChanged        -> snapshot.updatePlayer(event.playerId) { it.copy(status = event.to) }

        is DomainEvent.PlayerDeployedFromReserves -> snapshot.updatePlayer(event.playerId) { it.copy(status = PlayerStatus.STANDING, at = event.to) }

        is DomainEvent.PlayerInjured              -> snapshot.updatePlayer(event.playerId) { it.copy(status = PlayerStatus.RESERVES, at = null) }

        is DomainEvent.PlayerRemovedAfterTouchdown -> snapshot.updatePlayer(event.playerId) { it.copy(status = PlayerStatus.RESERVES, at = null) }

        is DomainEvent.PlayerPlacedOnPitch         -> snapshot.updatePlayer(event.playerId) { it.copy(status = PlayerStatus.STANDING, at = event.at) }

        is DomainEvent.BallPickedUp               -> snapshot
            .updatePlayer(event.playerId) { it.copy(holdsBall = true) }
            .withoutLooseBallAt(event.playerAt)

        is DomainEvent.BallDropped                -> snapshot
            .updatePlayer(event.playerId) { it.copy(holdsBall = false) }
            .withLooseBallAt(event.playerAt)

        is DomainEvent.BallThrown                 -> snapshot.copy(
            players = snapshot.players.map { player ->
                when (player.at) {
                    event.from -> player.copy(holdsBall = false)
                    event.to   -> player.copy(holdsBall = true)
                    else       -> player
                }
            },
        )

        is DomainEvent.BallThrowMissed            -> snapshot
            .clearHoldsBallAt(event.from)
            .withoutLooseBallAt(event.from)
            .withLooseBallAt(event.to)

        is DomainEvent.BallBounced                -> if (event.from == event.to) {
            snapshot
        } else {
            snapshot
                .withoutLooseBallAt(event.from)
                .withLooseBallAt(event.to)
        }

        is DomainEvent.BallPlacedLoose            -> snapshot.withLooseBallAt(event.at)

        is DomainEvent.BallRemovedFromPlay -> {
            var patched = snapshot
            event.playerId?.let { playerId ->
                patched = patched.updatePlayer(playerId) { it.copy(holdsBall = false) }
            }
            event.at?.let { at ->
                patched = patched.withoutLooseBallAt(at)
            }
            patched
        }
    }

fun replayPieceSnapshot(initial: PieceSnapshotDto, events: List<DomainEvent>): PieceSnapshotDto =
    events.fold(initial, ::applyPieceSnapshotPatch)

private fun PieceSnapshotDto.updatePlayer(
    playerId: PlayerId,
    transform: (PlayerPieceDto) -> PlayerPieceDto,
): PieceSnapshotDto =
    copy(players = players.map { if (it.id == playerId) transform(it) else it })

private fun PieceSnapshotDto.withLooseBallAt(at: Coordinate): PieceSnapshotDto =
    copy(looseBallLocations = (looseBallLocations + at).sortedDistinct())

private fun PieceSnapshotDto.withoutLooseBallAt(at: Coordinate): PieceSnapshotDto =
    copy(looseBallLocations = looseBallLocations.filter { it != at })

private fun PieceSnapshotDto.clearHoldsBallAt(at: Coordinate): PieceSnapshotDto =
    copy(
        players = players.map { player ->
            if (player.at == at) player.copy(holdsBall = false) else player
        },
    )

private fun List<Coordinate>.sortedDistinct(): List<Coordinate> =
    distinct().sortedWith(compareBy({ it.x }, { it.y }))
