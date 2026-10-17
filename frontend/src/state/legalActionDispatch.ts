import {
    postSubmitAction,
    type LegalActionWire,
    type MatchActionResponse,
    type MatchId,
} from "../api/client";

export async function dispatchLegalAction(matchId: MatchId, action: LegalActionWire): Promise<MatchActionResponse> {
    const id = action.id;
    if (id == null) {
        throw new Error("Cannot submit a legal action without an id");
    }
    return postSubmitAction(matchId, id);
}
