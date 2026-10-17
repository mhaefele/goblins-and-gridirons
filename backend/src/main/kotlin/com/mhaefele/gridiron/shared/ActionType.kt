package com.mhaefele.gridiron.shared

enum class ActionType {
    RUN,
    MARK,
    THROW,
    /** Ogre / Troll / Oakwalker throw an adjacent smaller team-mate (digest § Throw Team-mate). */
    THROW_TEAMMATE,
    BLOCK,
    /** Open player vs adjacent Prone opposition (digest § Foul). */
    FOUL,
    SIDESTEP,
    STAND_UP,
    RESERVES,
    SKIP,
    /** Decline Throw Team-mate after selecting a team-mate — restores the regular action pick. */
    CANCEL_THROW_TEAMMATE,
    CHOOSE_BLOCK_REROLL,
    CHOOSE_BLOCK_FACE_ORDER,
    CHOOSE_THROW_REROLL,
    /** Do-Over — re-roll a held throw check. */
    ADJUST_THROW_BY_DO_OVER,
    /** Do-Over — re-roll held block dice. */
    ADJUST_BLOCK_BY_DO_OVER,
    /** Do-Over — re-roll a held armour check. */
    ADJUST_ARMOUR_BY_DO_OVER,
    PLACE_LOOSE_BALL,
    CONTINUE_BLOCK,
    QUICK_RELEASE_THROW,
    PLAY_GANG_UP,
    PLAY_HOLD_THE_LINE,
    PLAY_EXTRA_EFFORT,
    PLAY_MOMENTUM,
    PLAY_ALL_IN_TACKLE,
    PLAY_SLIP_AWAY,
    PLAY_CUT_OFF,
    PLAY_STICK_CLOSE,
    PLAY_BACK_ON_YOUR_FEET,
    PLAY_FRESH_LEGS,
    PLAY_BRACE,
    PLAY_UNDERDOG,
    PLAY_SPIKED_GAUNTLETS,
    PLAY_DEAD_EYE,
    PLAY_HAIL_MARY,
    PLAY_SPRINT,
    CLAIM_CHALLENGE,
    REPLACE_CHALLENGE,
}
