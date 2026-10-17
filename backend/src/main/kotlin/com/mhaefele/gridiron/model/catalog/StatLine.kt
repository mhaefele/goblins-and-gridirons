package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.model.pending.RunContinuation
/** Move allowance — fixed squares or roll D6 at the start of each Run action. */
sealed interface MoveValue {
    data class Fixed(val squares: Int) : MoveValue {
        init {
            require(squares > 0) { "Move must be positive" }
        }
    }

    /** Roll D6 at the start of each Run, then move up to that many squares (PM.3). */
    data object RandomD6 : MoveValue
}

/** Throw check target from team card (e.g. 4+ → minimum roll of 4). `-` means cannot hold the ball. */
sealed interface ThrowStat {
    data class Threshold(val minimumRoll: Int) : ThrowStat {
        init {
            require(minimumRoll in 2..6) { "Throw threshold must be 2–6" }
        }
    }

    data object Unthrowable : ThrowStat
}

fun ThrowStat.canHoldBall(): Boolean = this !is ThrowStat.Unthrowable

/** Armour check target from team card. `-` means auto-fail without rolling. */
sealed interface ArmourStat {
    data class Threshold(val minimumRoll: Int) : ArmourStat {
        init {
            require(minimumRoll in 2..6) { "Armour threshold must be 2–6" }
        }
    }

    data object NoArmour : ArmourStat
}

fun ArmourStat.failsAutomatically(): Boolean = this is ArmourStat.NoArmour

/** Fixed Move allowance for Run pathfinding. RandomD6 uses roll-then-path ([RunContinuation]). */
fun StatLine.runMoveAllowance(): Int =
    when (val m = move) {
        is MoveValue.Fixed -> m.squares
        MoveValue.RandomD6 -> error("RandomD6 has no fixed Run allowance — roll then choose a path")
    }

data class StatLine(
    val move: MoveValue,
    val blockDiceCount: Int,
    val throwStat: ThrowStat,
    val armour: ArmourStat,
) {
    init {
        require(blockDiceCount > 0) { "Block dice count must be positive" }
    }
}
