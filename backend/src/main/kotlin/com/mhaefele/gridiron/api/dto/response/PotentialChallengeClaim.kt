package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Hint that this legal action could earn an active challenge card.")
data class PotentialChallengeClaim(
    @field:Schema(description = "Claim condition on that challenge card.")
    val claimCondition: ChallengeClaimCondition,
    @field:Schema(description = "Printed challenge title.")
    val challengeTitle: String,
    @field:Schema(description = "Points if claimed.")
    val points: Int,
    @field:Schema(description = "CERTAIN = outcome knowable now; POTENTIAL = still depends on dice or contest.")
    val certainty: ClaimCertainty,
)

@Schema(description = "Whether a potential challenge claim is guaranteed or still uncertain.")
enum class ClaimCertainty {
    CERTAIN,
    POTENTIAL,
}
