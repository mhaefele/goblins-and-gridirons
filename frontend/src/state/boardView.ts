import type { MatchGameStateDto, MatchSnapshotDto, PieceSnapshotDto } from "../api/client";

export type BoardView = {
    pieces: PieceSnapshotDto;
    gameState: MatchGameStateDto;
    lastEventId: number;
};

export function boardViewFromSnapshot(snapshot: MatchSnapshotDto): BoardView {
    return {
        pieces: snapshot.pieces,
        gameState: snapshot.gameState,
        lastEventId: snapshot.lastEventId,
    };
}
