package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.DomainId
import com.mhaefele.gridiron.shared.requireDomainId

/** Catalog key for one physical challenge/bonus card instance. */
data class ChallengeCardId(override val value: String) : DomainId {
    init {
        requireDomainId(value)
    }
}

/**
 * Challenge face title (red footer). Multiple physical cards may share a title with different
 * bonus reverses — claim logic matches on [ChallengeClaimCondition], not instance id.
 */
enum class ChallengeClaimCondition(
    val displayName: String,
    val points:      Int,
    val description: String,
) {
    GRAB_IT                  ("Grab It!",                1, "Claim if one of your players gained possession of a ball that your team did not hold when your turn began."),
    HUDDLE_UP                ("Huddle Up!",              1, "Claim after one of your players makes a Run, Mark or Sidestep Action and ends next to at least two team-mates."),
    PUSH_FORWARD             ("Push Forward!",           1, "Claim if, during one of your Throw or Run Actions, a ball moved at least 4 squares farther from your own End Zone and one of your players still has it."),
    FAN_OUT                  ("Fan Out!",                1, "Claim after one of your players makes a Run or Sidestep Action if none of your players is next to any team-mate."),
    PLAY_TO_THE_STANDS       ("Play to the Stands!",     1, "Claim if an Action by one of your players led to your team scoring a Touchdown."),
    CONNECT                  ("Connect!",                1, "Claim if one of your players completed a Throw Action (Hand-offs don't count)."),
    STRIP_IT                 ("Strip It!",               2, "Claim if an opponent held a ball and, after one of your players' Actions, the opposing team no longer has it."),
    KEEP_RUNNING             ("Keep Running!",           2, "Claim if one of your players made a Run Action and your team has now made at least three Run Actions this turn."),
    HOLD_THE_PIT             ("Hold the Pit!",           2, "Claim if one of your players finishes an Action on a Trapdoor while an opponent holds a ball."),
    THREAD_THE_NEEDLE        ("Thread the Needle!",      2, "Claim if one of your players completed a Throw Action despite a negative modifier on the Throw Check."),
    ALL_HANDS                ("All Hands!",              2, "Claim if, this turn, your team has made at least three different kinds of Action using at least three different players."),
    STAR_TURN                ("Star Turn!",              2, "Claim if one of your players made an Action and has now made at least three different Player Actions this turn (free Actions don't count)."),
    DROP_THEM                ("Drop Them!",              2, "Claim if one of your players made a Block Action that Knocked the target Down."),
    LOCK_THEM_DOWN           ("Lock Them Down!",         2, "Claim if one of your players made a Mark Action and is now Marking two or more opponents that no other player of yours is Marking."),
    SEND_THEM_OFF            ("Send Them Off!",          3, "Claim if a Block or Foul Action by one of your players Injured the target."),
}

enum class BonusPlayCategory {
    INTERRUPT,
    FREE_ACTION,
    ACTION_MODIFIER,
    /** Re-rolls, modifiers, extra dice, or set Check / Block Dice values. */
    CHECK,
    TURN,
    OTHER,
}

/** Bonus play reverse (blue footer). Resolvers match on the enum — never parse [name]. Enum names are the display titles. */
enum class BonusPlay(
    val category: BonusPlayCategory,
    /** Player action that must happen first to trigger this bonus; `null` if it is not action-triggered. */
    val actionType: ActionType?,
    val description: String,
) {
    `Stick Close`(BonusPlayCategory.INTERRUPT, ActionType.SIDESTEP, "Interrupt an opponent's Sidestep Action. Choose one of your players who was Marking that opponent before the Sidestep; move them into the square the opponent just left, next to the opponent."),
    `Slip Away`(BonusPlayCategory.INTERRUPT, ActionType.MARK, "Interrupt right after an opponent's Mark Action. Choose one of your players that opponent is now Marking; they may take a free Sidestep Action."),
    `Fresh Legs`(BonusPlayCategory.FREE_ACTION, "Before one of your Player Actions, a player from your Dugout may take a free Reserves Action."),
    Weave(BonusPlayCategory.ACTION_MODIFIER, "Before one of your Run Actions: during that Run the player may pass next to opponents, but must still finish Open."),
    `Do-Over`(BonusPlayCategory.CHECK, "Right after your team rolls an Armour Check, a Throw Check, or the Block Dice for one of your Block Actions: re-roll those dice."),
    `Hail Mary Pass`(BonusPlayCategory.CHECK, ActionType.THROW, "When one of your players takes a Throw Action (not Throw Team-mate): any team-mate on the board can be the target, and the Throw Check passes on 4+ with no modifiers."),
    `Extra Effort`(BonusPlayCategory.TURN, "After the Claim Challenge Card step of your third Player Action: take a fourth Player Action. It is still part of your turn and uses all the normal rules for Actions and claiming Challenge Cards."),
    `Ice Cold`(BonusPlayCategory.ACTION_MODIFIER, "Before one of your Player Actions: for that Action only, the player may Throw (including Throw Team-mate) while Marked."),
    Momentum(BonusPlayCategory.FREE_ACTION, ActionType.MARK, "After one of your players makes a Mark Action, if they already took a Run Action this turn: they may take a free Block Action against an opponent they are Marking."),
    `Cut Off`(BonusPlayCategory.INTERRUPT, ActionType.RUN, "Interrupt right after an opponent's Run Action. One of your Open players may take a free Mark Action, which must end next to the opponent who ran."),
    Sprint(BonusPlayCategory.ACTION_MODIFIER, "Before one of your Run Actions: the player gets +2 Move for that Run."),
    Press(BonusPlayCategory.ACTION_MODIFIER, "Before one of your Mark Actions: for that Action the player may move up to 4 squares rather than 2."),
    `Back on Your Feet`(BonusPlayCategory.FREE_ACTION, "Choose one of your Prone players; they may take a free Stand Up Action."),
    `Dead Eye`(BonusPlayCategory.CHECK, "Before one of your Throw Actions: the thrower's Throw value counts as 2+ for that Throw."),
    Brace(BonusPlayCategory.INTERRUPT, ActionType.BLOCK, "Interrupt when one of your players is picked as a Block target, before the dice are rolled: that Block can't be Assisted, and Shove results count as Miss."),
    `Spiked Gauntlets`(BonusPlayCategory.CHECK, ActionType.BLOCK, "After an opponent is Knocked Down by a Block Action: their Armour value is 6+ for that Armour Check."),
    Underdog(BonusPlayCategory.CHECK, ActionType.BLOCK, "When one of your players makes an unassisted Block Action: roll 3 Block Dice for it."),
    `All-In Tackle`(BonusPlayCategory.FREE_ACTION, ActionType.MARK, "After one of your players makes a Mark Action: they may take a free Block Action, then are placed Prone (no Armour Check for them)."),
    `Hold the Line`(BonusPlayCategory.TURN, "After your Pre-Turn Sequence, before your first Player Action: this turn your players may Run into squares next to opponents, but doing so ends that Run immediately."),
    `Gang Up`(BonusPlayCategory.TURN, "At the start of your turn, before the Pre-Turn Sequence: every Block Action your players make this turn counts as Assisted."),
    ;

    constructor(category: BonusPlayCategory, description: String) : this(category, null, description)
}


/**
 * One physical card: challenge face + paired bonus reverse.
 * Pairings are listed in [`reference/cards.md`](../../../../../../../../../reference/cards.md).
 * [deckIndex] is the stable catalog key (c01–c26).
 */
data class ChallengeCardDefinition(
    val id: ChallengeCardId,
    /** Stable catalog index 1–26 (id `cNN`). */
    val deckIndex: Int,
    val challenge: ChallengeClaimCondition,
    val bonusPlay: BonusPlay,
) {
    val points: Int get() = challenge.points
}
