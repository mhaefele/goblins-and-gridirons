package com.mhaefele.gridiron.game.dice

/**
 * Re-roll rules (digest § Checks): own rolls only; no re-roll of a re-roll; must accept the re-roll result.
 * Do-Over and other grants land in deferred phases — this tracks eligibility only.
 */
data class ReRollState(
    val eligibleRollIds: Set<RollId> = emptySet(),
    val reRolledRollIds: Set<RollId> = emptySet(),
)

@JvmInline
value class RollId(val value: String)

sealed interface ReRollResult {
    data class Allowed(val state: ReRollState) : ReRollResult

    data class Denied(val reason: String) : ReRollResult
}

fun ReRollState.registerRoll(rollId: RollId): ReRollState =
    copy(eligibleRollIds = eligibleRollIds + rollId)

/** Request a re-roll for [rollId]. Returns [ReRollResult.Denied] when policy forbids it. */
fun ReRollState.requestReRoll(rollId: RollId): ReRollResult {
    if (rollId !in eligibleRollIds) {
        return ReRollResult.Denied("Roll is not eligible for re-roll")
    }
    if (rollId in reRolledRollIds) {
        return ReRollResult.Denied("Cannot re-roll a re-roll")
    }
    return ReRollResult.Allowed(
        copy(
            reRolledRollIds = reRolledRollIds + rollId,
            eligibleRollIds = eligibleRollIds - rollId,
        ),
    )
}

/** After a re-roll completes, the new result stands — original roll id is consumed, not re-eligible. */
fun ReRollState.completeReRoll(newRollId: RollId): ReRollState =
    copy(eligibleRollIds = eligibleRollIds + newRollId)
