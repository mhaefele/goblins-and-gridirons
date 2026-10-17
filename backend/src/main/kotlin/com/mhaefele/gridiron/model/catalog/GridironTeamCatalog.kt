package com.mhaefele.gridiron.model.catalog

/**
 * All 23 built-in team cards.
 * Roster counts follow each card's team size and position labels (e.g. three linemen).
 */
internal object GridironTeamCatalog {

    fun allTeams(): List<TeamCard> = listOf(
        amazon(),
        ironhide(),
        doomsworn(),
        darkElf(),
        dwarves(),
        silverleaf(),
        gnome(),
        goblin(),
        halfling(),
        human(),
        gildedCrown(),
        bloodsworn(),
        scalekin(),
        graveborn(),
        norse(),
        rotsworn(),
        ogre(),
        orcs(),
        restlessDead(),
        ratfolk(),
        gremlin(),
        vampire(),
        woodElf(),
    )

    fun team(id: TeamCatalogId): TeamCard =
        allTeams().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown team id: ${id.value}")

    fun team(id: String): TeamCard = team(TeamCatalogId(id))

    fun amazon(): TeamCard = team(
        id = "amazon",
        name = "Amazon Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Linewoman", 6, 4, 4)) + thrower("Thrower", 6, 3, 4, PlayerAbility.`Sure Scoop`) + profile("Blitzer", 7, 1, 4, 4, PlayerAbility.`Strike and Fade`) + blocker("Blocker", 6, 4, 3, PlayerAbility.Disruptor),)

    fun ironhide(): TeamCard = team(
        id = "ironhide",
        name = "Ironhide Team",
        emergencyReserves = 4,
        roster = repeat(4, profile("Goblin", 6, 1, 4, 6, PlayerAbility.Elusive)) + repeat(2, profile("Ironhide", 4, 2, 5, 2)),)

    fun doomsworn(): TeamCard = team(
        id = "doomsworn",
        name = "Doomsworn Team",
        emergencyReserves = 4,
        roster = repeat(4, profile("Beastkin", 6, 1, 4, 4, PlayerAbility.Charge)) + repeat(2, profile("Sworn Blocker", 5, 2, 5, 2)),)

    fun darkElf(): TeamCard = team(
        id = "dark-elf",
        name = "Dark Elf Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 6, 4, 3)) + profile("Runner", 7, 1, 3, 4, PlayerAbility.`Quick Release`) + blitzer("Blitzer", 7, 4, 3) + profile("Bloodblade", 7, 1, 5, 4, PlayerAbility.`Relentless After Mark`),)

    fun dwarves(): TeamCard = team(
        id = "dwarves",
        name = "Dwarf Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 4, 5, 2)) + profile("Runner", 6, 1, 4, 3, PlayerAbility.`Ball Guard`) + blitzer("Blitzer", 5, 4, 2) + profile("Doom Seeker", 5, 1, 6, 4, PlayerAbility.`Relentless After Mark`),)

    fun silverleaf(): TeamCard = team(
        id = "silverleaf",
        name = "Silverleaf Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 6, 3, 4)) + thrower("Thrower", 6, 2, 4, PlayerAbility.`Pinpoint Passer`) + profile("Catcher", 8, 1, 3, 5, PlayerAbility.`Catch and Go`) + blitzer("Blitzer", 7, 3, 3),)

    fun gnome(): TeamCard = team(
        id = "gnome",
        name = "Gnome Team",
        emergencyReserves = 4,
        roster = repeat(4, lineman("Lineman", 5, 4, 6)) + profile("Illusionist", 5, 1, 3, 6, PlayerAbility.`Quick Release`) + profile("Beastmaster", 5, 1, 4, 4, PlayerAbility.`Wild Ward`) + profile("Woodland Fox", 7, 1, 6, 6, PlayerAbility.`Nimble Beast`) + profile("Oakwalker", 2, 2, 5, 2, PlayerAbility.Colossus, canThrowTeammate = true),)

    fun goblin(): TeamCard = team(
        id = "goblin",
        name = "Goblin Team",
        emergencyReserves = 4,
        roster = repeat(6, profile("Goblin", 6, 1, 4, 6, PlayerAbility.Elusive)) + profile("Troll", 4, 2, 5, 2, PlayerAbility.Colossus, canThrowTeammate = true),)

    fun halfling(): TeamCard = team(
        id = "halfling",
        name = "Halfling Team",
        emergencyReserves = 2,
        roster = repeat(4, lineman("Lineman", 5, 4, 6)) + profile("Catcher", 5, 1, 4, 6, PlayerAbility.`Catch and Go`) + profile("Hefty", 5, 1, 4, 4, PlayerAbility.Anchored) + profile("Oakwalker", 2, 2, 5, 2, PlayerAbility.Colossus, canThrowTeammate = true),)

    fun human(): TeamCard = team(
        id = "human",
        name = "Human Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 6, 4, 3)) + thrower("Thrower", 6, 3, 3, PlayerAbility.`Sure Scoop`) + profile("Catcher", 8, 1, 4, 5, PlayerAbility.`Catch and Go`) + blitzer("Blitzer", 7, 4, 3),)

    fun gildedCrown(): TeamCard = team(
        id = "gilded-crown",
        name = "Gilded Crown Team",
        emergencyReserves = 3,
        roster = repeat(3, lineman("Lineman", 6, 4, 5)) + thrower("Thrower", 6, 3, 4, PlayerAbility.`Pass on the Run`) + profile("Bodyguard", 6, 1, 5, 3, PlayerAbility.Anchored) + blitzer("Blitzer", 7, 4, 3),)

    fun bloodsworn(): TeamCard = team(
        id = "bloodsworn",
        name = "Bloodsworn Team",
        emergencyReserves = 4,
        roster = repeat(3, profile("Marauder", 6, 1, 4, 4)) + repeat(2, profile("Gorehorn", 6, 1, 4, 4, PlayerAbility.Charge)) + profile("Skullreaver", 5, 2, 6, 3, PlayerAbility.Enforcer),)

    fun scalekin(): TeamCard = team(
        id = "scalekin",
        name = "Scalekin Team",
        emergencyReserves = 5,
        roster = repeat(4, profile("Darter", 8, 1, 4, 6, PlayerAbility.`Ball Guard`)) + profile("Shade Darter", 7, 1, 3, 6, PlayerAbility.`Catch and Go`) + profile("Scalebrute", 6, 2, 6, 2),)

    fun graveborn(): TeamCard = team(
        id = "graveborn",
        name = "Graveborn Team",
        emergencyReserves = 5,
        roster = repeat(3, profile("Zombie", 4, 1, 6, 3)) + profile("Ghoul", 7, 1, 4, 4, PlayerAbility.`Ball Guard`) + profile("Wraith", MoveValue.Fixed(6), 1, ThrowStat.Unthrowable, ArmourStat.Threshold(3), PlayerAbility.Ethereal) + profile("Werewolf", 8, 1, 4, 3, PlayerAbility.Violent) + profile("Flesh Golem", 4, 2, 6, 2, PlayerAbility.Anchored),)

    fun norse(): TeamCard = team(
        id = "norse",
        name = "Norse Team",
        emergencyReserves = 3,
        roster = repeat(3, lineman("Lineman", 6, 4, 4)) + profile("Ale Hog", MoveValue.Fixed(5), 1, ThrowStat.Unthrowable, ArmourStat.Threshold(6), PlayerAbility.Rally) + blitzer("Berserker", 6, 4, 4) + profile("Valkyrie", 7, 1, 3, 4, PlayerAbility.`Ball Guard`) + profile("Wolfcoat", 6, 2, 6, 3, PlayerAbility.`Relentless After Mark`),)

    fun rotsworn(): TeamCard = team(
        id = "rotsworn",
        name = "Rotsworn Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 5, 6, 4)) + repeat(2, profile("Plaguehorn", 6, 1, 4, 3, PlayerAbility.Charge)) + profile("Pustule Brute", 4, 2, 6, 3, PlayerAbility.Anchored),)

    fun ogre(): TeamCard = team(
        id = "ogre",
        name = "Ogre Team",
        emergencyReserves = 4,
        roster = repeat(6, profile("Grubling", 5, 1, 5, 6, PlayerAbility.Underfoot)) + repeat(2, profile("Ogre", 5, 2, 5, 2, PlayerAbility.Colossus, canThrowTeammate = true)),)

    fun orcs(): TeamCard = team(
        id = "orcs",
        name = "Orc Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 5, 4, 3)) + thrower("Thrower", 5, 3, 3, PlayerAbility.`Sure Scoop`) + blitzer("Blitzer", 6, 4, 2) + profile("Bruiser", 5, 2, 6, 2, PlayerAbility.`Heavy Hitter`),)

    fun restlessDead(): TeamCard = team(
        id = "restless-dead",
        name = "Restless Dead Team",
        emergencyReserves = 4,
        roster = repeat(3, profile("Zombie", 4, 1, 6, 3)) + profile("Skeleton", 5, 1, 6, 4, PlayerAbility.Undying) + profile("Ghoul", 7, 1, 4, 4, PlayerAbility.`Ball Guard`) + blitzer("Wight", 6, 5, 3) + profile("Mummy", 3, 2, 6, 2, PlayerAbility.`Heavy Hitter`),)

    fun ratfolk(): TeamCard = team(
        id = "ratfolk",
        name = "Ratfolk Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 7, 4, 4)) + thrower("Thrower", 7, 3, 4, PlayerAbility.`Sure Scoop`) + blitzer("Blitzer", 7, 5, 3) + profile("Sewer Runner", 9, 1, 4, 5, PlayerAbility.`Ball Guard`),)

    fun gremlin(): TeamCard = team(
        id = "gremlin",
        name = "Gremlin Team",
        emergencyReserves = 5,
        roster = repeat(6, profile("Gremlin", 5, 1, 5, noArmour = true, ability = PlayerAbility.`Beneath Notice`)) + profile("Spore Slinger", 5, 1, 4, noArmour = true, ability = PlayerAbility.Lobber) + profile("Bounder", 6, 1, 5, noArmour = true, ability = PlayerAbility.Vault) + profile("Rattle Cart", MoveValue.RandomD6, 3, ThrowStat.Unthrowable, ArmourStat.Threshold(3), PlayerAbility.Juggernaut) + profile("Stilt Runner", 7, 1, 5, noArmour = true),)

    fun vampire(): TeamCard = team(
        id = "vampire",
        name = "Vampire Team",
        emergencyReserves = 4,
        roster = repeat(4, profile("Thrall", 6, 1, 5, 6, PlayerAbility.Devoted)) + profile("Runner", 8, 1, 4, 3, PlayerAbility.Thirst) + profile("Thrower", 6, 2, 2, 3, PlayerAbility.Thirst) + profile("Blitzer", 6, 2, 5, 3, PlayerAbility.Thirst),)

    fun woodElf(): TeamCard = team(
        id = "wood-elf",
        name = "Wood Elf Team",
        emergencyReserves = 4,
        roster = repeat(3, lineman("Lineman", 7, 4, 5)) + thrower("Thrower", 7, 2, 5, PlayerAbility.`Sure Scoop`) + profile("Catcher", 8, 1, 4, 6, PlayerAbility.`Catch and Go`) + profile("Glade Dancer", 8, 1, 4, 4, PlayerAbility.`Dance Step`),)

    private fun team(
        id: String,
        name: String,
        emergencyReserves: Int,
        roster: List<PositionProfile>,
    ): TeamCard = TeamCard(id = TeamCatalogId(id), name = name, emergencyReserves = emergencyReserves, roster = roster)

    private fun repeat(count: Int, profile: PositionProfile): List<PositionProfile> =
        List(count) { profile }

    private fun lineman(name: String, move: Int, throwThreshold: Int, armour: Int): PositionProfile =
        profile(name, move, 1, throwThreshold, armour)

    private fun thrower(
        name: String,
        move: Int,
        throwThreshold: Int,
        armour: Int,
        ability: PlayerAbility,
    ): PositionProfile = profile(name, move, 1, throwThreshold, armour, ability)

    private fun blitzer(name: String, move: Int, throwThreshold: Int, armour: Int): PositionProfile =
        profile(name, move, 1, throwThreshold, armour, PlayerAbility.`Second Swing`)

    private fun blocker(name: String, move: Int, throwThreshold: Int, armour: Int, ability: PlayerAbility): PositionProfile =
        profile(name, move, 2, throwThreshold, armour, ability)

    private fun profile(
        name: String,
        move: Int,
        blockDiceCount: Int,
        throwThreshold: Int,
        noArmour: Boolean,
        ability: PlayerAbility? = null,
        canThrowTeammate: Boolean = false,
    ): PositionProfile = profile(
        name = name,
        move = MoveValue.Fixed(move),
        blockDiceCount = blockDiceCount,
        throwStat = ThrowStat.Threshold(throwThreshold),
        armour = if (noArmour) ArmourStat.NoArmour else throw error("use armour threshold overload"),
        ability = ability,
        canThrowTeammate = canThrowTeammate,
    )

    private fun profile(
        name: String,
        move: Int,
        blockDiceCount: Int,
        throwThreshold: Int,
        armour: Int,
        ability: PlayerAbility? = null,
        canThrowTeammate: Boolean = false,
    ): PositionProfile = profile(
        name = name,
        move = MoveValue.Fixed(move),
        blockDiceCount = blockDiceCount,
        throwStat = ThrowStat.Threshold(throwThreshold),
        armour = ArmourStat.Threshold(armour),
        ability = ability,
        canThrowTeammate = canThrowTeammate,
    )

    private fun profile(
        name: String,
        move: MoveValue,
        blockDiceCount: Int,
        throwStat: ThrowStat,
        armour: ArmourStat,
        ability: PlayerAbility? = null,
        canThrowTeammate: Boolean = false,
    ): PositionProfile = PositionProfile(
        name = name,
        stats = StatLine(move = move, blockDiceCount = blockDiceCount, throwStat = throwStat, armour = armour),
        ability = ability,
        canThrowTeammate = canThrowTeammate,
    )
}
