package com.mhaefele.gridiron.game.opponent

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.stream.MatchEventHub
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadOpponentStrategy
import com.mhaefele.gridiron.game.opponent.onnx.OnnxOpponentFactory
import com.mhaefele.gridiron.game.opponent.onnx.OnnxPolicySession
import com.mhaefele.gridiron.game.opponent.scripted.ScriptedOpponentStrategy
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Spawns and tears down in-process [ComputerCoachAgent]s for seats marked [CoachControl.COMPUTER].
 */
@Component
class ComputerCoachRegistry(
    private val eventHub: MatchEventHub,
    private val repository: InMemoryMatchRepository,
    private val lookahead: LookaheadOpponentStrategy,
    private val heuristic: ScriptedOpponentStrategy,
    private val onnxFactory: OnnxOpponentFactory,
    @Lazy private val matchGame: MatchGame,
) {
    private val agentsByMatch = ConcurrentHashMap<MatchId, List<ComputerCoachAgent>>()

    fun spawnForMatch(
        matchId: MatchId,
        computerOpponent: ComputerOpponentKind = ComputerOpponentKind.LOOKAHEAD,
        onnxProfile: String = OnnxPolicySession.DEFAULT_PROFILE.id,
    ) {
        val state = repository.findById(matchId)
        val seats = TeamSide.entries.filter { state.config.controlledBy(it) == CoachControl.COMPUTER }
        if (seats.isEmpty()) return

        val strategy = strategyFor(computerOpponent, onnxProfile)

        check(agentsByMatch.putIfAbsent(matchId, emptyList()) == null) { "Computer coaches already spawned for match ${matchId.value}" }
        val agents =
            seats.map { team ->
                ComputerCoachAgent(
                    team = team,
                    matchId = matchId,
                    matchGame = matchGame,
                    repository = repository,
                    strategy = strategy,
                ).also { agent ->
                    agent.start(eventHub.subscribe(matchId, agent))
                    agent.primeIfAlreadyActive()
                }
            }
        agentsByMatch[matchId] = agents
    }

    /**
     * Shared Spring `@Component` strategies — not constructed per match. Search dice is a
     * [com.mhaefele.gridiron.game.dice.DiceRollerContext] overlay per evaluate, not MatchGame's roller.
     * Optional [LookaheadOpponentStrategy.telemetry] is the only instance mutable state and is null here.
     * ONNX strategies are per-profile instances from [OnnxOpponentFactory].
     */
    fun strategyFor(
        kind: ComputerOpponentKind,
        onnxProfile: String = OnnxPolicySession.DEFAULT_PROFILE.id,
    ): OpponentStrategy =
        when (kind) {
            ComputerOpponentKind.LOOKAHEAD -> lookahead
            ComputerOpponentKind.HEURISTIC -> heuristic
            ComputerOpponentKind.ONNX      -> onnxFactory.forProfile(onnxProfile)
        }

    fun stopForMatch(matchId: MatchId) {
        agentsByMatch.remove(matchId)?.forEach { it.close() }
    }

    /** True while in-process agents are registered for [matchId] (false after [stopForMatch]). */
    fun hasAgents(matchId: MatchId): Boolean = agentsByMatch.containsKey(matchId)

    @PreDestroy
    fun shutdown() {
        agentsByMatch.keys.toList().forEach { stopForMatch(it) }
    }
}
