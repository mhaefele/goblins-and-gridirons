package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import java.util.stream.Stream

class FullGameShakeStuffLooseTest: BaseApiTest()  {
    @Autowired
    lateinit var singleGameArenaRunner: SingleGameArenaRunner

    @BeforeEach
    fun setup() {
        dice.usePassThrough()
    }

    @BeforeEach
    override fun resetDeterministicDice() {
        //  Since we're a multithreaded test, reseting the dice could cause race conditions where one test turns off the passthrough for another.
//        dice.reset()
    }

    @ParameterizedTest
    @MethodSource("teamPermutations")
    @Execution(ExecutionMode.CONCURRENT)
    fun `Test Running Full Game for two teams`(homeTeam: TeamCard, awayTeam: TeamCard) {
        singleGameArenaRunner.playGame(homeTeam, awayTeam)
    }

    companion object {
        @JvmStatic
        fun teamPermutations(): Stream<Arguments> {
            val arguments = mutableListOf<Arguments>()
            val allTeams = TeamCatalog.allTeams().toMutableList()
            do {
                val homeTeam = allTeams.first()
                arguments.addAll(allTeams.map { awayTeam -> Arguments.of(homeTeam, awayTeam) })

                allTeams.remove(homeTeam)
            } while (allTeams.isNotEmpty())

            return arguments.stream()
        }
    }
}