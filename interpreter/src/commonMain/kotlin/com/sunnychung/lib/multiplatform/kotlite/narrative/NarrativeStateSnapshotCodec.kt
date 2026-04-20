package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.ClassInstance
import com.sunnychung.lib.multiplatform.kotlite.model.DataType
import com.sunnychung.lib.multiplatform.kotlite.model.DelegatedValue
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.KotlinValueHolder
import com.sunnychung.lib.multiplatform.kotlite.model.ListValue
import com.sunnychung.lib.multiplatform.kotlite.model.NullValue
import com.sunnychung.lib.multiplatform.kotlite.model.PairValue
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.model.SymbolTable
import com.sunnychung.lib.multiplatform.kotlite.model.toTypeNode
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

class NarrativeStateSnapshotCodec(
    private val valueCodecs: NarrativeValueCodecRegistry = NarrativeValueCodecRegistry(emptyList()),
    private val executionEnvironment: ExecutionEnvironment = ExecutionEnvironment(),
) {
    private val runtimeInterpreter by lazy {
        KotliteInterpreter(
            filename = "<NarrativeSnapshot>",
            code = "",
            executionEnvironment = executionEnvironment,
        )
    }

    fun serialize(state: NarrativeState): NarrativeStateSnapshot {
        return NarrativeStateSnapshot(
            programVersion = state.programVersion,
            globals = state.globals.mapValues { (_, value) -> serializeValue(value) },
            tasks = state.tasks.map { task ->
                NarrativeTaskSnapshot(
                    id = task.id,
                    instructionPointer = task.instructionPointer,
                    localVariables = if (task.callFrames.isEmpty()) {
                        task.localVariables.mapValues { (_, value) -> serializeValue(value) }
                    } else {
                        emptyMap()
                    },
                    callFrames = task.callFrames.map { frame ->
                        NarrativeCallFrameSnapshot(
                            id = frame.id,
                            functionId = frame.functionId,
                            lexicalParentFrameId = frame.lexicalParentFrameId,
                            localVariables = frame.localVariables.mapValues { (_, value) -> serializeValue(value) },
                        )
                    },
                    nextCallFrameId = task.nextCallFrameId,
                    slots = task.slots.mapValues { (_, value) -> serializeSlot(value) },
                    status = serializeStatus(task.status),
                )
            },
        )
    }

    suspend fun restore(
        snapshot: NarrativeStateSnapshot,
        context: NarrativeValueRestoreContext = EmptyNarrativeValueRestoreContext,
    ): NarrativeState {
        return NarrativeState(
            programVersion = snapshot.programVersion,
            globals = snapshot.globals.mapValues { (_, value) -> restoreValue(value, context) },
            tasks = snapshot.tasks.map { task ->
                val restoredLegacyLocals = task.localVariables.mapValues { (_, value) -> restoreValue(value, context) }
                val restoredFrames = task.callFrames.map { frame ->
                    NarrativeCallFrameState(
                        id = frame.id,
                        functionId = frame.functionId,
                        lexicalParentFrameId = frame.lexicalParentFrameId,
                        localVariables = frame.localVariables.mapValues { (_, value) -> restoreValue(value, context) },
                    )
                }
                NarrativeTaskState(
                    id = task.id,
                    instructionPointer = task.instructionPointer,
                    localVariables = restoredFrames.lastOrNull()?.localVariables ?: restoredLegacyLocals,
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
            polymorphic(NarrativeValueSnapshot::class) {
                subclass(ChoiceOptionValueSnapshot::class, ChoiceOptionValueSnapshot.serializer())
            }
        }
    }

    private fun serializeValue(value: NarrativeValue): NarrativeValueSnapshot {
        return when (value) {
            NarrativeValue.Null -> NullValueSnapshot
            is NarrativeValue.Bool -> BoolValueSnapshot(value.value)
            is NarrativeValue.Int32 -> Int32ValueSnapshot(value.value)
            is NarrativeValue.Float64 -> Float64ValueSnapshot(value.value)
            is NarrativeValue.Text -> TextValueSnapshot(value.value)
            is NarrativeValue.Lambda -> LambdaValueSnapshot(value.id)
            is NarrativeValue.HostObject -> {
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
                    (valueCodecs.codec(value.typeId) as NarrativeValueCodec<NarrativeValueSnapshot>)
                        .serialize(value.value)
                }
            }
        }
    }

    private suspend fun restoreValue(
        snapshot: NarrativeValueSnapshot,
        context: NarrativeValueRestoreContext,
    ): NarrativeValue {
        return when (snapshot) {
            NullValueSnapshot -> NarrativeValue.Null
            is BoolValueSnapshot -> NarrativeValue.Bool(snapshot.value)
            is Int32ValueSnapshot -> NarrativeValue.Int32(snapshot.value)
            is Float64ValueSnapshot -> NarrativeValue.Float64(snapshot.value)
            is TextValueSnapshot -> NarrativeValue.Text(snapshot.value)
            is LambdaValueSnapshot -> NarrativeValue.Lambda(snapshot.id)
            is ChoiceOptionValueSnapshot -> NarrativeValue.HostObject(
                typeId = CHOICE_OPTION_TYPE_ID,
                value = ChoiceOptionValue(
                    id = snapshot.id,
                    text = snapshot.text,
                    visible = snapshot.visible,
                    enabled = snapshot.enabled,
                    disabledText = snapshot.disabledText,
                )
            )
            is RuntimeListValueSnapshot -> NarrativeValue.HostObject(
                typeId = snapshot.typeId,
                value = restoreRuntimeValue(snapshot),
            )
            is RuntimeMapValueSnapshot -> NarrativeValue.HostObject(
                typeId = snapshot.typeId,
                value = restoreRuntimeValue(snapshot),
            )
            is RuntimePairValueSnapshot -> NarrativeValue.HostObject(
                typeId = "Pair",
                value = restoreRuntimeValue(snapshot),
            )
            else -> {
                val codec = valueCodecs.codec(snapshot)
                NarrativeValue.HostObject(
                    typeId = codec.typeId,
                    value = codec.deserialize(snapshot, context),
                )
            }
        }
    }

    private fun serializeSlot(slot: NarrativeSlotValue): NarrativeSlotSnapshot {
        return when (slot) {
            is NarrativeSlotValue.VariableReference -> NarrativeSlotSnapshot.VariableReference(
                name = slot.name,
                frameId = slot.frameId,
            )
        }
    }

    private fun restoreSlot(slot: NarrativeSlotSnapshot): NarrativeSlotValue {
        return when (slot) {
            is NarrativeSlotSnapshot.VariableReference -> NarrativeSlotValue.VariableReference(
                name = slot.name,
                frameId = slot.frameId,
            )
        }
    }

    private fun serializeStatus(status: NarrativeTaskStatus): NarrativeTaskStatusSnapshot {
        return when (status) {
            NarrativeTaskStatus.Ready -> NarrativeTaskStatusSnapshot.Ready
            is NarrativeTaskStatus.SuspendedCall -> NarrativeTaskStatusSnapshot.SuspendedCall(
                resultTarget = serializeResultTarget(status.resultTarget),
                nextInstructionPointer = status.nextInstructionPointer,
            )
            is NarrativeTaskStatus.Failed -> NarrativeTaskStatusSnapshot.Failed(
                message = status.message,
            )
            NarrativeTaskStatus.Completed -> NarrativeTaskStatusSnapshot.Completed
        }
    }

    private fun restoreStatus(status: NarrativeTaskStatusSnapshot): NarrativeTaskStatus {
        return when (status) {
            NarrativeTaskStatusSnapshot.Ready -> NarrativeTaskStatus.Ready
            is NarrativeTaskStatusSnapshot.SuspendedCall -> NarrativeTaskStatus.SuspendedCall(
                resultTarget = restoreResultTarget(status.resultTarget),
                nextInstructionPointer = status.nextInstructionPointer,
            )
            is NarrativeTaskStatusSnapshot.Failed -> NarrativeTaskStatus.Failed(
                message = status.message,
            )
            NarrativeTaskStatusSnapshot.Completed -> NarrativeTaskStatus.Completed
        }
    }

    private fun serializeResultTarget(target: NarrativeResultTarget?): NarrativeResultTargetSnapshot? {
        return when (target) {
            null -> null
            is NarrativeResultTarget.Variable -> NarrativeResultTargetSnapshot.Variable(target.name)
            is NarrativeResultTarget.Slot -> NarrativeResultTargetSnapshot.Slot(target.slot)
        }
    }

    private fun restoreResultTarget(target: NarrativeResultTargetSnapshot?): NarrativeResultTarget? {
        return when (target) {
            null -> null
            is NarrativeResultTargetSnapshot.Variable -> NarrativeResultTarget.Variable(target.name)
            is NarrativeResultTargetSnapshot.Slot -> NarrativeResultTarget.Slot(target.slot)
        }
    }

    private fun serializeRuntimeValue(value: RuntimeValue): NarrativeValueSnapshot {
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
                else -> throw IllegalArgumentException("No snapshot codec is registered for runtime value type `${value.type().descriptiveName}`")
            }
        }
    }

    private fun restoreRuntimeValue(snapshot: NarrativeValueSnapshot): RuntimeValue {
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
            else -> throw IllegalArgumentException("Unsupported runtime snapshot `${snapshot::class.simpleName}`")
        }
    }

    private fun runtimeDataType(type: String): DataType {
        return runtimeSymbolTable().assertToDataType(type.toTypeNode("<NarrativeSnapshot>"))
    }

    private fun runtimeSymbolTable(): SymbolTable = runtimeInterpreter.symbolTable()
}
