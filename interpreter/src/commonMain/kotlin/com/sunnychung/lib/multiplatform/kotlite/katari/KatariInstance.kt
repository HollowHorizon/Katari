package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KatariInstance(
    private val program: KatariProgram,
    initialState: KatariState = KatariState(
        programVersion = program.version,
        tasks = listOf(TaskState(id = program.entryTaskId)),
    ),
    private val functionRegistry: KatariFunctionRegistry = NarrativeBuiltinFunctions.registry(NarrativeNoOpHost),
    private val propertyRegistry: KatariPropertyRegistry = KatariPropertyRegistry(),
    private val snapshotCodec: StateSnapshotCodec = StateSnapshotCodec(),
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
    private var currentState: KatariState = initialState
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

    suspend fun serializeState(): KatariStateSnapshot {
        return mutex.withLock {
            val compacted = currentState.copy(tasks = currentState.tasks.map { compactTaskForSnapshot(it) })
            snapshotCodec.serialize(compacted)
        }
    }

    suspend fun currentState(): KatariState {
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
                val taskIndex = currentState.tasks.indexOfFirst { it.status == TaskStatus.Ready }
                if (taskIndex < 0) {
                    pendingSuspensions = collectUndispatchedSuspensionsLocked()
                    if (currentState.tasks.all { isTaskFinal(it.status) } && !completion.isCompleted) {
                        completion.complete(Unit)
                    }
                    shouldReturn = true
                    return@withLock
                }

                val task = normalizeTask(currentState.tasks[taskIndex])
                if (task.instructionPointer >= program.instructions.size) {
                    currentState = currentState.completeTask(taskIndex)
                } else {
                    val instruction = program.instructions[task.instructionPointer]
                    try {
                        when (instruction) {
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
                            is EnterCallFrameInstruction -> {
                                val nextFrame = CallFrameState(
                                    id = task.nextCallFrameId,
                                    functionId = instruction.functionId,
                                    lexicalParentFrameId = instruction.lexicalParentFrameId,
                                )
                                val updated = task.copy(
                                    instructionPointer = task.instructionPointer + 1,
                                    callFrames = task.callFrames + nextFrame,
                                    nextCallFrameId = task.nextCallFrameId + 1,
                                )
                                currentState = currentState.updateTask(index = taskIndex, task = syncTopLocals(updated))
                            }
                            is ExitCallFrameInstruction -> {
                                val returnValue = instruction.returnExpression?.let { evaluateExpression(currentState, task, it) }
                                    ?: KatariValue.Null
                                currentState = currentState.updateTask(
                                    index = taskIndex,
                                    task = applyExitCallFrameInstruction(task, instruction, returnValue),
                                )
                            }
                            is RemoveVariablesInstruction -> {
                                val frame = currentCallFrame(task)
                                val names = instruction.names.toSet()
                                val updatedFrame = frame.copy(localVariables = frame.localVariables - names)
                                val nextTask = syncTopLocals(
                                    task.copy(
                                        instructionPointer = task.instructionPointer + 1,
                                        callFrames = task.callFrames.replaceLast(updatedFrame),
                                        slots = task.slots.filterValues { slotValue ->
                                            slotValue !is SlotValue.VariableReference ||
                                                slotValue.frameId != frame.id || slotValue.name !in names
                                        },
                                    )
                                )
                                currentState = currentState.updateTask(index = taskIndex, task = nextTask)
                            }
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) {
                            throw e
                        }
                        val message = buildRuntimeErrorMessage(instruction.position, e)
                        currentState = currentState.updateTask(
                            index = taskIndex,
                            task = task.copy(status = TaskStatus.Failed(message = message)),
                        )
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
        task: TaskState,
        instruction: CallFunctionInstruction,
    ): FunctionDispatchRequest? {
        val call = resolveFunctionCall(task, instruction)
        val referencedSlots = collectReferencedSlots(instruction.arguments)
        val definition = call.definition
        val arguments = call.arguments
        return when (val result = definition.startCall(arguments, DefaultKatariFunctionContext(currentState, task))) {
            is FunctionResult.Returned -> {
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
            FunctionResult.Suspended -> {
                val suspendedStatus = TaskStatus.SuspendedCall(
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
                currentTask.id == request.taskId && currentTask.status is TaskStatus.SuspendedCall
            }?.let { normalizeTask(it) } ?: return
            inFlightTasks += request.taskId
            val instruction = resolveSuspendedCallInstruction(task)
            val call = resolveFunctionCall(task, instruction)
            DispatchData(
                taskId = task.id,
                arguments = call.arguments,
                definition = call.definition,
                context = DefaultKatariFunctionContext(currentState, task),
            )
        }

        try {
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
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            markTaskAsFailed(
                taskId = dispatchData.taskId,
                message = buildRuntimeErrorMessage(null, e),
            )
        }
    }

    private suspend fun handleResume(
        taskId: String,
        response: FunctionResponse?,
    ) {
        val pending: List<FunctionDispatchRequest>
        mutex.withLock {
            inFlightTasks -= taskId
            val taskIndex = currentState.tasks.indexOfFirst {
                it.id == taskId && it.status is TaskStatus.SuspendedCall
            }
            if (taskIndex < 0 || cancelled) return

            val task = normalizeTask(currentState.tasks[taskIndex])
            val status = task.status as TaskStatus.SuspendedCall
            val instruction = resolveSuspendedCallInstruction(task)
            val call = resolveFunctionCall(task, instruction)
            val referencedSlots = collectReferencedSlots(instruction.arguments)
            val definition = call.definition
            val arguments = call.arguments
            try {
                when (val result = definition.resumeCall(
                    arguments = arguments,
                    response = response,
                    context = DefaultKatariFunctionContext(currentState, task),
                )) {
                    is FunctionResult.Returned -> {
                        currentState = currentState.updateTask(
                            index = taskIndex,
                            task = cleanupSlots(
                                task = applyResultTarget(
                                    task = task.copy(status = TaskStatus.Ready),
                                    resultTarget = status.resultTarget,
                                    value = result.value,
                                    nextInstructionPointer = status.nextInstructionPointer,
                                ),
                                slotsToRemove = referencedSlots,
                            ),
                        )
                    }
                    FunctionResult.Suspended -> {
                        currentState = currentState.updateTask(
                            index = taskIndex,
                            task = task,
                        )
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                currentState = currentState.updateTask(
                    index = taskIndex,
                    task = task.copy(status = TaskStatus.Failed(message = buildRuntimeErrorMessage(null, e))),
                )
            }

            pending = collectUndispatchedSuspensionsLocked()
            if (currentState.tasks.all { isTaskFinal(it.status) } && !completion.isCompleted) {
                completion.complete(Unit)
            } else if (executionJob?.isActive != true && !completion.isCompleted) {
                executionJob = launchExecutionLocked()
            }
            Unit
        }
        pending.forEach { request ->
            scope.launch { dispatchSuspendedCall(request) }
        }
    }

    private fun collectUndispatchedSuspensionsLocked(): List<FunctionDispatchRequest> {
        return currentState.tasks.mapNotNull { task ->
            if (task.status !is TaskStatus.SuspendedCall || task.id in inFlightTasks) {
                null
            } else {
                FunctionDispatchRequest(task.id)
            }
        }
    }

    private fun applySetVariableInstruction(
        task: TaskState,
        instruction: SetVariableInstruction,
    ): TaskState {
        val value = evaluateExpression(currentState, task, instruction.expression)
        if (!instruction.declaresLocal && findFrameVariable(task, currentCallFrame(task).id, instruction.name) == null) {
            val nextTask = propertyRegistry.setGlobal(instruction.name, value)?.let {
                task.copy(
                    instructionPointer = task.instructionPointer + 1,
                    slots = if (instruction.expression is SlotExpression) task.slots - instruction.expression.slot else task.slots,
                )
            }
            if (nextTask != null) {
                return syncTopLocals(nextTask)
            }
        }
        val frame = currentCallFrame(task)
        val updated = frame.copy(localVariables = frame.localVariables + (instruction.name to value))
        return syncTopLocals(
            task.copy(
                instructionPointer = task.instructionPointer + 1,
                callFrames = task.callFrames.replaceLast(updated),
                slots = if (instruction.expression is SlotExpression) task.slots - instruction.expression.slot else task.slots,
            )
        )
    }

    private fun applyResultTarget(
        task: TaskState,
        resultTarget: ResultTarget?,
        value: KatariValue,
        nextInstructionPointer: Int,
    ): TaskState {
        return when (resultTarget) {
            null -> task.copy(instructionPointer = nextInstructionPointer)
            is ResultTarget.Variable -> {
                if (
                    !resultTarget.declaresLocal &&
                    findFrameVariable(task, currentCallFrame(task).id, resultTarget.name) == null &&
                    propertyRegistry.setGlobal(resultTarget.name, value) != null
                ) {
                    return task.copy(instructionPointer = nextInstructionPointer)
                }
                val frame = currentCallFrame(task)
                val updated = frame.copy(localVariables = frame.localVariables + (resultTarget.name to value))
                syncTopLocals(task.copy(instructionPointer = nextInstructionPointer, callFrames = task.callFrames.replaceLast(updated)))
            }
            is ResultTarget.Slot -> {
                val frame = currentCallFrame(task)
                val slotVariableName = slotVariableName(resultTarget.slot)
                val updated = frame.copy(localVariables = frame.localVariables + (slotVariableName to value))
                syncTopLocals(
                    task.copy(
                        instructionPointer = nextInstructionPointer,
                        callFrames = task.callFrames.replaceLast(updated),
                        slots = task.slots + (
                            resultTarget.slot to SlotValue.VariableReference(name = slotVariableName, frameId = frame.id)
                        ),
                    )
                )
            }
        }
    }

    private fun applyExpressionResultTarget(
        task: TaskState,
        resultTarget: ResultTarget,
        expression: KatariExpression,
        nextInstructionPointer: Int,
    ): TaskState {
        return when (resultTarget) {
            is ResultTarget.Variable -> applyResultTarget(
                task = task,
                resultTarget = resultTarget,
                value = evaluateExpression(currentState, task, expression),
                nextInstructionPointer = nextInstructionPointer,
            )
            is ResultTarget.Slot -> {
                val reference = evaluateSlotReference(currentState, task, expression)
                if (reference != null) {
                    task.copy(
                        instructionPointer = nextInstructionPointer,
                        slots = task.slots + (resultTarget.slot to reference),
                    )
                } else {
                    val frame = currentCallFrame(task)
                    val slotVariableName = slotVariableName(resultTarget.slot)
                    val value = evaluateExpression(currentState, task, expression)
                    val updated = frame.copy(localVariables = frame.localVariables + (slotVariableName to value))
                    syncTopLocals(
                        task.copy(
                            instructionPointer = nextInstructionPointer,
                            callFrames = task.callFrames.replaceLast(updated),
                            slots = task.slots + (
                                resultTarget.slot to SlotValue.VariableReference(name = slotVariableName, frameId = frame.id)
                            ),
                        )
                    )
                }
            }
        }
    }

    private fun evaluateSlotReference(
        state: KatariState,
        task: TaskState,
        expression: KatariExpression,
    ): SlotValue.VariableReference? {
        return when (expression) {
            is VariableExpression -> resolveVariableReference(state, task, expression.name)
            is SlotExpression -> task.slots[expression.slot] as? SlotValue.VariableReference
                ?: throw IllegalArgumentException("Slot `${expression.slot}` is not defined")
            else -> null
        }
    }

    private fun resolveSuspendedCallInstruction(task: TaskState): CallFunctionInstruction {
        return program.instructions.getOrNull(task.instructionPointer) as? CallFunctionInstruction
            ?: throw IllegalStateException("Task `${task.id}` is suspended at instruction ${task.instructionPointer}, but no function call exists there")
    }

    private fun evaluateArguments(
        task: TaskState,
        instruction: CallFunctionInstruction,
    ): List<KatariValue> {
        return instruction.arguments.map { evaluateExpression(currentState, task, it) }
    }

    private fun resolveFunctionCall(
        task: TaskState,
        instruction: CallFunctionInstruction,
    ): KatariResolvedFunctionCall {
        val argumentNames = instruction.argumentNames.takeIf { it.isNotEmpty() }
            ?: List(instruction.arguments.size) { null }
        require(argumentNames.size == instruction.arguments.size) {
            "Call `${instruction.functionId}` has ${instruction.arguments.size} arguments but ${argumentNames.size} argument names"
        }
        return functionRegistry.resolve(
            id = instruction.functionId,
            arguments = instruction.arguments.mapIndexed { index, expression ->
                KatariCallArgument(
                    name = argumentNames[index],
                    value = evaluateExpression(currentState, task, expression),
                )
            },
        )
    }

    private fun evaluateExpression(
        state: KatariState,
        task: TaskState,
        expression: KatariExpression,
    ): KatariValue {
        try {
            return when (expression) {
                is LiteralExpression -> expression.value
                is VariableExpression -> resolveVariableValue(state, task, expression.name)
                is SlotExpression -> resolveSlotValue(state, task, expression.slot)
                is LambdaLiteralExpression -> KatariValue.Lambda(expression.lambdaId)
                is EnumEntryExpression -> program.enumDefinitions.getValue(expression.typeId).entry(expression.entryName)
                is EnumEntriesExpression -> {
                    val definition = program.enumDefinitions.getValue(expression.typeId)
                    KatariValue.EnumEntries(typeId = expression.typeId, entries = definition.entries)
                }
                is EnumValueOfExpression -> {
                    val entryName = evaluateExpression(state, task, expression.entryName) as? KatariValue.Text
                        ?: throw IllegalArgumentException("Enum valueOf expects String argument")
                    program.enumDefinitions.getValue(expression.typeId).entry(entryName.value)
                }
                is EnumPropertyExpression -> {
                    val receiver = evaluateExpression(state, task, expression.receiver) as? KatariValue.EnumValue
                        ?: throw IllegalArgumentException("Enum property `${expression.propertyName}` expects enum receiver")
                    when (expression.propertyName) {
                        "name" -> KatariValue.Text(receiver.entryName)
                        "ordinal" -> KatariValue.Int32(receiver.ordinal)
                        else -> receiver.properties[expression.propertyName]
                            ?: throw IllegalArgumentException(
                                "Enum `${receiver.typeId}` has no property `${expression.propertyName}`"
                            )
                    }
                }
                is UnaryExpression -> {
                    val operand = evaluateExpression(state, task, expression.operand)
                    when (expression.operator) {
                        UnaryOperator.Plus -> operand.numericIdentity()
                        UnaryOperator.Minus -> operand.negateNumeric()
                        UnaryOperator.Not -> KatariValue.Bool(!operand.asBoolean())
                    }
                }
                is BinaryExpression -> {
                    val left = evaluateExpression(state, task, expression.left)
                    when (expression.operator) {
                        BinaryOperator.Add -> {
                            val right = evaluateExpression(state, task, expression.right)
                            if (left is KatariValue.Text || right is KatariValue.Text) {
                                KatariValue.Text(left.asString() + right.asString())
                            } else if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                                KatariValue.Float64(left.asDouble() + right.asDouble())
                            } else {
                                KatariValue.Int32(left.asInt() + right.asInt())
                            }
                        }
                        BinaryOperator.Subtract -> {
                            val right = evaluateExpression(state, task, expression.right)
                            if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                                KatariValue.Float64(left.asDouble() - right.asDouble())
                            } else {
                        KatariValue.Int32(left.asInt() - right.asInt())
                    }
                }
                BinaryOperator.Multiply -> {
                    val right = evaluateExpression(state, task, expression.right)
                    if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                        KatariValue.Float64(left.asDouble() * right.asDouble())
                    } else {
                        KatariValue.Int32(left.asInt() * right.asInt())
                    }
                }
                BinaryOperator.Divide -> {
                    val right = evaluateExpression(state, task, expression.right)
                    if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                        KatariValue.Float64(left.asDouble() / right.asDouble())
                    } else {
                        KatariValue.Int32(left.asInt() / right.asInt())
                    }
                }
                BinaryOperator.Remainder -> {
                    val right = evaluateExpression(state, task, expression.right)
                    if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                        KatariValue.Float64(left.asDouble() % right.asDouble())
                    } else {
                        KatariValue.Int32(left.asInt() % right.asInt())
                    }
                }
                BinaryOperator.LessThan -> {
                    val right = evaluateExpression(state, task, expression.right)
                    if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                                KatariValue.Bool(left.asDouble() < right.asDouble())
                            } else {
                                KatariValue.Bool(left.asInt() < right.asInt())
                            }
                        }
                        BinaryOperator.LessThanOrEquals -> {
                            val right = evaluateExpression(state, task, expression.right)
                            if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                                KatariValue.Bool(left.asDouble() <= right.asDouble())
                            } else {
                                KatariValue.Bool(left.asInt() <= right.asInt())
                            }
                        }
                        BinaryOperator.GreaterThan -> {
                            val right = evaluateExpression(state, task, expression.right)
                            if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                                KatariValue.Bool(left.asDouble() > right.asDouble())
                            } else {
                                KatariValue.Bool(left.asInt() > right.asInt())
                            }
                        }
                        BinaryOperator.GreaterThanOrEquals -> {
                            val right = evaluateExpression(state, task, expression.right)
                            if (left is KatariValue.Float64 || right is KatariValue.Float64) {
                                KatariValue.Bool(left.asDouble() >= right.asDouble())
                            } else {
                                KatariValue.Bool(left.asInt() >= right.asInt())
                            }
                        }
                        BinaryOperator.Equals -> KatariValue.Bool(left == evaluateExpression(state, task, expression.right))
                        BinaryOperator.NotEquals -> KatariValue.Bool(left != evaluateExpression(state, task, expression.right))
                        BinaryOperator.And -> KatariValue.Bool(left.asBoolean() && evaluateExpression(state, task, expression.right).asBoolean())
                        BinaryOperator.Or -> KatariValue.Bool(left.asBoolean() || evaluateExpression(state, task, expression.right).asBoolean())
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            if (e is KatariExpressionEvaluationException) {
                if (e.position != null || expression.position == null) {
                    throw e
                }
                throw KatariExpressionEvaluationException(expression.position, e.cause ?: e)
            }
            throw KatariExpressionEvaluationException(expression.position, e)
        }
    }

    private fun resolveVariableValue(state: KatariState, task: TaskState, name: String): KatariValue {
        val frameValue = findFrameVariable(task, currentCallFrame(task).id, name)
        if (frameValue != null) {
            return frameValue.second
        }
        return state.globals[name]
            ?: propertyRegistry.getGlobal(name)
            ?: throw IllegalArgumentException("Variable `$name` is not defined")
    }

    private fun resolveVariableReference(
        state: KatariState,
        task: TaskState,
        name: String,
    ): SlotValue.VariableReference {
        val frameValue = findFrameVariable(task, currentCallFrame(task).id, name)
        if (frameValue != null) {
            return SlotValue.VariableReference(name = name, frameId = frameValue.first.id)
        }
        if (name in state.globals) {
            return SlotValue.VariableReference(name = name, frameId = null)
        }
        if (propertyRegistry.hasGlobal(name)) {
            return SlotValue.VariableReference(name = name, frameId = null)
        }
        throw IllegalArgumentException("Variable `$name` is not defined")
    }

    private fun resolveSlotValue(state: KatariState, task: TaskState, slot: Int): KatariValue {
        val reference = task.slots[slot] as? SlotValue.VariableReference
            ?: throw IllegalArgumentException("Slot `$slot` is not defined")
        return resolveReferenceValue(state, task, reference)
    }

    private fun resolveReferenceValue(
        state: KatariState,
        task: TaskState,
        reference: SlotValue.VariableReference,
    ): KatariValue {
        return if (reference.frameId == null) {
            state.globals[reference.name]
                ?: propertyRegistry.getGlobal(reference.name)
                ?: throw IllegalArgumentException("Global variable `${reference.name}` is not defined")
        } else {
            findFrameById(task, reference.frameId)
                ?.localVariables
                ?.get(reference.name)
                ?: throw IllegalArgumentException("Frame variable `${reference.name}` is not defined for frame `${reference.frameId}`")
        }
    }

    private fun findFrameVariable(task: TaskState, frameId: Int, name: String): Pair<CallFrameState, KatariValue>? {
        val frame = findFrameById(task, frameId) ?: return null
        val value = frame.localVariables[name]
        if (value != null) {
            return frame to value
        }
        val lexicalParent = frame.lexicalParentFrameId ?: return null
        return findFrameVariable(task, lexicalParent, name)
    }

    private fun findFrameById(task: TaskState, frameId: Int): CallFrameState? {
        return task.callFrames.firstOrNull { it.id == frameId }
    }

    private fun applyExitCallFrameInstruction(
        task: TaskState,
        instruction: ExitCallFrameInstruction,
        returnValue: KatariValue,
    ): TaskState {
        require(task.callFrames.size > 1) { "Cannot exit root call frame" }
        val exitingFrame = currentCallFrame(task)
        val popped = task.copy(
            callFrames = task.callFrames.dropLast(1),
            instructionPointer = task.instructionPointer + 1,
            slots = task.slots.filterValues { ref ->
                (ref as? SlotValue.VariableReference)?.frameId != exitingFrame.id
            },
        )
        val nextTask = applyResultTarget(
            task = syncTopLocals(popped),
            resultTarget = instruction.resultTarget,
            value = returnValue,
            nextInstructionPointer = popped.instructionPointer,
        )
        return syncTopLocals(nextTask)
    }

    private fun cleanupSlots(task: TaskState, slotsToRemove: Set<Int>): TaskState {
        if (slotsToRemove.isEmpty()) {
            return task
        }

        val removedReferences = slotsToRemove.mapNotNull { slot ->
            task.slots[slot] as? SlotValue.VariableReference
        }
        val remainingSlots = task.slots - slotsToRemove
        val remainingReferences = remainingSlots.values
            .mapNotNull { it as? SlotValue.VariableReference }
            .map { it.frameId to it.name }
            .toSet()

        var nextTask = task.copy(slots = remainingSlots)
        removedReferences.forEach { removed ->
            val key = removed.frameId to removed.name
            if (isInternalSlotVariable(removed.name) && key !in remainingReferences && removed.frameId != null) {
                nextTask = removeFrameLocal(nextTask, removed.frameId, removed.name)
            }
        }
        return syncTopLocals(nextTask)
    }

    private fun removeFrameLocal(task: TaskState, frameId: Int, name: String): TaskState {
        val index = task.callFrames.indexOfFirst { it.id == frameId }
        if (index < 0) return task
        val frame = task.callFrames[index]
        if (name !in frame.localVariables) return task
        val updated = frame.copy(localVariables = frame.localVariables - name)
        val frames = task.callFrames.toMutableList()
        frames[index] = updated
        return task.copy(callFrames = frames)
    }

    private fun collectReferencedSlots(expressions: List<KatariExpression>): Set<Int> {
        return expressions.flatMapTo(linkedSetOf()) { collectReferencedSlots(it) }
    }

    private fun collectReferencedSlots(expression: KatariExpression): Set<Int> {
        return when (expression) {
            is LiteralExpression -> emptySet()
            is VariableExpression -> emptySet()
            is SlotExpression -> setOf(expression.slot)
            is LambdaLiteralExpression -> emptySet()
            is EnumEntryExpression -> emptySet()
            is EnumEntriesExpression -> emptySet()
            is EnumValueOfExpression -> collectReferencedSlots(expression.entryName)
            is EnumPropertyExpression -> collectReferencedSlots(expression.receiver)
            is UnaryExpression -> collectReferencedSlots(expression.operand)
            is BinaryExpression -> collectReferencedSlots(expression.left) + collectReferencedSlots(expression.right)
        }
    }

    private fun KatariState.updateTask(index: Int, task: TaskState): KatariState {
        return copy(tasks = tasks.mapIndexed { currentIndex, currentTask ->
            if (currentIndex == index) syncTopLocals(normalizeTask(task)) else currentTask
        })
    }

    private fun KatariState.completeTask(index: Int): KatariState {
        return updateTask(index, tasks[index].copy(status = TaskStatus.Completed))
    }

    private fun isTaskFinal(status: TaskStatus): Boolean {
        return status == TaskStatus.Completed || status is TaskStatus.Failed
    }

    private fun buildRuntimeErrorMessage(position: Any?, error: Throwable): String {
        var resolvedPosition = position
        var resolvedError: Throwable = error
        if (error is KatariExpressionEvaluationException) {
            if (error.position != null) {
                resolvedPosition = error.position
            }
            resolvedError = error.cause ?: error
        }
        val base = resolvedError.message ?: resolvedError::class.simpleName ?: "Unknown Katari runtime error"
        return when (resolvedPosition) {
            null -> base
            is SourcePosition -> "$resolvedPosition $base"
            else -> "[$resolvedPosition] $base"
        }
    }

    private suspend fun markTaskAsFailed(taskId: String, message: String) {
        mutex.withLock {
            val taskIndex = currentState.tasks.indexOfFirst { it.id == taskId }
            if (taskIndex < 0) return
            val task = normalizeTask(currentState.tasks[taskIndex])
            currentState = currentState.updateTask(
                index = taskIndex,
                task = task.copy(status = TaskStatus.Failed(message = message)),
            )
            if (currentState.tasks.all { isTaskFinal(it.status) } && !completion.isCompleted) {
                completion.complete(Unit)
            }
            Unit
        }
    }

    private fun normalizeTask(task: TaskState): TaskState {
        if (task.callFrames.isNotEmpty()) {
            return syncTopLocals(task)
        }
        return task.copy(
            callFrames = listOf(
                CallFrameState(
                    id = ROOT_CALL_FRAME_ID,
                    functionId = ROOT_CALL_FRAME_FUNCTION_ID,
                    lexicalParentFrameId = null,
                    localVariables = task.localVariables,
                )
            ),
            nextCallFrameId = maxOf(task.nextCallFrameId, ROOT_CALL_FRAME_ID + 1),
        )
    }

    private fun syncTopLocals(task: TaskState): TaskState {
        val top = task.callFrames.lastOrNull() ?: return task
        return task.copy(localVariables = top.localVariables)
    }

    private fun currentCallFrame(task: TaskState): CallFrameState {
        return task.callFrames.lastOrNull()
            ?: CallFrameState(
                id = ROOT_CALL_FRAME_ID,
                functionId = ROOT_CALL_FRAME_FUNCTION_ID,
                lexicalParentFrameId = null,
                localVariables = task.localVariables,
            )
    }

    private fun compactTaskForSnapshot(task: TaskState): TaskState {
        val normalized = normalizeTask(task)
        val existingFrameIds = normalized.callFrames.map { it.id }.toSet()
        val filteredSlots = normalized.slots.filterValues { value ->
            val ref = value as SlotValue.VariableReference
            ref.frameId == null || ref.frameId in existingFrameIds
        }
        val liveRefs = filteredSlots.values
            .map { it as SlotValue.VariableReference }
            .map { it.frameId to it.name }
            .toSet()

        val compactedFrames = normalized.callFrames.map { frame ->
            val locals = frame.localVariables.filterKeys { name ->
                !isInternalSlotVariable(name) || (frame.id to name) in liveRefs
            }
            frame.copy(localVariables = locals)
        }

        return syncTopLocals(
            normalized.copy(
                callFrames = compactedFrames,
                slots = filteredSlots,
            )
        )
    }

    private fun KatariValue.asBoolean(): Boolean {
        return when (this) {
            is KatariValue.Bool -> value
            else -> throw IllegalArgumentException("Expected boolean value but got $this")
        }
    }

    private fun KatariValue.asInt(): Int {
        return when (this) {
            is KatariValue.Int32 -> value
            else -> throw IllegalArgumentException("Expected int value but got $this")
        }
    }

    private fun KatariValue.asDouble(): Double {
        return when (this) {
            is KatariValue.Int32 -> value.toDouble()
            is KatariValue.Float64 -> value
            else -> throw IllegalArgumentException("Expected numeric value but got $this")
        }
    }

    private fun KatariValue.numericIdentity(): KatariValue {
        return when (this) {
            is KatariValue.Int32 -> this
            is KatariValue.Float64 -> this
            else -> throw IllegalArgumentException("Expected numeric value but got $this")
        }
    }

    private fun KatariValue.negateNumeric(): KatariValue {
        return when (this) {
            is KatariValue.Int32 -> KatariValue.Int32(-value)
            is KatariValue.Float64 -> KatariValue.Float64(-value)
            else -> throw IllegalArgumentException("Expected numeric value but got $this")
        }
    }

    private fun KatariValue.asString(): String {
        return when (this) {
            KatariValue.Null -> "null"
            KatariValue.DefaultArgument -> "<default>"
            is KatariValue.Bool -> value.toString()
            is KatariValue.Int32 -> value.toString()
            is KatariValue.Float64 -> value.toString()
            is KatariValue.Text -> value
            is KatariValue.Lambda -> "Lambda($id)"
            is KatariValue.EnumValue -> entryName
            is KatariValue.EnumEntries -> entries.joinToString(prefix = "[", postfix = "]") { it.entryName }
            is KatariValue.HostObject -> value.toString()
        }
    }

    private fun slotVariableName(slot: Int): String = "$SLOT_VARIABLE_PREFIX$slot"

    private fun isInternalSlotVariable(name: String): Boolean = name.startsWith(SLOT_VARIABLE_PREFIX)

    private fun List<CallFrameState>.replaceLast(frame: CallFrameState): List<CallFrameState> {
        if (isEmpty()) return this
        val list = toMutableList()
        list[list.lastIndex] = frame
        return list
    }
}

private data class FunctionDispatchRequest(
    val taskId: String,
)

private data class DispatchData(
    val taskId: String,
    val arguments: List<KatariValue>,
    val definition: KatariFunctionDefinition,
    val context: KatariFunctionDispatchContext,
)

private class KatariExpressionEvaluationException(
    val position: SourcePosition?,
    cause: Throwable,
) : RuntimeException(cause.message, cause)
