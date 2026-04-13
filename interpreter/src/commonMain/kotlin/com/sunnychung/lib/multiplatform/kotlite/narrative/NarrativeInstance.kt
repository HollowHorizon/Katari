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
    private val snapshotCodec: NarrativeStateSnapshotCodec = NarrativeStateSnapshotCodec(functionRegistry = functionRegistry),
    coroutineScope: CoroutineScope? = null,
) {

    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val completion = CompletableDeferred<Unit>()
    private val inFlightEffects = mutableSetOf<String>()
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
        scope.cancel()
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
                            currentState = currentState.updateTask(
                                index = taskIndex,
                                task = task.copy(
                                    instructionPointer = task.instructionPointer + 1,
                                    localVariables = task.localVariables + (instruction.name to evaluateExpression(currentState, task, instruction.expression)),
                                ),
                            )
                        }
                        is ConditionalJumpInstruction -> {
                            val isTrue = evaluateExpression(currentState, task, instruction.condition).asBoolean()
                            currentState = currentState.updateTask(
                                index = taskIndex,
                                task = task.copy(
                                    instructionPointer = if (isTrue) task.instructionPointer + 1 else instruction.falseTarget,
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
        val arguments = instruction.arguments.map { evaluateExpression(currentState, task, it) }
        @Suppress("UNCHECKED_CAST")
        val definition = functionRegistry.definition(instruction.functionId) as NarrativeFunctionDefinition<NarrativeFunctionStateSnapshot, NarrativeFunctionEffectPayload>
        return when (val result = definition.startCall(arguments, DefaultNarrativeFunctionContext(currentState, task))) {
            is NarrativeFunctionResult.Returned -> {
                currentState = currentState.updateTask(
                    index = taskIndex,
                    task = task.copy(
                        instructionPointer = task.instructionPointer + 1,
                        slots = assignResult(task, instruction.resultSlot, result.value),
                    ),
                )
                null
            }
            is NarrativeFunctionResult.Suspended -> {
                val effectId = "${task.id}:${task.instructionPointer}"
                val suspendedStatus = NarrativeTaskStatus.SuspendedCall(
                    effectId = effectId,
                    functionId = instruction.functionId,
                    resultSlot = instruction.resultSlot,
                    nextInstructionPointer = task.instructionPointer + 1,
                    payload = result.payload,
                    continuation = result.continuation,
                )
                currentState = currentState.updateTask(
                    index = taskIndex,
                    task = task.copy(status = suspendedStatus),
                )
                FunctionDispatchRequest(task.id, effectId, instruction.functionId, suspendedStatus.payload, suspendedStatus.continuation)
            }
        }
    }

    private suspend fun dispatchSuspendedCall(request: FunctionDispatchRequest) {
        val context = mutex.withLock {
            if (request.effectId in inFlightEffects) return
            inFlightEffects += request.effectId
            val task = currentState.tasks.first { currentTask ->
                val status = currentTask.status
                status is NarrativeTaskStatus.SuspendedCall && status.effectId == request.effectId
            }
            DefaultNarrativeFunctionContext(currentState, task)
        }

        @Suppress("UNCHECKED_CAST")
        val definition = functionRegistry
            .definition(request.functionId) as NarrativeFunctionDefinition<NarrativeFunctionStateSnapshot, NarrativeFunctionEffectPayload>
        definition.dispatch(
            payload = request.payload,
            context = context,
            resume = { response ->
                val activeJob = executionJob
                scope.launch {
                    activeJob?.join()
                    handleResume(request.effectId, response)
                }
            },
        )
    }

    private suspend fun handleResume(
        effectId: String,
        response: NarrativeFunctionResponse?,
    ) {
        mutex.withLock {
            inFlightEffects -= effectId
            val taskIndex = currentState.tasks.indexOfFirst {
                val status = it.status
                status is NarrativeTaskStatus.SuspendedCall && status.effectId == effectId
            }
            if (taskIndex < 0 || cancelled) return

            val task = currentState.tasks[taskIndex]
            val status = task.status as NarrativeTaskStatus.SuspendedCall
            @Suppress("UNCHECKED_CAST")
            val definition = functionRegistry
                .definition(status.functionId) as NarrativeFunctionDefinition<NarrativeFunctionStateSnapshot, NarrativeFunctionEffectPayload>
            when (val result = definition.resumeCall(
                continuation = status.continuation,
                response = response,
                context = DefaultNarrativeFunctionContext(currentState, task),
            )) {
                is NarrativeFunctionResult.Returned -> {
                    currentState = currentState.updateTask(
                        index = taskIndex,
                        task = task.copy(
                            instructionPointer = status.nextInstructionPointer,
                            slots = assignResult(task, status.resultSlot, result.value),
                            status = NarrativeTaskStatus.Ready,
                        ),
                    )
                }
                is NarrativeFunctionResult.Suspended -> {
                    currentState = currentState.updateTask(
                        index = taskIndex,
                        task = task.copy(
                            status = status.copy(
                                payload = result.payload,
                                continuation = result.continuation,
                            ),
                        ),
                    )
                }
            }

            val pending = collectUndispatchedSuspensionsLocked()
            if (executionJob?.isActive != true && !completion.isCompleted) {
                executionJob = launchExecutionLocked()
            }
            pending.forEach { request ->
                scope.launch { dispatchSuspendedCall(request) }
            }
        }
    }

    private fun collectUndispatchedSuspensionsLocked(): List<FunctionDispatchRequest> {
        return currentState.tasks.mapNotNull { task ->
            val status = task.status as? NarrativeTaskStatus.SuspendedCall ?: return@mapNotNull null
            if (status.effectId in inFlightEffects) {
                null
            } else {
                FunctionDispatchRequest(
                    taskId = task.id,
                    effectId = status.effectId,
                    functionId = status.functionId,
                    payload = status.payload,
                    continuation = status.continuation,
                )
            }
        }
    }

    private fun assignResult(
        task: NarrativeTaskState,
        resultSlot: Int?,
        value: NarrativeValue,
    ): Map<Int, NarrativeValue> {
        return if (resultSlot != null) {
            task.slots + (resultSlot to value)
        } else {
            task.slots
        }
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
            is SlotExpression -> task.slots[expression.slot]
                ?: throw IllegalArgumentException("Slot `${expression.slot}` is not defined")
            is BinaryExpression -> {
                val left = evaluateExpression(state, task, expression.left)
                when (expression.operator) {
                    NarrativeBinaryOperator.Add -> NarrativeValue.Int32(left.asInt() + evaluateExpression(state, task, expression.right).asInt())
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
}

private data class FunctionDispatchRequest(
    val taskId: String,
    val effectId: String,
    val functionId: String,
    val payload: NarrativeFunctionEffectPayload,
    val continuation: NarrativeFunctionStateSnapshot,
)
