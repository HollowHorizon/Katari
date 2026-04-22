package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.ClassInstance
import com.sunnychung.lib.multiplatform.kotlite.model.DataType
import com.sunnychung.lib.multiplatform.kotlite.model.DelegatedValue
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.IteratorValue
import com.sunnychung.lib.multiplatform.kotlite.model.KotlinValueHolder
import com.sunnychung.lib.multiplatform.kotlite.model.ListValue
import com.sunnychung.lib.multiplatform.kotlite.model.NullValue
import com.sunnychung.lib.multiplatform.kotlite.model.PairValue
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeMapEntry
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValueSnapshotIterator
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.model.SymbolTable
import com.sunnychung.lib.multiplatform.kotlite.model.toTypeNode
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic

class StateSnapshotCodec(
    private val valueCodecs: KatariValueCodecRegistry = KatariValueCodecRegistry(emptyList()),
    private val executionEnvironment: ExecutionEnvironment = ExecutionEnvironment(),
) {
    private val runtimeInterpreter by lazy {
        KotliteInterpreter(
            filename = "<NarrativeSnapshot>",
            code = "",
            executionEnvironment = executionEnvironment,
        )
    }

    fun serialize(state: KatariState): KatariStateSnapshot {
        val valueTable = NarrativeSnapshotValueTable()
        val tasks = state.tasks.map { task ->
            TaskSnapshot(
                id = task.id,
                instructionPointer = task.instructionPointer,
                variableRefs = if (task.callFrames.isEmpty()) {
                    task.localVariables.mapValues { (_, value) -> valueTable.reference(value) }
                } else {
                    emptyMap()
                },
                callFrames = task.callFrames.map { frame ->
                    CallFrameSnapshot(
                        id = frame.id,
                        functionId = frame.functionId,
                        lexicalParentFrameId = frame.lexicalParentFrameId,
                        variableRefs = frame.localVariables.mapValues { (_, value) -> valueTable.reference(value) },
                    )
                },
                nextCallFrameId = task.nextCallFrameId,
                slots = task.slots.mapValues { (_, value) -> serializeSlot(value) },
                status = serializeStatus(task.status),
            )
        }
        return KatariStateSnapshot(
            programVersion = state.programVersion,
            tasks = tasks,
            values = valueTable.values.mapValues { (_, value) -> serializeValue(value) },
        )
    }

    suspend fun restore(
        snapshot: KatariStateSnapshot,
        context: ValueRestoreContext = EmptyValueRestoreContext,
    ): KatariState {
        StateSnapshotValidator.validate(snapshot)
        val sharedValues = mutableMapOf<Int, KatariValue>()
        snapshot.values.forEach { (id, value) ->
            sharedValues[id] = restoreValue(value, context)
        }
        return KatariState(
            programVersion = snapshot.programVersion,
            tasks = snapshot.tasks.map { task ->
                fun restoreVariables(refs: Map<String, ValueReferenceSnapshot>): Map<String, KatariValue> {
                    return refs.mapValues { (_, ref) ->
                        sharedValues.getValue(ref.valueId)
                    }
                }
                val restoredTaskLocals = restoreVariables(task.variableRefs)
                val restoredFrames = task.callFrames.map { frame ->
                    CallFrameState(
                        id = frame.id,
                        functionId = frame.functionId,
                        lexicalParentFrameId = frame.lexicalParentFrameId,
                        localVariables = restoreVariables(frame.variableRefs),
                    )
                }
                TaskState(
                    id = task.id,
                    instructionPointer = task.instructionPointer,
                    localVariables = restoredFrames.lastOrNull()?.localVariables
                        ?: restoredTaskLocals,
                    callFrames = restoredFrames,
                    nextCallFrameId = task.nextCallFrameId,
                    slots = task.slots.mapValues { (_, value) -> restoreSlot(value) },
                    status = restoreStatus(task.status),
                )
            },
        )
    }

    fun serializersModule(): SerializersModule {
        return valueCodecs.serializersModule() + SerializersModule {
            polymorphic(ValueSnapshot::class) {
                subclass(ChoiceOptionValueSnapshot::class, ChoiceOptionValueSnapshot.serializer())
            }
        }
    }

    private fun serializeValue(value: KatariValue): ValueSnapshot {
        return when (value) {
            KatariValue.Null -> NullValueSnapshot
            is KatariValue.Bool -> BoolValueSnapshot(value.value)
            is KatariValue.Int32 -> Int32ValueSnapshot(value.value)
            is KatariValue.Float64 -> Float64ValueSnapshot(value.value)
            is KatariValue.Text -> TextValueSnapshot(value.value)
            is KatariValue.Lambda -> LambdaValueSnapshot(value.id)
            is KatariValue.HostObject -> {
                if (value.typeId == CHOICE_OPTION_TYPE_ID) {
                    val option = value.value as? ChoiceOptionValue
                        ?: throw IllegalArgumentException(
                            "Internal choice option value is corrupted: expected ChoiceOptionValue but got `${value.value::class.simpleName}`"
                        )
                    ChoiceOptionValueSnapshot(
                        id = option.id,
                        text = option.text,
                        visible = option.visible,
                        enabled = option.enabled,
                        disabledText = option.disabledText,
                    )
                } else if (value.value is RuntimeValue) {
                    serializeRuntimeValue(value.value)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (valueCodecs.codec(value.typeId) as ValueCodec<ValueSnapshot>)
                        .serialize(value.value)
                }
            }
        }
    }

    private suspend fun restoreValue(
        snapshot: ValueSnapshot,
        context: ValueRestoreContext,
    ): KatariValue {
        return when (snapshot) {
            NullValueSnapshot -> KatariValue.Null
            is BoolValueSnapshot -> KatariValue.Bool(snapshot.value)
            is Int32ValueSnapshot -> KatariValue.Int32(snapshot.value)
            is Float64ValueSnapshot -> KatariValue.Float64(snapshot.value)
            is TextValueSnapshot -> KatariValue.Text(snapshot.value)
            is LambdaValueSnapshot -> KatariValue.Lambda(snapshot.id)
            is ChoiceOptionValueSnapshot -> KatariValue.HostObject(
                typeId = CHOICE_OPTION_TYPE_ID,
                value = ChoiceOptionValue(
                    id = snapshot.id,
                    text = snapshot.text,
                    visible = snapshot.visible,
                    enabled = snapshot.enabled,
                    disabledText = snapshot.disabledText,
                )
            )
            is RuntimeListValueSnapshot -> KatariValue.HostObject(
                typeId = snapshot.typeId,
                value = restoreRuntimeValue(snapshot),
            )
            is RuntimeMapValueSnapshot -> KatariValue.HostObject(
                typeId = snapshot.typeId,
                value = restoreRuntimeValue(snapshot),
            )
            is RuntimePairValueSnapshot -> KatariValue.HostObject(
                typeId = "Pair",
                value = restoreRuntimeValue(snapshot),
            )
            is RuntimeIteratorValueSnapshot -> KatariValue.HostObject(
                typeId = "Iterator",
                value = restoreRuntimeValue(snapshot),
            )
            is RuntimeMapEntryValueSnapshot -> KatariValue.HostObject(
                typeId = "MapEntry",
                value = restoreRuntimeValue(snapshot),
            )
            else -> {
                val codec = valueCodecs.codec(snapshot)
                KatariValue.HostObject(
                    typeId = codec.typeId,
                    value = codec.deserialize(snapshot, context),
                )
            }
        }
    }

    private fun serializeSlot(slot: SlotValue): SlotSnapshot {
        return when (slot) {
            is SlotValue.VariableReference -> SlotSnapshot.VariableReference(
                name = slot.name,
                frameId = slot.frameId,
            )
        }
    }

    private fun restoreSlot(slot: SlotSnapshot): SlotValue {
        return when (slot) {
            is SlotSnapshot.VariableReference -> SlotValue.VariableReference(
                name = slot.name,
                frameId = slot.frameId,
            )
        }
    }

    private fun serializeStatus(status: TaskStatus): TaskStatusSnapshot {
        return when (status) {
            TaskStatus.Ready -> TaskStatusSnapshot.Ready
            is TaskStatus.SuspendedCall -> TaskStatusSnapshot.SuspendedCall(
                resultTarget = serializeResultTarget(status.resultTarget),
                nextInstructionPointer = status.nextInstructionPointer,
            )
            is TaskStatus.Failed -> TaskStatusSnapshot.Failed(
                message = status.message,
            )
            TaskStatus.Completed -> TaskStatusSnapshot.Completed
        }
    }

    private fun restoreStatus(status: TaskStatusSnapshot): TaskStatus {
        return when (status) {
            TaskStatusSnapshot.Ready -> TaskStatus.Ready
            is TaskStatusSnapshot.SuspendedCall -> TaskStatus.SuspendedCall(
                resultTarget = restoreResultTarget(status.resultTarget),
                nextInstructionPointer = status.nextInstructionPointer,
            )
            is TaskStatusSnapshot.Failed -> TaskStatus.Failed(
                message = status.message,
            )
            TaskStatusSnapshot.Completed -> TaskStatus.Completed
        }
    }

    private fun serializeResultTarget(target: ResultTarget?): ResultTargetSnapshot? {
        return when (target) {
            null -> null
            is ResultTarget.Variable -> ResultTargetSnapshot.Variable(target.name)
            is ResultTarget.Slot -> ResultTargetSnapshot.Slot(target.slot)
        }
    }

    private fun restoreResultTarget(target: ResultTargetSnapshot?): ResultTarget? {
        return when (target) {
            null -> null
            is ResultTargetSnapshot.Variable -> ResultTarget.Variable(target.name)
            is ResultTargetSnapshot.Slot -> ResultTarget.Slot(target.slot)
        }
    }

    private fun serializeRuntimeValue(value: RuntimeValue): ValueSnapshot {
        return when (value) {
            NullValue -> NullValueSnapshot
            is BooleanValue -> BoolValueSnapshot(value.value)
            is IntValue -> Int32ValueSnapshot(value.value)
            is DoubleValue -> Float64ValueSnapshot(value.value)
            is StringValue -> TextValueSnapshot(value.value)
            else -> when (value.type().name) {
                "List", "MutableList", "Set" -> {
                    val runtimeObject = value as ClassInstance
                    val holder = value as KotlinValueHolder<*>
                    RuntimeListValueSnapshot(
                        typeId = value.type().name,
                        elementType = (runtimeObject.typeArguments.singleOrNull() ?: runtimeSymbolTable().AnyType).descriptiveName,
                        elements = (holder.value as Iterable<*>).map { serializeRuntimeValue(it as RuntimeValue) },
                    )
                }
                "Map", "MutableMap" -> {
                    val runtimeObject = value as ClassInstance
                    val holder = value as KotlinValueHolder<*>
                    RuntimeMapValueSnapshot(
                        typeId = value.type().name,
                        keyType = (runtimeObject.typeArguments.getOrNull(0) ?: runtimeSymbolTable().AnyType).descriptiveName,
                        valueType = (runtimeObject.typeArguments.getOrNull(1) ?: runtimeSymbolTable().AnyType).descriptiveName,
                        entries = (holder.value as Map<*, *>).entries.map { (key, entryValue) ->
                            RuntimeMapEntrySnapshot(
                                key = serializeRuntimeValue(key as RuntimeValue),
                                value = serializeRuntimeValue(entryValue as RuntimeValue),
                            )
                        },
                    )
                }
                "Pair" -> {
                    val runtimeObject = value as ClassInstance
                    val pair = (value as KotlinValueHolder<*>).value as Pair<*, *>
                    RuntimePairValueSnapshot(
                        firstType = (runtimeObject.typeArguments.getOrNull(0) ?: runtimeSymbolTable().AnyType).descriptiveName,
                        secondType = (runtimeObject.typeArguments.getOrNull(1) ?: runtimeSymbolTable().AnyType).descriptiveName,
                        first = serializeRuntimeValue(pair.first as RuntimeValue),
                        second = serializeRuntimeValue(pair.second as RuntimeValue),
                    )
                }
                "MapEntry" -> {
                    val runtimeObject = value as ClassInstance
                    val entry = (value as KotlinValueHolder<*>).value as Map.Entry<*, *>
                    RuntimeMapEntryValueSnapshot(
                        keyType = (runtimeObject.typeArguments.getOrNull(0) ?: runtimeSymbolTable().AnyType).descriptiveName,
                        valueType = (runtimeObject.typeArguments.getOrNull(1) ?: runtimeSymbolTable().AnyType).descriptiveName,
                        key = serializeRuntimeValue(entry.key as RuntimeValue),
                        value = serializeRuntimeValue(entry.value as RuntimeValue),
                    )
                }
                "Iterator" -> {
                    val runtimeObject = value as ClassInstance
                    val iterator = (value as KotlinValueHolder<*>).value as? RuntimeValueSnapshotIterator
                        ?: throw IllegalArgumentException("Runtime iterator `${value.type().descriptiveName}` is not snapshot-safe")
                    RuntimeIteratorValueSnapshot(
                        elementType = (runtimeObject.typeArguments.singleOrNull() ?: runtimeSymbolTable().AnyType).descriptiveName,
                        elements = iterator.remainingElements().map { serializeRuntimeValue(it) },
                    )
                }
                else -> throw IllegalArgumentException("No snapshot codec is registered for runtime value type `${value.type().descriptiveName}`")
            }
        }
    }

    private fun restoreRuntimeValue(snapshot: ValueSnapshot): RuntimeValue {
        val symbolTable = runtimeSymbolTable()
        return when (snapshot) {
            NullValueSnapshot -> NullValue
            is BoolValueSnapshot -> BooleanValue(snapshot.value, symbolTable)
            is Int32ValueSnapshot -> IntValue(snapshot.value, symbolTable)
            is Float64ValueSnapshot -> DoubleValue(snapshot.value, symbolTable)
            is TextValueSnapshot -> StringValue(snapshot.value, symbolTable)
            is RuntimeListValueSnapshot -> {
                val elementType = runtimeDataType(snapshot.elementType)
                val elements = snapshot.elements.map { restoreRuntimeValue(it) }
                when (snapshot.typeId) {
                    "List" -> ListValue(elements, elementType, symbolTable)
                    "MutableList" -> DelegatedValue(
                        value = elements.toMutableList(),
                        fullClassName = "MutableList",
                        typeArguments = listOf(elementType),
                        symbolTable = symbolTable,
                    )
                    "Set" -> DelegatedValue(
                        value = elements.toSet(),
                        fullClassName = "Set",
                        typeArguments = listOf(elementType),
                        symbolTable = symbolTable,
                    )
                    else -> throw IllegalArgumentException("Unsupported runtime list snapshot type `${snapshot.typeId}`")
                }
            }
            is RuntimeMapValueSnapshot -> {
                val keyType = runtimeDataType(snapshot.keyType)
                val valueType = runtimeDataType(snapshot.valueType)
                val entries = snapshot.entries.associate { entry ->
                    restoreRuntimeValue(entry.key) to restoreRuntimeValue(entry.value)
                }
                when (snapshot.typeId) {
                    "Map" -> DelegatedValue(
                        value = entries,
                        fullClassName = "Map",
                        typeArguments = listOf(keyType, valueType),
                        symbolTable = symbolTable,
                    )
                    "MutableMap" -> DelegatedValue(
                        value = entries.toMutableMap(),
                        fullClassName = "MutableMap",
                        typeArguments = listOf(keyType, valueType),
                        symbolTable = symbolTable,
                    )
                    else -> throw IllegalArgumentException("Unsupported runtime map snapshot type `${snapshot.typeId}`")
                }
            }
            is RuntimePairValueSnapshot -> PairValue(
                value = restoreRuntimeValue(snapshot.first) to restoreRuntimeValue(snapshot.second),
                typeA = runtimeDataType(snapshot.firstType),
                typeB = runtimeDataType(snapshot.secondType),
                symbolTable = symbolTable,
            )
            is RuntimeIteratorValueSnapshot -> IteratorValue(
                value = RuntimeValueSnapshotIterator(
                    snapshot.elements.map { restoreRuntimeValue(it) }
                ),
                typeArgument = runtimeDataType(snapshot.elementType),
                symbolTable = symbolTable,
            )
            is RuntimeMapEntryValueSnapshot -> DelegatedValue(
                value = RuntimeMapEntry(
                    key = restoreRuntimeValue(snapshot.key),
                    value = restoreRuntimeValue(snapshot.value),
                ),
                fullClassName = "MapEntry",
                typeArguments = listOf(
                    runtimeDataType(snapshot.keyType),
                    runtimeDataType(snapshot.valueType),
                ),
                symbolTable = symbolTable,
            )
            else -> throw IllegalArgumentException("Unsupported runtime snapshot `${snapshot::class.simpleName}`")
        }
    }

    private fun runtimeDataType(type: String): DataType {
        return runtimeSymbolTable().assertToDataType(type.toTypeNode("<NarrativeSnapshot>"))
    }

    private fun runtimeSymbolTable(): SymbolTable = runtimeInterpreter.symbolTable()
}

private class NarrativeSnapshotValueTable {
    private val entries = mutableListOf<KatariValue>()

    val values: Map<Int, KatariValue>
        get() = entries.withIndex().associate { it.index to it.value }

    fun reference(value: KatariValue): ValueReferenceSnapshot {
        val index = entries.indexOfFirst { existing -> existing.hasSameSnapshotIdentityAs(value) }
            .takeIf { it >= 0 }
            ?: entries.size.also { entries += value }
        return ValueReferenceSnapshot(index)
    }

    private fun KatariValue.hasSameSnapshotIdentityAs(other: KatariValue): Boolean {
        return this === other || (
            this is KatariValue.HostObject &&
                other is KatariValue.HostObject &&
                typeId == other.typeId &&
                value === other.value
            )
    }
}
