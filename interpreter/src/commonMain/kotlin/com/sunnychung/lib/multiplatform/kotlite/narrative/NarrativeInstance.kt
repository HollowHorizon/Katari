package com.sunnychung.lib.multiplatform.kotlite.narrative

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NarrativeInstance(
    private val program: NarrativeProgram,
    initialState: NarrativeState = NarrativeState(
        programVersion = program.version,
        tasks = listOf(NarrativeTaskState(id = program.entryTaskId)),
    ),
    private val functionRegistry: NarrativeFunctionRegistry = NarrativeBuiltinFunctions.registry(NarrativeNoOpHost),
    private val snapshotCodec: NarrativeStateSnapshotCodec = NarrativeStateSnapshotCodec(),
    coroutineScope: CoroutineScope? = null,
) {
    companion object {
        private const val SLOT_VARIABLE_PREFIX = "__narrative_slot_"
    }

    private val ownsScope = coroutineScope == null
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val completion = CompletableDeferred<Unit>()
    private val inFlightTasks = mutableSetOf<String>()
    private var currentState: NarrativeState = initialState
    private var executionJob: Job? = null
    private var cancelled = false

    fun start() {
        if (cancelled || completion.isCompleted) return
        scope.launch {
            val pending = mutex.withLock {
                val requests = collectUndispatchedSuspensionsLocked()
                if (executionJob?.isActive != true) {
                    executionJob = launchExecutionLocked()
                }
                requests
            }
            pending.forEach { dispatchSuspendedCall(it) }
        }
    }

    fun cancel() {
        cancelled = true
        executionJob?.cancel()
        if (ownsScope) {
            scope.cancel()
        }
        if (!completion.isCompleted) {
            completion.complete(Unit)
        }
    }

    suspend fun join() {
        completion.await()
    }

    suspend fun serializeState(): NarrativeStateSnapshot {
        return mutex.withLock {
            snapshotCodec.serialize(currentState)
        }
    }

    suspend fun currentState(): NarrativeState {
        return mutex.withLock { currentState }
    }

    private fun launchExecutionLocked(): Job {
        return scope.launch {
            try {
                runExecutionLoop()
            } finally {
                mutex.withLock {
                    if (executionJob === this@launch) {
                        executionJob = null
                    }
                }
            }
        }
    }

    private suspend fun runExecutionLoop() {
        while (!cancelled) {
            var nextEffectToDispatch: FunctionDispatchRequest? = null
            var pendingSuspensions: List<FunctionDispatchRequest> = emptyList()
            var shouldReturn = false
            mutex.withLock {
                val taskIndex = currentState.tasks.indexOfFirst { it.status == NarrativeTaskStatus.Ready }
                if (taskIndex < 0) {
                    pendingSuspensions = collectUndispatchedSuspensionsLocked()
                    if (currentState.tasks.all { it.status == NarrativeTaskStatus.Completed } && !completion.isCompleted) {
                        completion.complete(Unit)
                    }
                    shouldReturn = true
                    return@withLock
                }

                val task = currentState.tasks[taskIndex]
                if (task.instructionPointer >= program.instructions.size) {
                    currentState = currentState.completeTask(taskIndex)
                } else {
                    when (val instruction = program.instructions[task.instructionPointer]) {
                        is CallFunctionInstruction -> nextEffectToDispatch = executeFunctionCallLocked(taskIndex, task, instruction)
                        is SetVariableInstruction -> {
                            val referencedSlots = collectReferencedSlots(instruction.expression)
                            currentState = currentState.updateTask(
                                index = taskIndex,
                                task = cleanupSlots(
                                    task = applySetVariableInstruction(task, instruction),
                                    slotsToRemove = referencedSlots,
                                ),
                            )
                        }
                        is SetResultInstruction -> {
                            val referencedSlots = collectReferencedSlots(instruction.expression)
                            currentState = currentState.updateTask(
                                index = taskIndex,
                                task = cleanupSlots(
                                    task = applyExpressionResultTarget(
                                        task = task,
                                        resultTarget = instruction.target,
                                        expression = instruction.expression,
                                        nextInstructionPointer = task.instructionPointer + 1,
                                    ),
                                    slotsToRemove = referencedSlots,
                                ),
                            )
                        }
                        is ConditionalJumpInstruction -> {
                            val referencedSlots = collectReferencedSlots(instruction.condition)
                            val isTrue = evaluateExpression(currentState, task, instruction.condition).asBoolean()
                            currentState = currentState.updateTask(
                                index = taskIndex,
                                task = cleanupSlots(
                                    task = task.copy(
                                        instructionPointer = if (isTrue) task.instructionPointer + 1 else instruction.falseTarget,
                                    ),
                                    slotsToRemove = referencedSlots,
                                ),
                            )
                        }
                        is JumpInstruction -> {
                            currentState = currentState.updateTask(
                                index = taskIndex,
                                task = task.copy(instructionPointer = instruction.target),
                            )
                        }
                        is EndInstruction -> {
                            currentState = currentState.completeTask(taskIndex)
                        }
                    }
                }
            }

            pendingSuspensions.forEach { dispatchSuspendedCall(it) }
            if (shouldReturn) {
                return
            }
            nextEffectToDispatch?.let { request ->
                dispatchSuspendedCall(request)
                return
            }
        }
    }

    private suspend fun executeFunctionCallLocked(
        taskIndex: Int,
        task: NarrativeTaskState,
        instruction: CallFunctionInstruction,
    ): FunctionDispatchRequest? {
        val arguments = evaluateArguments(task, instruction)
        val referencedSlots = collectReferencedSlots(instruction.arguments)
        val definition = functionRegistry.definition(instruction.functionId)
        return when (val result = definition.startCall(arguments, DefaultNarrativeFunctionContext(currentState, task))) {
            is NarrativeFunctionResult.Returned -> {
                currentState = currentState.updateTask(
                    index = taskIndex,
                    task = cleanupSlots(
                        task = applyResultTarget(
                            task = task,
                            resultTarget = instruction.resultTarget,
                            value = result.value,
                            nextInstructionPointer = task.instructionPointer + 1,
                        ),
                        slotsToRemove = referencedSlots,
                    ),
                )
                null
            }
            NarrativeFunctionResult.Suspended -> {
                val suspendedStatus = NarrativeTaskStatus.SuspendedCall(
                    resultTarget = instruction.resultTarget,
                    nextInstructionPointer = task.instructionPointer + 1,
                )
                currentState = currentState.updateTask(
                    index = taskIndex,
                    task = task.copy(status = suspendedStatus),
                )
                FunctionDispatchRequest(task.id)
            }
        }
    }

    private suspend fun dispatchSuspendedCall(request: FunctionDispatchRequest) {
        val dispatchData = mutex.withLock {
            if (request.taskId in inFlightTasks) return
            val task = currentState.tasks.firstOrNull { currentTask ->
                currentTask.id == request.taskId && currentTask.status is NarrativeTaskStatus.SuspendedCall
            } ?: return
            inFlightTasks += request.taskId
            val instruction = resolveSuspendedCallInstruction(task)
            val arguments = evaluateArguments(task, instruction)
            DispatchData(
                taskId = task.id,
                arguments = arguments,
                definition = functionRegistry.definition(instruction.functionId),
                context = DefaultNarrativeFunctionContext(currentState, task),
            )
        }

        dispatchData.definition.dispatch(
            arguments = dispatchData.arguments,
            context = dispatchData.context,
            resume = { response ->
                val activeJob = executionJob
                scope.launch {
                    activeJob?.join()
                    handleResume(dispatchData.taskId, response)
                }
            },
        )
    }

    private suspend fun handleResume(
        taskId: String,
        response: NarrativeFunctionResponse?,
    ) {
        val pending: List<FunctionDispatchRequest>
        mutex.withLock {
            inFlightTasks -= taskId
            val taskIndex = currentState.tasks.indexOfFirst {
                it.id == taskId && it.status is NarrativeTaskStatus.SuspendedCall
            }
            if (taskIndex < 0 || cancelled) return

            val task = currentState.tasks[taskIndex]
            val status = task.status as NarrativeTaskStatus.SuspendedCall
            val instruction = resolveSuspendedCallInstruction(task)
            val arguments = evaluateArguments(task, instruction)
            val referencedSlots = collectReferencedSlots(instruction.arguments)
            val definition = functionRegistry.definition(instruction.functionId)
            when (val result = definition.resumeCall(
                arguments = arguments,
                response = response,
                context = DefaultNarrativeFunctionContext(currentState, task),
            )) {
                is NarrativeFunctionResult.Returned -> {
                    currentState = currentState.updateTask(
                        index = taskIndex,
                        task = cleanupSlots(
                            task = applyResultTarget(
                                task = task.copy(status = NarrativeTaskStatus.Ready),
                                resultTarget = status.resultTarget,
                                value = result.value,
                                nextInstructionPointer = status.nextInstructionPointer,
                            ),
                            slotsToRemove = referencedSlots,
                        ),
                    )
                }
                NarrativeFunctionResult.Suspended -> {
                    currentState = currentState.updateTask(
                        index = taskIndex,
                        task = task,
                    )
                }
            }

            pending = collectUndispatchedSuspensionsLocked()
            if (executionJob?.isActive != true && !completion.isCompleted) {
                executionJob = launchExecutionLocked()
            }
        }
        pending.forEach { request ->
            scope.launch { dispatchSuspendedCall(request) }
        }
    }

    private fun collectUndispatchedSuspensionsLocked(): List<FunctionDispatchRequest> {
        return currentState.tasks.mapNotNull { task ->
            if (task.status !is NarrativeTaskStatus.SuspendedCall || task.id in inFlightTasks) {
                null
            } else {
                FunctionDispatchRequest(task.id)
            }
        }
    }

    private fun applySetVariableInstruction(
        task: NarrativeTaskState,
        instruction: SetVariableInstruction,
    ): NarrativeTaskState {
        val value = evaluateExpression(currentState, task, instruction.expression)
        val nextLocals = if (instruction.expression is SlotExpression) {
            val slotValue = task.slots[instruction.expression.slot]
            val locals = if (slotValue is NarrativeSlotValue.VariableReference && isInternalSlotVariable(slotValue.name)) {
                task.localVariables - slotValue.name
            } else {
                task.localVariables
            }
            locals + (instruction.name to value)
        } else {
            task.localVariables + (instruction.name to value)
        }
        return task.copy(
            instructionPointer = task.instructionPointer + 1,
            localVariables = nextLocals,
            slots = if (instruction.expression is SlotExpression) task.slots - instruction.expression.slot else task.slots,
        )
    }

    private fun applyResultTarget(
        task: NarrativeTaskState,
        resultTarget: NarrativeResultTarget?,
        value: NarrativeValue,
        nextInstructionPointer: Int,
    ): NarrativeTaskState {
        return when (resultTarget) {
            null -> task.copy(instructionPointer = nextInstructionPointer)
            is NarrativeResultTarget.Variable -> task.copy(
                instructionPointer = nextInstructionPointer,
                localVariables = task.localVariables + (resultTarget.name to value),
            )
            is NarrativeResultTarget.Slot -> {
                val slotVariableName = slotVariableName(resultTarget.slot)
                task.copy(
                    instructionPointer = nextInstructionPointer,
                    localVariables = task.localVariables + (slotVariableName to value),
                    slots = task.slots + (resultTarget.slot to NarrativeSlotValue.VariableReference(slotVariableName)),
                )
            }
        }
    }

    private fun applyExpressionResultTarget(
        task: NarrativeTaskState,
        resultTarget: NarrativeResultTarget,
        expression: NarrativeExpression,
        nextInstructionPointer: Int,
    ): NarrativeTaskState {
        return when (resultTarget) {
            is NarrativeResultTarget.Variable -> applyResultTarget(
                task = task,
                resultTarget = resultTarget,
                value = evaluateExpression(currentState, task, expression),
                nextInstructionPointer = nextInstructionPointer,
            )
            is NarrativeResultTarget.Slot -> {
                val slotVariableName = slotVariableName(resultTarget.slot)
                when (val slotValue = evaluateSlotValue(currentState, task, expression)) {
                    is NarrativeSlotValue.VariableReference -> task.copy(
                        instructionPointer = nextInstructionPointer,
                        slots = task.slots + (resultTarget.slot to slotValue),
                    )
                    is NarrativeSlotValue.Value -> task.copy(
                        instructionPointer = nextInstructionPointer,
                        localVariables = task.localVariables + (slotVariableName to slotValue.value),
                        slots = task.slots + (resultTarget.slot to NarrativeSlotValue.VariableReference(slotVariableName)),
                    )
                }
            }
        }
    }

    private fun evaluateSlotValue(
        state: NarrativeState,
        task: NarrativeTaskState,
        expression: NarrativeExpression,
    ): NarrativeSlotValue {
        return when (expression) {
            is VariableExpression -> NarrativeSlotValue.VariableReference(expression.name)
            is SlotExpression -> task.slots[expression.slot]
                ?: throw IllegalArgumentException("Slot `${expression.slot}` is not defined")
            else -> NarrativeSlotValue.Value(evaluateExpression(state, task, expression))
        }
    }

    private fun resolveSuspendedCallInstruction(task: NarrativeTaskState): CallFunctionInstruction {
        val instruction = program.instructions.getOrNull(task.instructionPointer) as? CallFunctionInstruction
            ?: throw IllegalStateException("Task `${task.id}` is suspended at instruction ${task.instructionPointer}, but no function call exists there")
        return instruction
    }

    private fun evaluateArguments(
        task: NarrativeTaskState,
        instruction: CallFunctionInstruction,
    ): List<NarrativeValue> {
        return instruction.arguments.map { evaluateExpression(currentState, task, it) }
    }

    private fun evaluateExpression(
        state: NarrativeState,
        task: NarrativeTaskState,
        expression: NarrativeExpression,
    ): NarrativeValue {
        return when (expression) {
            is LiteralExpression -> expression.value
            is VariableExpression -> task.localVariables[expression.name]
                ?: state.globals[expression.name]
                ?: throw IllegalArgumentException("Variable `${expression.name}` is not defined")
            is SlotExpression -> {
                when (val slotValue = task.slots[expression.slot]) {
                    null -> throw IllegalArgumentException("Slot `${expression.slot}` is not defined")
                    is NarrativeSlotValue.Value -> slotValue.value
                    is NarrativeSlotValue.VariableReference -> task.localVariables[slotValue.name]
                        ?: state.globals[slotValue.name]
                        ?: throw IllegalArgumentException("Variable `${slotValue.name}` is not defined")
                }
            }
            is UnaryExpression -> {
                val operand = evaluateExpression(state, task, expression.operand)
                when (expression.operator) {
                    NarrativeUnaryOperator.Plus -> NarrativeValue.Int32(operand.asInt())
                    NarrativeUnaryOperator.Minus -> NarrativeValue.Int32(-operand.asInt())
                    NarrativeUnaryOperator.Not -> NarrativeValue.Bool(!operand.asBoolean())
                }
            }
            is BinaryExpression -> {
                val left = evaluateExpression(state, task, expression.left)
                when (expression.operator) {
                    NarrativeBinaryOperator.Add -> {
                        val right = evaluateExpression(state, task, expression.right)
                        if (left is NarrativeValue.Text || right is NarrativeValue.Text) {
                            NarrativeValue.Text(left.asString() + right.asString())
                        } else {
                            NarrativeValue.Int32(left.asInt() + right.asInt())
                        }
                    }
                    NarrativeBinaryOperator.Subtract -> NarrativeValue.Int32(left.asInt() - evaluateExpression(state, task, expression.right).asInt())
                    NarrativeBinaryOperator.LessThan -> NarrativeValue.Bool(left.asInt() < evaluateExpression(state, task, expression.right).asInt())
                    NarrativeBinaryOperator.LessThanOrEquals -> NarrativeValue.Bool(left.asInt() <= evaluateExpression(state, task, expression.right).asInt())
                    NarrativeBinaryOperator.GreaterThan -> NarrativeValue.Bool(left.asInt() > evaluateExpression(state, task, expression.right).asInt())
                    NarrativeBinaryOperator.GreaterThanOrEquals -> NarrativeValue.Bool(left.asInt() >= evaluateExpression(state, task, expression.right).asInt())
                    NarrativeBinaryOperator.Equals -> NarrativeValue.Bool(left == evaluateExpression(state, task, expression.right))
                    NarrativeBinaryOperator.NotEquals -> NarrativeValue.Bool(left != evaluateExpression(state, task, expression.right))
                    NarrativeBinaryOperator.And -> NarrativeValue.Bool(left.asBoolean() && evaluateExpression(state, task, expression.right).asBoolean())
                    NarrativeBinaryOperator.Or -> NarrativeValue.Bool(left.asBoolean() || evaluateExpression(state, task, expression.right).asBoolean())
                }
            }
        }
    }

    private fun cleanupSlots(
        task: NarrativeTaskState,
        slotsToRemove: Set<Int>,
    ): NarrativeTaskState {
        if (slotsToRemove.isEmpty()) {
            return task
        }

        val removedSlotReferences = slotsToRemove.mapNotNull { slot ->
            (task.slots[slot] as? NarrativeSlotValue.VariableReference)?.name
        }.toSet()
        val remainingSlots = task.slots - slotsToRemove
        val remainingReferences = remainingSlots.values
            .mapNotNull { (it as? NarrativeSlotValue.VariableReference)?.name }
            .toSet()
        val localsToRemove = removedSlotReferences.filter { referencedName ->
            isInternalSlotVariable(referencedName) && referencedName !in remainingReferences
        }.toSet()

        return task.copy(
            slots = remainingSlots,
            localVariables = if (localsToRemove.isEmpty()) {
                task.localVariables
            } else {
                task.localVariables - localsToRemove
            },
        )
    }

    private fun collectReferencedSlots(expressions: List<NarrativeExpression>): Set<Int> {
        return expressions.flatMapTo(linkedSetOf()) { collectReferencedSlots(it) }
    }

    private fun collectReferencedSlots(expression: NarrativeExpression): Set<Int> {
        return when (expression) {
            is LiteralExpression -> emptySet()
            is VariableExpression -> emptySet()
            is SlotExpression -> setOf(expression.slot)
            is UnaryExpression -> collectReferencedSlots(expression.operand)
            is BinaryExpression -> collectReferencedSlots(expression.left) + collectReferencedSlots(expression.right)
        }
    }

    private fun NarrativeState.updateTask(index: Int, task: NarrativeTaskState): NarrativeState {
        return copy(tasks = tasks.mapIndexed { currentIndex, currentTask ->
            if (currentIndex == index) task else currentTask
        })
    }

    private fun NarrativeState.completeTask(index: Int): NarrativeState {
        return updateTask(index, tasks[index].copy(status = NarrativeTaskStatus.Completed))
    }

    private fun NarrativeValue.asBoolean(): Boolean {
        return when (this) {
            is NarrativeValue.Bool -> value
            else -> throw IllegalArgumentException("Expected boolean value but got $this")
        }
    }

    private fun NarrativeValue.asInt(): Int {
        return when (this) {
            is NarrativeValue.Int32 -> value
            else -> throw IllegalArgumentException("Expected int value but got $this")
        }
    }

    private fun NarrativeValue.asString(): String {
        return when (this) {
            NarrativeValue.Null -> "null"
            is NarrativeValue.Bool -> value.toString()
            is NarrativeValue.Int32 -> value.toString()
            is NarrativeValue.Text -> value
            is NarrativeValue.Entity -> id
            is NarrativeValue.HostObject -> value.toString()
        }
    }

    private fun slotVariableName(slot: Int): String = "$SLOT_VARIABLE_PREFIX$slot"

    private fun isInternalSlotVariable(name: String): Boolean = name.startsWith(SLOT_VARIABLE_PREFIX)
}

private data class FunctionDispatchRequest(
    val taskId: String,
)

private data class DispatchData(
    val taskId: String,
    val arguments: List<NarrativeValue>,
    val definition: NarrativeFunctionDefinition,
    val context: NarrativeFunctionDispatchContext,
)
