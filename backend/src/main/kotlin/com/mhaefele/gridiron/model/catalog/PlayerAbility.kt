package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.shared.ActionType

/**
 * Typed player ability from team cards. Resolvers match on the enum — never parse [name] in play logic.
 * Enum names are the display titles (the two Relentless variants share a title and are disambiguated here).
 *
 * When implementing any player-ability behavior, read [PlayerAbility.description]
 * and make sure the code matches that wording — do not infer triggers from neighboring abilities or
 * from “after any action” hooks unless the text says so.
 *
 * Engine behavior is aligned under BACKLOG PA.C (done); challenge pairings under D1.C-fix (done).
 */
enum class PlayerAbilityCategory {
    MOVEMENT,
    BLOCK,
    BALL,
    FREE_ACTION,
    /** Re-rolls, modifiers, face remaps, extra dice, or set Check / Block Dice values. */
    CHECK,
    OTHER,
}

enum class PlayerAbility(
    val category: PlayerAbilityCategory,
    /** Player action that must happen first to trigger this ability; `null` if it is not action-triggered. */
    val actionType: ActionType?,
    val description: String,
) {
    `Sure Scoop`(PlayerAbilityCategory.BALL, "Any time this player enters the ball's square, however they got there, they pick the ball up as if they were on a Run Action."),
    `Strike and Fade`(PlayerAbilityCategory.FREE_ACTION, ActionType.BLOCK, "Once this player finishes a Block Action, they may take a free Run Action of at most 2 squares, even if they start it next to an opponent."),
    Disruptor(PlayerAbilityCategory.BLOCK, "Opponents next to this player can never provide an Assist to a Block Action."),
    Elusive(PlayerAbilityCategory.MOVEMENT, "On a Run Action this player may pass next to opponents, as long as they finish the Action Open."),
    Charge(PlayerAbilityCategory.FREE_ACTION, ActionType.MARK, "When this player follows a Run Action with a Mark Action, they get a free Block Action straight away."),
    `Quick Release`(PlayerAbilityCategory.BALL, ActionType.BLOCK, "When an opponent picks this player as a Block target, this player may Interrupt to make a free Short Throw or Hand-off, ignoring all modifiers and even while Marked."),
    `Second Swing`(PlayerAbilityCategory.CHECK, ActionType.BLOCK, "On this player's Block Actions, you may re-roll the Block Dice."),
    /**
     * Shown as "Relentless" — free Block after a Block.
     * Distinct enum from [Relentless After Mark] (same title, different trigger).
     */
    `Relentless After Block`(PlayerAbilityCategory.FREE_ACTION, ActionType.BLOCK, "Once this player finishes a Block Action, they may take a free Block Action against any opponent they are Marking at that point."),
    /**
     * Shown as "Relentless" — free Block after a Mark.
     * Distinct enum from [Relentless After Block] (same title, different trigger).
     */
    `Relentless After Mark`(PlayerAbilityCategory.FREE_ACTION, ActionType.MARK, "Once this player finishes a Mark Action, they may take a free Block Action against any opponent they are Marking at that point."),
    `Ball Guard`(PlayerAbilityCategory.BALL, "If this player is Knocked Down while carrying the ball, skip the D8: you choose the adjacent square the ball Bounces to."),
    `Pinpoint Passer`(PlayerAbilityCategory.CHECK, ActionType.THROW, "This player may re-roll any failed Throw Check."),
    `Catch and Go`(PlayerAbilityCategory.FREE_ACTION, ActionType.THROW, "When this player is Open and receives a successful Throw Action (Hand-offs excluded), they may take a free Run Action."),
    `Wild Ward`(PlayerAbilityCategory.BLOCK, "No Block Action against this player can be Assisted."),
    `Nimble Beast`(PlayerAbilityCategory.MOVEMENT, "On a Run Action this player may pass next to opponents, as long as they finish the Action Open. This player may never take a Throw Action."),
    Colossus(PlayerAbilityCategory.CHECK, "When this player Blocks, Tackle and Smash results count as Flatten!. When this player is the Block target, Shove results count as Miss."),
    `Pass on the Run`(PlayerAbilityCategory.FREE_ACTION, ActionType.RUN, "Once this player finishes a Run Action, they may take a free Short Throw."),
    Anchored(PlayerAbilityCategory.CHECK, ActionType.BLOCK, "When this player is the target of a Block Action, Shove results count as Miss."),
    Enforcer(PlayerAbilityCategory.BLOCK, ActionType.BLOCK, "When this player Blocks, every Block Die rolled is resolved, in an order you pick. Each result that Knocks the target Down forces the opposing coach to make an Armour Check for that target. After a Shove, this player must follow up."),
    Ethereal(PlayerAbilityCategory.MOVEMENT, "On a Run Action this player may move next to or straight through opponents, team-mates and obstructions, but must finish Open, in a square with no player or obstruction."),
    Violent(PlayerAbilityCategory.CHECK, "On this player's Block or Foul Actions, roll an extra D6 alongside the Block Dice. On a 6 the target is Injured outright and the Block Dice are ignored."),
    Rally(PlayerAbilityCategory.FREE_ACTION, "Whenever this player finishes an Action, every Prone team-mate next to them may take a free Stand Up Action."),
    Underfoot(PlayerAbilityCategory.MOVEMENT, "Opponents may Run into a square next to this player, but that ends their Run. When this player Blocks, Tackle results count as Miss."),
    `Heavy Hitter`(PlayerAbilityCategory.CHECK, "When this player Blocks, Smash results count as Flatten!."),
    Undying(PlayerAbilityCategory.FREE_ACTION, "If this player is Prone after your Pre-Turn Sequence, they may take a free Stand Up Action before your first Player Action."),
    `Beneath Notice`(PlayerAbilityCategory.MOVEMENT, "Opponents may Run next to this player without restriction. When this player Blocks, Tackle results count as Miss."),
    Lobber(PlayerAbilityCategory.BLOCK, "This player may Block while Open, choosing an Open or Marked opponent up to 3 squares away as the target. That Block can't be Assisted, and Shove results count as Miss."),
    Vault(PlayerAbilityCategory.MOVEMENT, "On a Run Action this player may move next to and over opponents and team-mates, but must finish the Action Open."),
    Juggernaut(PlayerAbilityCategory.MOVEMENT, "This player may take a Run Action while Marked, may pass next to opponents during it, and may finish either Open or Marked. Nobody can Assist this player's Block Actions."),
    `Dance Step`(PlayerAbilityCategory.FREE_ACTION, "This player may take a free Sidestep Action."),
    Devoted(PlayerAbilityCategory.OTHER, "If a team-mate next to this player would be Injured by their own Thirst ability, you may Injure this player in their place."),
    Thirst(PlayerAbilityCategory.OTHER, ActionType.BLOCK, "After the Claim Challenge Card step of this player's Block Action, if any Block Die rolled showed Miss, this player is Injured and any ball they carry Bounces."),
    ;

    constructor(category: PlayerAbilityCategory, description: String) : this(category, null, description)
}
