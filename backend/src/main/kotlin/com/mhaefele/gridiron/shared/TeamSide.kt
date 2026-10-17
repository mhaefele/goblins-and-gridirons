package com.mhaefele.gridiron.shared

/** Coach A sets up first and is assigned [HOME]. */
enum class TeamSide {
    HOME,
    AWAY,
    ;

    fun opposite(): TeamSide = when (this) {
        HOME -> AWAY
        AWAY -> HOME
    }
}
