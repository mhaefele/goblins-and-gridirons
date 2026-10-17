package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.model.board.Player

fun Player.hasAbility(ability: PlayerAbility): Boolean = profile.ability == ability
