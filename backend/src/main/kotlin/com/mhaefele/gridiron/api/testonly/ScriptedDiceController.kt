package com.mhaefele.gridiron.api.testonly

import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import jakarta.validation.Valid
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Queues override rolls for [ScriptedDiceRoller] — present on `test` / `scripted-dice` only.
 *
 * Posted values are consumed in order for that die type; when exhausted, behavior follows
 * [gridiron.dice.fallback-to-default-pattern] (false in unit/API tests, true by default for e2e).
 */
@RestController
@RequestMapping("/api/test/dice")
@Profile("test", "scripted-dice")
class ScriptedDiceController(
    private val dice: ScriptedDiceRoller,
) {

    @PostMapping("/d6")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun queueD6(@Valid @RequestBody request: QueueDiceValuesRequest) {
        dice.queueD6(request.values)
    }

    @PostMapping("/d8")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun queueD8(@Valid @RequestBody request: QueueDiceValuesRequest) {
        dice.queueD8(request.values)
    }

    @PostMapping("/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun queueBlock(@Valid @RequestBody request: QueueBlockDiceRequest) {
        dice.queueBlockDice(request.values)
    }
}
