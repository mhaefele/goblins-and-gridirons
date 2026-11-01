package com.mhaefele.gridiron.game.action.pending.block

import com.mhaefele.gridiron.game.action.Bounce
import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.ability.BlockRole
import com.mhaefele.gridiron.game.action.ability.applyBlockerFaceSubstitutions
import com.mhaefele.gridiron.game.action.ability.lobberBlock
import com.mhaefele.gridiron.game.action.ability.substituteBlockFace
import com.mhaefele.gridiron.game.action.ability.violentAutoInjureCheck
import com.mhaefele.gridiron.game.action.ability.FreeFollowUpAbilityHook
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.blockDiceRolled
import com.mhaefele.gridiron.game.action.utils.isAssistedBlock
import com.mhaefele.gridiron.game.action.utils.playerMarksOpponent
import com.mhaefele.gridiron.game.action.utils.pushSquareAwayFrom
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.dice.dto.bestBlockFace
import com.mhaefele.gridiron.game.dice.dto.bestEnforcerOrder
import com.mhaefele.gridiron.game.dice.dto.blockDiceCount
import com.mhaefele.gridiron.game.dice.dto.distinctFaceOrders
import com.mhaefele.gridiron.game.legalmoves.freeShortThrowMovesForQuickRelease
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.armourcheck.ArmourCheckResolver
import com.mhaefele.gridiron.game.action.pending.knockdown.KnockDownResolver
import com.mhaefele.gridiron.game.action.pending.run.RunResolver
import com.mhaefele.gridiron.game.action.pending.throwball.ThrowResolver

/**
 * Block resolution — standing knock-down nests [KnockDownResolver]; already-prone armour nests [ArmourCheckResolver].
 * Public surface: [beginBlock], [beginNested], [mutate].
 */
@Component
class BlockResolver(
    private val dice: DiceRoller,
    private val bounce: Bounce,
    private val boardMovement: BoardMovement,
    private val throwResolver: ThrowResolver,
    private val knockDownResolver: KnockDownResolver,
    private val armourCheckResolver: ArmourCheckResolver,
    private val freeFollowUpAbilityHook: FreeFollowUpAbilityHook,
    @Lazy private val runResolver: RunResolver,
) : PendingDecisionResolver<BlockContinuation>() {
    fun beginBlock(session: MatchMutator, action: LegalAction.Block, allInTackleProneAfterBlock: Boolean = false): BlockContinuation {
        val ongoing = startOngoing(session, action, allInTackleProneAfterBlock)
        val next = advance(session, ongoing)
        // Park even at DONE — TurnEngine COMPLETE pops the finished continuation and closes the open action.
        return BlockContinuation(next)
    }

    /** Nest under a parent FSM — start at INITIALIZE, crank, return continuation (no pending write). */
    fun beginNested(session: MatchMutator, action: LegalAction.Block, allInTackleProneAfterBlock: Boolean): BlockContinuation {
        val ongoing = startOngoing(session, action, allInTackleProneAfterBlock)
        return BlockContinuation(advance(session, ongoing))
    }

    private fun startOngoing(session: MatchMutator, action: LegalAction.Block, allInTackleProneAfterBlock: Boolean): OngoingBlockMutator {
        val board = session.board
        val blockerId = action.playerId
        val destination = action.destination
        val blocker = board.getPlayer(blockerId)
        val targetId = board.square(destination).playerId ?: error("No player at block destination $destination")
        val target = board.getPlayer(targetId)
        require(playerMarksOpponent(blocker, target, board.board) || lobberBlock(blocker)) { "Block target $targetId is not marked by ${blockerId.value}" }
        return OngoingBlockMutator(
            OngoingBlock(
                blockerId = blockerId,
                destination = destination,
                targetId = targetId,
                targetTeam = target.team,
                stage = OngoingBlock.BlockStage.INITIALIZE,
                gangUpAssisted = session.bonusModifiers.gangUp,
                allInTackleProneAfterBlock = allInTackleProneAfterBlock,
            ),
        )
    }

    /** Resume an existing [BlockContinuation]. */
    override fun mutate(session: MatchMutator, pending: BlockContinuation, command: LegalAction): BlockContinuation {
        val ongoing = OngoingBlockMutator(pending.ongoingBlock)
        handleLegalAction(session, ongoing, command)
        return BlockContinuation(advance(session, ongoing))
    }

    /** Consume one POST at an awaiting-input stage. Does not crank. */
    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingBlockMutator, action: LegalAction) {
        check(ongoing.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingBlock.BlockStage.HANDLE_QUICK_RELEASE  -> {
                handleQuickRelease(session, ongoing, action)
                ongoing.offeredAbility = null
                ongoing.offeredAbilityPlayerId = null
                ongoing.stage = OngoingBlock.BlockStage.OFFER_BRACE
            }
            OngoingBlock.BlockStage.HANDLE_BRACE          -> {
                handleBrace(session, ongoing, action)
                ongoing.stage = OngoingBlock.BlockStage.OFFER_UNDERDOG
            }
            OngoingBlock.BlockStage.HANDLE_UNDERDOG       -> {
                handleUnderdog(session, ongoing, action)
                ongoing.assisted = computeAssisted(session, ongoing)
                ongoing.stage = OngoingBlock.BlockStage.VIOLENT_CHECK
            }
            OngoingBlock.BlockStage.HANDLE_POST_POOL      -> {
                if (handlePostPool(session, ongoing, action)) {
                    ongoing.offeredAbility = null
                    ongoing.offeredAbilityPlayerId = null
                    ongoing.stage = OngoingBlock.BlockStage.QUEUE_POST_POOL
                } else {
                    ongoing.offeredAbility = null
                    ongoing.offeredAbilityPlayerId = null
                    ongoing.stage = OngoingBlock.BlockStage.RESOLVE_FACES
                }
            }
            OngoingBlock.BlockStage.HANDLE_ENFORCER_ORDER -> {
                check(action is LegalAction.ChooseBlockFaceOrder) { "HANDLE_ENFORCER_ORDER expects ChooseBlockFaceOrder, was ${action::class.simpleName}" }
                ongoing.remainingEnforcerFaces = action.orderedFaces
                ongoing.offeredAbility = null
                ongoing.offeredAbilityPlayerId = null
                ongoing.stage = OngoingBlock.BlockStage.APPLY_ENFORCER_FACES
            }
            OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET,
            OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_BLOCKER -> { ongoing.nestedKnockDown = knockDownResolver.mutate(session, ongoing.nestedKnockDown!!, action) }
            OngoingBlock.BlockStage.DELEGATE_ARMOUR_CHECK       -> { ongoing.nestedArmourCheck = armourCheckResolver.mutate(session, ongoing.nestedArmourCheck!!, action) }
            OngoingBlock.BlockStage.HANDLE_SHOVE_FOLLOWUP       -> {
                handleShoveFollowup(session, ongoing, action)
                ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
            }
            OngoingBlock.BlockStage.HANDLE_ABILITY_FOLLOW_UP    -> {
                val ability = checkNotNull(ongoing.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP missing ability" }
                when {
                    action is LegalAction.Skip                        -> {
                        ongoing.resolvedFollowUpAbilities = ongoing.resolvedFollowUpAbilities + ability
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingBlock.BlockStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    ability == PlayerAbility.`Strike and Fade`        -> {
                        val run = action as? LegalAction.Run ?: error("Strike and Fade expects Run, was ${action::class.simpleName}")
                        ongoing.nestedRun = runResolver.beginNested(session, run)
                        ongoing.stage = OngoingBlock.BlockStage.DELEGATE_FREE_ACTION
                    }
                    ability == PlayerAbility.`Relentless After Block` -> {
                        val block = action as? LegalAction.Block ?: error("Relentless After Block expects Block")
                        // Nested free Relentless may itself OFFER Relentless again (chain until Skip / no target).
                        ongoing.nestedBlock = beginNested(session, block, allInTackleProneAfterBlock = false)
                        ongoing.stage = OngoingBlock.BlockStage.DELEGATE_FREE_ACTION
                    }
                    ability == PlayerAbility.Rally                    -> {
                        val standUp = action as? LegalAction.StandUp ?: error("Rally expects StandUp")
                        boardMovement.applyStandUp(session.board, standUp)
                        session.recordTurnAction(standUp.playerId, ActionType.STAND_UP, isFree = true)
                        ongoing.resolvedFollowUpAbilities = ongoing.resolvedFollowUpAbilities + ability
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingBlock.BlockStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    else                                              -> error("No Block free-action handling for $ability")
                }
            }
            OngoingBlock.BlockStage.DELEGATE_FREE_ACTION        -> {
                when {
                    ongoing.nestedRun != null   -> { ongoing.nestedRun = runResolver.mutate(session, ongoing.nestedRun!!, action) }
                    ongoing.nestedBlock != null -> { ongoing.nestedBlock = mutate(session, ongoing.nestedBlock!!, action) }
                    else                        -> error("DELEGATE_FREE_ACTION without nested child")
                }
            }
            else                                                -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    /** Auto-crank until park or DONE. Never takes a [LegalAction]. */
    private fun advance(session: MatchMutator, ongoing: OngoingBlockMutator): OngoingBlock {
        while (ongoing.stage != OngoingBlock.BlockStage.DONE && !ongoing.awaitsInput) {
            when (ongoing.stage) {
                OngoingBlock.BlockStage.INITIALIZE -> ongoing.stage = OngoingBlock.BlockStage.OFFER_QUICK_RELEASE

                OngoingBlock.BlockStage.OFFER_QUICK_RELEASE -> {
                    val target = session.board.getPlayer(ongoing.targetId)
                    if (target.hasAbility(PlayerAbility.`Quick Release`) && target.holdsBall() && freeShortThrowMovesForQuickRelease(target, session.board.board).isNotEmpty()) {
                        ongoing.offeredAbility = PlayerAbility.`Quick Release`
                        ongoing.offeredAbilityPlayerId = ongoing.targetId
                        ongoing.stage = OngoingBlock.BlockStage.HANDLE_QUICK_RELEASE
                    } else {
                        ongoing.stage = OngoingBlock.BlockStage.OFFER_BRACE
                    }
                }

                OngoingBlock.BlockStage.OFFER_BRACE -> {
                    if (BonusOffers.shouldOfferBrace(session, ongoing.targetTeam)) {
                        ongoing.stage = OngoingBlock.BlockStage.HANDLE_BRACE
                    } else {
                        ongoing.stage = OngoingBlock.BlockStage.OFFER_UNDERDOG
                    }
                }

                OngoingBlock.BlockStage.OFFER_UNDERDOG -> {
                    val assisted = computeAssisted(session, ongoing)
                    if (!assisted && BonusOffers.shouldOfferUnderdog(session)) {
                        ongoing.stage = OngoingBlock.BlockStage.HANDLE_UNDERDOG
                    } else {
                        ongoing.assisted = assisted
                        ongoing.stage = OngoingBlock.BlockStage.VIOLENT_CHECK
                    }
                }

                OngoingBlock.BlockStage.VIOLENT_CHECK -> {
                    val blocker = session.board.getPlayer(ongoing.blockerId)
                    if (violentAutoInjureCheck(blocker)) {
                        val violentRoll = dice.d6()
                        session.board.record(DomainEvent.DiceRolled(purpose = DiceRollPurpose.VIOLENT, d6 = violentRoll))
                        if (violentRoll == 6) {
                            val target = session.board.getPlayer(ongoing.targetId)
                            if (target.holdsBall()) {
                                session.board.ballDroppedByPlayer(target.id)
                                bounce.bounce(session.board, ongoing.destination, cannotPickUp = target.id)
                            }
                            session.board.playerInjured(target.id)
                            ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                        } else {
                            ongoing.stage = OngoingBlock.BlockStage.ROLL_POOL
                        }
                    } else {
                        ongoing.stage = OngoingBlock.BlockStage.ROLL_POOL
                    }
                }

                OngoingBlock.BlockStage.ROLL_POOL -> {
                    rollPool(session, ongoing)
                    ongoing.stage = OngoingBlock.BlockStage.QUEUE_POST_POOL
                }

                OngoingBlock.BlockStage.QUEUE_POST_POOL -> {
                    if (shouldOfferPostPool(session, ongoing)) {
                        ongoing.offeredAbility = PlayerAbility.`Second Swing`
                        ongoing.offeredAbilityPlayerId = ongoing.blockerId
                        ongoing.stage = OngoingBlock.BlockStage.HANDLE_POST_POOL
                    } else {
                        ongoing.stage = OngoingBlock.BlockStage.RESOLVE_FACES
                    }
                }

                OngoingBlock.BlockStage.RESOLVE_FACES -> {
                    prepareResolvedFaces(session, ongoing)
                    val blocker = session.board.getPlayer(ongoing.blockerId)
                    val faces = ongoing.faces!!
                    if (blocker.hasAbility(PlayerAbility.Enforcer) && faces.size > 1) {
                        // Distinct permutations → coach picks order (humans + AI learning seats).
                        val orders = distinctFaceOrders(faces)
                        if (orders.size > 1) {
                            ongoing.offeredAbility = PlayerAbility.Enforcer
                            ongoing.offeredAbilityPlayerId = ongoing.blockerId
                            ongoing.stage = OngoingBlock.BlockStage.HANDLE_ENFORCER_ORDER
                        } else {
                            ongoing.remainingEnforcerFaces = bestEnforcerOrder(faces)
                            ongoing.stage = OngoingBlock.BlockStage.APPLY_ENFORCER_FACES
                        }
                    } else if (blocker.hasAbility(PlayerAbility.Enforcer)) {
                        ongoing.remainingEnforcerFaces = faces
                        ongoing.stage = OngoingBlock.BlockStage.APPLY_ENFORCER_FACES
                    } else {
                        ongoing.stage = OngoingBlock.BlockStage.APPLY_BEST_FACE
                    }
                }

                OngoingBlock.BlockStage.APPLY_ENFORCER_FACES -> {
                    val board = session.board
                    val remaining = ongoing.remainingEnforcerFaces
                    if (remaining.isNullOrEmpty()) {
                        ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                    } else if (board.getPlayer(ongoing.targetId).status == PlayerStatus.RESERVES) {
                        ongoing.remainingEnforcerFaces = null
                        ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                    } else {
                        val face = remaining.first()
                        ongoing.remainingEnforcerFaces = remaining.drop(1)
                        board.record(
                            DomainEvent.DiceRolled(
                                purpose = DiceRollPurpose.BLOCK,
                                blockFaces = listOf(face),
                                blockResult = face,
                                ability = PlayerAbility.Enforcer,
                            ),
                        )
                        applyEnforcerFace(session, ongoing, face)
                        // Nested KD / ArmourCheck arms own the next stage when they park.
                        if (ongoing.stage == OngoingBlock.BlockStage.APPLY_ENFORCER_FACES) {
                            if (board.getPlayer(ongoing.targetId).status == PlayerStatus.RESERVES) {
                                ongoing.remainingEnforcerFaces = null
                                ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                            } else if (ongoing.remainingEnforcerFaces.isNullOrEmpty()) {
                                ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                            }
                        }
                    }
                }

                OngoingBlock.BlockStage.APPLY_BEST_FACE -> {
                    when (val result = bestBlockFace(ongoing.faces!!)) {
                        BlockDieFace.SMASH   -> {
                            ongoing.enforcerFollowUpSquare = session.board.playerCoordinate(ongoing.targetId)
                            ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.targetId, withArmour = true, armourMinusOne = false, offerBladed = true)
                            ongoing.stage = OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET
                        }
                        BlockDieFace.FLATTEN -> {
                            ongoing.enforcerFollowUpSquare = session.board.playerCoordinate(ongoing.targetId)
                            ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.targetId, withArmour = true, armourMinusOne = true, offerBladed = true)
                            ongoing.stage = OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET
                        }
                        BlockDieFace.TACKLE  -> {
                            ongoing.enforcerFollowUpSquare = session.board.playerCoordinate(ongoing.targetId)
                            ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.targetId, withArmour = true, armourMinusOne = false, offerBladed = true)
                            ongoing.stage = OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET
                            session.endPlayerTurn(ongoing.blockerId)
                        }
                        BlockDieFace.SHOVE   -> applyShove(session, ongoing)
                        BlockDieFace.MISS    -> {
                            session.endPlayerTurn(ongoing.blockerId)
                            ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                        }
                    }
                }

                OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET -> {
                    val nested = checkNotNull(ongoing.nestedKnockDown) { "DELEGATE_KNOCK_DOWN_TARGET missing nestedKnockDown" }
                    if (nested.isFinished()) {
                        ongoing.nestedKnockDown = null
                        val followUp = checkNotNull(ongoing.enforcerFollowUpSquare) { "DELEGATE_KNOCK_DOWN_TARGET missing enforcerFollowUpSquare" }
                        ongoing.enforcerFollowUpSquare = null
                        maybeEnforcerFollowUpAfterArmour(session, ongoing, followUp)
                        if (!ongoing.remainingEnforcerFaces.isNullOrEmpty()) {
                            ongoing.stage = OngoingBlock.BlockStage.APPLY_ENFORCER_FACES
                        } else {
                            ongoing.remainingEnforcerFaces = null
                            ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                        }
                    }
                }

                OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_BLOCKER -> {
                    val nested = checkNotNull(ongoing.nestedKnockDown) { "DELEGATE_KNOCK_DOWN_BLOCKER missing nestedKnockDown" }
                    if (nested.isFinished()) {
                        ongoing.nestedKnockDown = null
                        ongoing.stage = OngoingBlock.BlockStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingBlock.BlockStage.DELEGATE_ARMOUR_CHECK -> {
                    val nested = checkNotNull(ongoing.nestedArmourCheck) { "DELEGATE_ARMOUR_CHECK missing nestedArmourCheck" }
                    if (nested.isFinished()) {
                        ongoing.nestedArmourCheck = null
                        val followUp = checkNotNull(ongoing.enforcerFollowUpSquare) { "DELEGATE_ARMOUR_CHECK missing enforcerFollowUpSquare" }
                        ongoing.enforcerFollowUpSquare = null
                        maybeEnforcerFollowUpAfterArmour(session, ongoing, followUp)
                        if (!ongoing.remainingEnforcerFaces.isNullOrEmpty()) {
                            ongoing.stage = OngoingBlock.BlockStage.APPLY_ENFORCER_FACES
                        } else {
                            ongoing.remainingEnforcerFaces = null
                            ongoing.stage = OngoingBlock.BlockStage.CORE_COMPLETE
                        }
                    }
                }

                OngoingBlock.BlockStage.CORE_COMPLETE -> {
                    if (ongoing.allInTackleProneAfterBlock) {
                        ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.blockerId, withArmour = false)
                        ongoing.stage = OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_BLOCKER
                    } else {
                        ongoing.stage = OngoingBlock.BlockStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingBlock.BlockStage.OFFER_ABILITY_FOLLOW_UPS -> {
                    // Nested Blocks under Mark skip re-query here when parent Mark owns ability stages
                    // for Mark-triggered grants; Block-triggered grants (Strike and Fade) still apply.
                    val grant = freeFollowUpAbilityHook.offersAfterResolved(ongoing.blockerId, ActionType.BLOCK, session.toMatchState())
                        .firstOrNull { it.ability !in ongoing.resolvedFollowUpAbilities }
                    if (grant == null) {
                        ongoing.stage = OngoingBlock.BlockStage.DONE
                    } else {
                        ongoing.offeredAbility = grant.ability
                        ongoing.offeredAbilityPlayerId = grant.playerId
                        ongoing.stage = OngoingBlock.BlockStage.HANDLE_ABILITY_FOLLOW_UP
                    }
                }

                OngoingBlock.BlockStage.DELEGATE_FREE_ACTION -> {
                    when {
                        ongoing.nestedRun != null   -> {
                            val nested = ongoing.nestedRun!!
                            if (nested.isFinished()) {
                                session.recordTurnAction(nested.ongoingRun.playerId, ActionType.RUN, isFree = true)
                                ongoing.resolvedFollowUpAbilities = ongoing.resolvedFollowUpAbilities +
                                    checkNotNull(ongoing.offeredAbility) { "DELEGATE_FREE_ACTION Run missing offeredAbility" }
                                ongoing.nestedRun = null
                                ongoing.offeredAbility = null
                                ongoing.offeredAbilityPlayerId = null
                                ongoing.stage = OngoingBlock.BlockStage.OFFER_ABILITY_FOLLOW_UPS
                            }
                        }
                        ongoing.nestedBlock != null -> {
                            val nested = ongoing.nestedBlock!!
                            if (nested.isFinished()) {
                                session.recordTurnAction(nested.ongoingBlock.blockerId, ActionType.BLOCK, isFree = true)
                                ongoing.resolvedFollowUpAbilities = ongoing.resolvedFollowUpAbilities +
                                    checkNotNull(ongoing.offeredAbility) { "DELEGATE_FREE_ACTION Block missing offeredAbility" }
                                ongoing.nestedBlock = null
                                ongoing.offeredAbility = null
                                ongoing.offeredAbilityPlayerId = null
                                ongoing.stage = OngoingBlock.BlockStage.OFFER_ABILITY_FOLLOW_UPS
                            }
                        }
                        else                        -> error("DELEGATE_FREE_ACTION without nested child")
                    }
                }

                else -> error("auto-crank unexpected ${ongoing.stage}")
            }
        }
        return ongoing.ongoingBlock
    }

    private fun handleQuickRelease(session: MatchMutator, ongoing: OngoingBlockMutator, action: LegalAction) {
        when (action) {
            is LegalAction.ContinueBlock     -> Unit
            is LegalAction.QuickReleaseThrow -> throwResolver.beginQuickReleaseThrow(session, action)
            else                             -> error("HANDLE_QUICK_RELEASE expects ContinueBlock or QuickReleaseThrow, was ${action::class.simpleName}")
        }
    }

    private fun handleBrace(session: MatchMutator, ongoing: OngoingBlockMutator, action: LegalAction) {
        when (action) {
            is LegalAction.DeclineBonus -> Unit
            is LegalAction.PlayBrace    -> {
                session.playBonusCard(ongoing.targetTeam, action.bonusPlay)
                ongoing.braceNoAssist = true
                ongoing.braceShoveIsMiss = true
            }
            else                        -> error("HANDLE_BRACE expects PlayBrace or DeclineBonus, was ${action::class.simpleName}")
        }
    }

    private fun handleUnderdog(session: MatchMutator, ongoing: OngoingBlockMutator, action: LegalAction) {
        when (action) {
            is LegalAction.DeclineBonus -> Unit
            is LegalAction.PlayUnderdog -> {
                session.playBonusCard(session.activeCoach, action.bonusPlay)
                ongoing.underdog = true
            }
            else                        -> error("HANDLE_UNDERDOG expects PlayUnderdog or DeclineBonus, was ${action::class.simpleName}")
        }
    }

    /** Returns true when post-pool abilities should be re-checked (Do-Over re-roll). */
    private fun handlePostPool(session: MatchMutator, ongoing: OngoingBlockMutator, action: LegalAction): Boolean {
        when (action) {
            is LegalAction.DeclineBonus        -> {
                ongoing.doOverBlockDeclined = true
                val blocker = session.board.getPlayer(ongoing.blockerId)
                return blocker.hasAbility(PlayerAbility.`Second Swing`) && !ongoing.secondSwingUsed
            }
            is LegalAction.AdjustBlockByDoOver -> {
                session.playBonusCard(session.activeCoach, BonusPlay.`Do-Over`)
                val newFaces = dice.blockDice(ongoing.faces!!.size)
                val blocker = session.board.getPlayer(ongoing.blockerId)
                val blockResult = if (blocker.hasAbility(PlayerAbility.Enforcer)) null else bestBlockFace(newFaces)
                session.board.record(blockDiceRolled(newFaces, blockResult, bonusPlay = BonusPlay.`Do-Over`))
                ongoing.faces = newFaces
                return true
            }
            is LegalAction.ChooseBlockReroll   -> {
                ongoing.doOverBlockDeclined = true
                if (action.reroll) {
                    val blocker = session.board.getPlayer(ongoing.blockerId)
                    val count = blockDiceCount(blocker.profile.stats.blockDiceCount, ongoing.assisted)
                    val rerolled = dice.blockDice(count)
                    val blockResult = if (blocker.hasAbility(PlayerAbility.Enforcer)) null else bestBlockFace(rerolled)
                    val ability = when {
                        blocker.hasAbility(PlayerAbility.Enforcer) -> PlayerAbility.Enforcer
                        else                                       -> PlayerAbility.`Second Swing`
                    }
                    session.board.record(blockDiceRolled(rerolled, blockResult, ability = ability))
                    ongoing.faces = rerolled
                    ongoing.secondSwingUsed = true
                } else {
                    ongoing.secondSwingUsed = true
                }
                return false
            }
            else                               -> error("HANDLE_POST_POOL unexpected command ${action::class.simpleName}")
        }
    }

    private fun handleShoveFollowup(session: MatchMutator, ongoing: OngoingBlockMutator, action: LegalAction) {
        when (action) {
            is LegalAction.Skip -> Unit
            is LegalAction.Mark -> boardMovement.applyPathMovement(session.board, action)
            else                -> error("HANDLE_SHOVE_FOLLOWUP expects Mark or Skip, was ${action::class.simpleName}")
        }
    }

    private fun computeAssisted(session: MatchMutator, ongoing: OngoingBlockMutator): Boolean {
        val board = session.board
        val blocker = board.getPlayer(ongoing.blockerId)
        val target = board.getPlayer(ongoing.targetId)
        return !ongoing.braceNoAssist && (ongoing.gangUpAssisted ||(!lobberBlock(blocker) && isAssistedBlock(blocker, target, board.board)))
    }

    private fun rollPool(session: MatchMutator, ongoing: OngoingBlockMutator) {
        val board = session.board
        val blocker = board.getPlayer(ongoing.blockerId)
        val assisted = if (ongoing.faces == null) computeAssisted(session, ongoing) else ongoing.assisted
        ongoing.assisted = assisted
        var count = blockDiceCount(blocker.profile.stats.blockDiceCount, assisted)
        if (ongoing.underdog && !assisted) {
            count = 3
        }
        val rawFaces = dice.blockDice(count)
        val blockResult = if (blocker.hasAbility(PlayerAbility.Enforcer)) null else bestBlockFace(rawFaces)
        board.record(blockDiceRolled(rawFaces, blockResult))
        ongoing.faces = rawFaces
    }

    private fun shouldOfferPostPool(session: MatchMutator, ongoing: OngoingBlockMutator): Boolean {
        val blocker = session.board.getPlayer(ongoing.blockerId)
        val hasOs = blocker.hasAbility(PlayerAbility.`Second Swing`) && !ongoing.secondSwingUsed
        val doOver = !ongoing.doOverBlockDeclined && BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.`Do-Over`) && DoOverRoll.mayImproveBlock(session.board.board, ongoing.blockerId, ongoing.faces!!)
        return hasOs || doOver
    }

    private fun prepareResolvedFaces(session: MatchMutator, ongoing: OngoingBlockMutator) {
        val board = session.board
        val blocker = board.getPlayer(ongoing.blockerId)
        val target = board.getPlayer(ongoing.targetId)
        val blockerFaces = applyBlockerFaceSubstitutions(ongoing.faces!!, blocker)
        val substitutedFaces = blockerFaces.map { substituteBlockFace(it, target, BlockRole.TARGET) }
        val faces = if (ongoing.braceShoveIsMiss) {
            substitutedFaces.map { face -> if (face == BlockDieFace.SHOVE) BlockDieFace.MISS else face }
        } else {
            substitutedFaces
        }
        // One-shot Brace auras consume with face resolution.
        ongoing.braceNoAssist = false
        ongoing.braceShoveIsMiss = false
        ongoing.faces = faces
    }

    /** Apply one Enforcer face's board / nest effects. Caller owns stage when still on APPLY_ENFORCER_FACES. */
    private fun applyEnforcerFace(session: MatchMutator, ongoing: OngoingBlockMutator, result: BlockDieFace) {
        val board = session.board
        val targetId = ongoing.targetId
        val target = board.getPlayer(targetId)
        val wasStanding = target.status == PlayerStatus.STANDING

        when (result) {
            BlockDieFace.SMASH, BlockDieFace.FLATTEN, BlockDieFace.TACKLE -> {
                val flatten = result == BlockDieFace.FLATTEN
                if (wasStanding) {
                    ongoing.enforcerFollowUpSquare = session.board.playerCoordinate(ongoing.targetId)
                    ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.targetId, withArmour = true, armourMinusOne = flatten, offerBladed = true)
                    ongoing.stage = OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET
                } else {
                    ongoing.enforcerFollowUpSquare = session.board.playerCoordinate(ongoing.targetId)
                    ongoing.nestedArmourCheck = armourCheckResolver.begin(session, ongoing.targetId, armourMinusOne = flatten, offerBladed = true)
                    ongoing.stage = OngoingBlock.BlockStage.DELEGATE_ARMOUR_CHECK
                }
                if (result == BlockDieFace.TACKLE) {
                    session.endPlayerTurn(ongoing.blockerId)
                }
            }
            BlockDieFace.SHOVE                                            -> applyEnforcerShove(session, ongoing)
            BlockDieFace.MISS                                             -> session.endPlayerTurn(ongoing.blockerId)
        }
    }

    private fun applyEnforcerShove(session: MatchMutator, ongoing: OngoingBlockMutator) {
        val board = session.board
        val targetId = ongoing.targetId
        val blockerAt = board.playerCoordinate(ongoing.blockerId)
        val targetAt = board.playerCoordinate(targetId)
        val target = board.getPlayer(targetId)
        val pushTo = pushSquareAwayFrom(blockerAt, targetAt)
        val landing = board.square(pushTo)
        if (landing.isBlocked() || landing.playerId != null) {
            // Blocked shove → Knock Down. Already Prone: no second KD, and the square is still
            // occupied so Enforcer has nowhere to follow up.
            if (target.status == PlayerStatus.STANDING) {
                ongoing.enforcerFollowUpSquare = session.board.playerCoordinate(ongoing.targetId)
                ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.targetId, withArmour = true, armourMinusOne = false, offerBladed = true)
                ongoing.stage = OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET
            }
        } else {
            board.playerMoved(targetId, targetAt, pushTo)
            autoEnforcerFollowUp(board, ongoing.blockerId, targetAt)
            if (landing.looseBallId != null) {
                bounce.bounce(board, pushTo)
            }
        }
    }

    private fun autoEnforcerFollowUp(board: BoardMutator, blockerId: PlayerId, vacated: Coordinate) {
        val dest = board.square(vacated)
        if (dest.isBlocked() || dest.playerId != null) return
        val from = board.playerCoordinate(blockerId)
        val dx = kotlin.math.abs(vacated.x - from.x)
        val dy = kotlin.math.abs(vacated.y - from.y)
        if (dx > 1 || dy > 1 || (dx == 0 && dy == 0)) return
        board.playerMoved(blockerId, from, vacated)
    }

    private fun maybeEnforcerFollowUpAfterArmour(session: MatchMutator, ongoing: OngoingBlockMutator, targetAtWhenChecked: Coordinate) {
        val board = session.board
        if (board.getPlayer(ongoing.targetId).status == PlayerStatus.RESERVES && board.getPlayer(ongoing.blockerId).hasAbility(PlayerAbility.Enforcer)) {
            autoEnforcerFollowUp(board, ongoing.blockerId, targetAtWhenChecked)
        }
    }

    private fun applyShove(session: MatchMutator, ongoing: OngoingBlockMutator) {
        val board = session.board
        val blockerAt = board.playerCoordinate(ongoing.blockerId)
        val targetAt = board.playerCoordinate(ongoing.targetId)
        val pushTo = pushSquareAwayFrom(blockerAt, targetAt)
        val landing = board.square(pushTo)
        if (landing.isBlocked() || landing.playerId != null) {
            ongoing.enforcerFollowUpSquare = session.board.playerCoordinate(ongoing.targetId)
            ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.targetId, withArmour = true, armourMinusOne = false, offerBladed = true)
            ongoing.stage = OngoingBlock.BlockStage.DELEGATE_KNOCK_DOWN_TARGET
        } else {
            board.playerMoved(ongoing.targetId, targetAt, pushTo)
            ongoing.shoveVacatedSquare = targetAt
            ongoing.stage = OngoingBlock.BlockStage.HANDLE_SHOVE_FOLLOWUP
            if (landing.looseBallId != null) {
                bounce.bounce(board, pushTo)
            }
        }
    }
}
