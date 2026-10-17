package com.mhaefele.gridiron.domain

import com.mhaefele.gridiron.model.MatchProgress
import com.mhaefele.gridiron.model.Score
import com.mhaefele.gridiron.model.addTouchdown
import com.mhaefele.gridiron.model.recordChallengeCardClaim
import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScoreTest {
    @Test
    fun `touchdown adds four points`() {
        assertThat(Score().addTouchdown().points).isEqualTo(4)
    }

    @Test
    fun `lead over opponent`() {
        val home = Score(8)
        val away = Score(4)
        assertThat(home.leadOver(away)).isEqualTo(4)
        assertThat(away.leadOver(home)).isEqualTo(-4)
    }

    @Test
    fun `progress tracks touchdowns and challenge card claims separately from points`() {
        var progress = MatchProgress(turnNumber = 1)
        progress = progress.addTouchdown(TeamSide.HOME)
        progress = progress.recordChallengeCardClaim(TeamSide.HOME, points = 2)
        progress = progress.addTouchdown(TeamSide.AWAY)

        assertThat(progress.homeScore.points).isEqualTo(6)
        assertThat(progress.awayScore.points).isEqualTo(4)
        assertThat(progress.homeTouchdowns).isEqualTo(1)
        assertThat(progress.awayTouchdowns).isEqualTo(1)
        assertThat(progress.homeChallengeCards).isEqualTo(1)
        assertThat(progress.awayChallengeCards).isEqualTo(0)
    }
}
