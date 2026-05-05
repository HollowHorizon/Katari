package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.model.DefaultArgumentMarker
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeEnumValue
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.reflect.KClass

data class KatariProgram(
    val instructions: List<KatariInstruction>,
    val entryTaskId: String = "main",
    val version: Int = 1,
    val enumDefinitions: Map<String, KatariEnumDefinition> = emptyMap(),
)

sealed interface KatariInstruction {
    val position: SourcePosition?
}

data class CallFunctionInstruction(
    val functionId: String,
    val arguments: List<KatariExpression>,
    val argumentNames: List<String?> = emptyList(),
    val resultTarget: ResultTarget? = null,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class SetVariableInstruction(
    val name: String,
    val expression: KatariExpression,
    val declaresLocal: Boolean = false,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class SetResultInstruction(
    val target: ResultTarget,
    val expression: KatariExpression,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class ConditionalJumpInstruction(
    val condition: KatariExpression,
    val falseTarget: Int,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class JumpInstruction(
    val target: Int,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class EndInstruction(
    override val position: SourcePosition? = null,
) : KatariInstruction

data class CreateAsyncTaskInstruction(
    val entryPointer: Int,
    val rootFrameId: Int,
    val resultTarget: ResultTarget,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class TaskControlInstruction(
    val task: KatariExpression,
    val operation: TaskControlOperation,
    val resultTarget: ResultTarget? = null,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class StartRaceInstruction(
    val raceId: String,
    val entries: List<RaceEntryInstruction>,
    val resultTarget: ResultTarget,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class CompleteTaskInstruction(
    val resultExpression: KatariExpression? = null,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class EnterCallFrameInstruction(
    val functionId: String,
    val lexicalParentFrameId: Int? = null,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class ExitCallFrameInstruction(
    val returnExpression: KatariExpression? = null,
    val resultTarget: ResultTarget? = null,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class RemoveVariablesInstruction(
    val names: List<String>,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class ChoiceOption(
    val id: String,
    val text: KatariExpression,
    val target: Int,
)

sealed interface KatariExpression {
    val position: SourcePosition?
}

data class LiteralExpression(
    val value: RuntimeValue,
    override val position: SourcePosition? = null,
) : KatariExpression

data class VariableExpression(
    val name: String,
    override val position: SourcePosition? = null,
) : KatariExpression

data class SlotExpression(
    val slot: Int,
    override val position: SourcePosition? = null,
) : KatariExpression

data class LambdaLiteralExpression(
    val lambdaId: String,
    override val position: SourcePosition? = null,
) : KatariExpression

data class EnumEntryExpression(
    val typeId: String,
    val entryName: String,
    override val position: SourcePosition? = null,
) : KatariExpression

data class EnumEntriesExpression(
    val typeId: String,
    override val position: SourcePosition? = null,
) : KatariExpression

data class EnumValueOfExpression(
    val typeId: String,
    val entryName: KatariExpression,
    override val position: SourcePosition? = null,
) : KatariExpression

data class EnumPropertyExpression(
    val receiver: KatariExpression,
    val propertyName: String,
    override val position: SourcePosition? = null,
) : KatariExpression

data class UnaryExpression(
    val operator: UnaryOperator,
    val operand: KatariExpression,
    override val position: SourcePosition? = null,
) : KatariExpression

data class BinaryExpression(
    val left: KatariExpression,
    val operator: BinaryOperator,
    val right: KatariExpression,
    override val position: SourcePosition? = null,
) : KatariExpression

sealed interface ResultTarget {
    data class Variable(
        val name: String,
        val declaresLocal: Boolean = false,
    ) : ResultTarget
    data class Slot(val slot: Int) : ResultTarget
}

enum class TaskControlOperation {
    Start,
    Stop,
    Pause,
    Resume,
    Join,
}

data class RaceEntryInstruction(
    val entryPointer: Int,
    val rootFrameId: Int,
)

enum class UnaryOperator {
    Plus,
    Minus,
    Not,
}

enum class BinaryOperator {
    Add,
    Subtract,
    Multiply,
    Divide,
    Remainder,
    LessThan,
    LessThanOrEquals,
    GreaterThan,
    GreaterThanOrEquals,
    Equals,
    NotEquals,
    And,
    Or,
}

data class KatariEnumDefinition(
    val typeId: String,
    val entries: List<NarrativeEnumValue>,
) {
    private val entriesByName = entries.associateBy { it.entryName }

    fun entry(name: String): NarrativeEnumValue {
        return entriesByName[name]
            ?: throw IllegalArgumentException("Enum value `$name` not found in `$typeId`")
    }
}

data class KatariState(
    val programVersion: Int,
    val tasks: List<TaskState>,
    val globals: Map<String, RuntimeValue> = emptyMap(),
)

data class TaskState(
    val id: String,
    val instructionPointer: Int = 0,
    val localVariables: Map<String, RuntimeValue> = emptyMap(),
    val callFrames: List<CallFrameState> = emptyList(),
    val nextCallFrameId: Int = ROOT_CALL_FRAME_ID + 1,
    val slots: Map<Int, SlotValue> = emptyMap(),
    val status: TaskStatus = TaskStatus.Ready,
    val result: RuntimeValue? = null,
    val raceGroupId: String? = null,
)

data class CallFrameState(
    val id: Int,
    val functionId: String,
    val lexicalParentFrameId: Int?,
    val localVariables: Map<String, RuntimeValue> = emptyMap(),
)

sealed interface SlotValue {
    data class VariableReference(
        val name: String,
        val frameId: Int? = null,
    ) : SlotValue
}

sealed interface TaskStatus {
    data object Ready : TaskStatus
    data class Paused(
        val innerStatus: TaskStatus = Ready,
    ) : TaskStatus
    data class SuspendedCall(
        val resultTarget: ResultTarget?,
        val nextInstructionPointer: Int,
    ) : TaskStatus
    data class WaitingTaskJoin(
        val taskId: String,
        val resultTarget: ResultTarget?,
        val nextInstructionPointer: Int,
    ) : TaskStatus
    data class WaitingRace(
        val raceId: String,
        val resultTarget: ResultTarget,
        val nextInstructionPointer: Int,
    ) : TaskStatus
    data class Failed(
        val message: String,
    ) : TaskStatus
    data object Completed : TaskStatus
    data object Stopped : TaskStatus
}

@Serializable
data class ChoiceOptionSnapshot(
    val id: String,
    val text: String,
    val target: Int,
    val enabled: Boolean = true,
)

@Serializable
data class KatariStateSnapshot(
    val programVersion: Int,
    val tasks: List<TaskSnapshot>,
    val values: Map<Int, @Polymorphic ValueSnapshot> = emptyMap(),
)

@Serializable
data class TaskSnapshot(
    val id: String,
    val instructionPointer: Int,
    val variableRefs: Map<String, ValueReferenceSnapshot> = emptyMap(),
    val callFrames: List<CallFrameSnapshot>,
    val nextCallFrameId: Int,
    val slots: Map<Int, SlotSnapshot>,
    val status: TaskStatusSnapshot,
    val resultRef: ValueReferenceSnapshot? = null,
    val raceGroupId: String? = null,
)

@Serializable
data class CallFrameSnapshot(
    val id: Int,
    val functionId: String,
    val lexicalParentFrameId: Int? = null,
    val variableRefs: Map<String, ValueReferenceSnapshot> = emptyMap(),
)

@Serializable
data class ValueReferenceSnapshot(
    val valueId: Int,
)

@Serializable
sealed interface TaskStatusSnapshot {
    @Serializable
    data object Ready : TaskStatusSnapshot

    @Serializable
    data class Paused(
        val innerStatus: TaskStatusSnapshot = Ready,
    ) : TaskStatusSnapshot

    @Serializable
    data class SuspendedCall(
        val resultTarget: ResultTargetSnapshot?,
        val nextInstructionPointer: Int,
    ) : TaskStatusSnapshot

    @Serializable
    data class WaitingTaskJoin(
        val taskId: String,
        val resultTarget: ResultTargetSnapshot?,
        val nextInstructionPointer: Int,
    ) : TaskStatusSnapshot

    @Serializable
    data class WaitingRace(
        val raceId: String,
        val resultTarget: ResultTargetSnapshot,
        val nextInstructionPointer: Int,
    ) : TaskStatusSnapshot

    @Serializable
    data class Failed(
        val message: String,
    ) : TaskStatusSnapshot

    @Serializable
    data object Completed : TaskStatusSnapshot

    @Serializable
    data object Stopped : TaskStatusSnapshot
}

@Serializable
sealed interface ResultTargetSnapshot {
    @Serializable
    data class Variable(
        val name: String,
        val declaresLocal: Boolean = false,
    ) : ResultTargetSnapshot

    @Serializable
    data class Slot(val slot: Int) : ResultTargetSnapshot
}

@Serializable
sealed interface SlotSnapshot {
    @Serializable
    data class VariableReference(val name: String, val frameId: Int? = null) : SlotSnapshot
}

@Serializable
abstract class ValueSnapshot

@Serializable
@SerialName("null")
data object NullValueSnapshot : ValueSnapshot()

@Serializable
@SerialName("bool")
data class BoolValueSnapshot(val value: Boolean) : ValueSnapshot()

@Serializable
@SerialName("int")
data class Int32ValueSnapshot(val value: Int) : ValueSnapshot()

@Serializable
@SerialName("double")
data class Float64ValueSnapshot(val value: Double) : ValueSnapshot()

@Serializable
@SerialName("text")
data class TextValueSnapshot(val value: String) : ValueSnapshot()

@Serializable
@SerialName("lambda")
data class LambdaValueSnapshot(val id: String) : ValueSnapshot()

@Serializable
@SerialName("katari_task")
data class KatariTaskValueSnapshot(
    val taskId: String,
    val entryPointer: Int,
    val rootFrameId: Int,
    val capturedVariables: Map<String, @Polymorphic ValueSnapshot>,
    val started: Boolean,
) : ValueSnapshot()

@Serializable
@SerialName("enum")
data class EnumValueSnapshot(
    val typeId: String,
    val entryName: String,
    val ordinal: Int,
    val properties: Map<String, @Polymorphic ValueSnapshot> = emptyMap(),
) : ValueSnapshot()

@Serializable
@SerialName("enum_entries")
data class EnumEntriesValueSnapshot(
    val typeId: String,
    val entries: List<EnumValueSnapshot>,
) : ValueSnapshot()

@Serializable
@SerialName("enum_entries_iterator")
data class EnumEntriesIteratorValueSnapshot(
    val entries: List<EnumValueSnapshot>,
    val index: Int,
) : ValueSnapshot()

@Serializable
@SerialName("choice_option")
data class ChoiceOptionValueSnapshot(
    val id: String,
    val text: String,
    val visible: Boolean,
    val enabled: Boolean,
    val disabledText: String? = null,
) : ValueSnapshot()

@Serializable
@SerialName("runtime_list")
data class RuntimeListValueSnapshot(
    val typeId: String,
    val elementType: String,
    val elements: List<@Polymorphic ValueSnapshot>,
) : ValueSnapshot()

@Serializable
@SerialName("runtime_map")
data class RuntimeMapValueSnapshot(
    val typeId: String,
    val keyType: String,
    val valueType: String,
    val entries: List<RuntimeMapEntrySnapshot>,
) : ValueSnapshot()

@Serializable
data class RuntimeMapEntrySnapshot(
    val key: @Polymorphic ValueSnapshot,
    val value: @Polymorphic ValueSnapshot,
)

@Serializable
@SerialName("runtime_pair")
data class RuntimePairValueSnapshot(
    val firstType: String,
    val secondType: String,
    val first: @Polymorphic ValueSnapshot,
    val second: @Polymorphic ValueSnapshot,
) : ValueSnapshot()

@Serializable
@SerialName("runtime_iterator")
data class RuntimeIteratorValueSnapshot(
    val elementType: String,
    val elements: List<@Polymorphic ValueSnapshot>,
) : ValueSnapshot()

@Serializable
@SerialName("runtime_map_entry_value")
data class RuntimeMapEntryValueSnapshot(
    val keyType: String,
    val valueType: String,
    val key: @Polymorphic ValueSnapshot,
    val value: @Polymorphic ValueSnapshot,
) : ValueSnapshot()

interface ValueCodec<S : ValueSnapshot> {
    val typeId: String
    val snapshotClass: KClass<S>
    val snapshotSerializer: KSerializer<S>

    fun serialize(value: Any): S

    suspend fun deserialize(snapshot: S, context: ValueRestoreContext): Any
}

interface ValueRestoreContext

data object EmptyValueRestoreContext : ValueRestoreContext

class KatariValueCodecRegistry(
    private val codecsByTypeId: Map<String, ValueCodec<out ValueSnapshot>>,
    private val codecsBySnapshotClass: Map<KClass<out ValueSnapshot>, ValueCodec<out ValueSnapshot>>,
) {
    constructor(codecs: List<ValueCodec<out ValueSnapshot>>) : this(
        codecs.associateBy { it.typeId },
        codecs.associateBy { it.snapshotClass },
    )

    fun codec(typeId: String): ValueCodec<out ValueSnapshot> {
        return codecsByTypeId[typeId]
            ?: throw IllegalArgumentException("No external katari value codec is registered for type `$typeId`")
    }

    @Suppress("UNCHECKED_CAST")
    fun <S : ValueSnapshot> codec(snapshot: S): ValueCodec<S> {
        return codecsBySnapshotClass[snapshot::class] as? ValueCodec<S>
            ?: throw IllegalArgumentException("No external katari value codec is registered for snapshot `${snapshot::class.simpleName}`")
    }

    fun serializersModule(): SerializersModule {
        return SerializersModule {
            polymorphic(ValueSnapshot::class) {
                subclass(NullValueSnapshot::class, NullValueSnapshot.serializer())
                subclass(BoolValueSnapshot::class, BoolValueSnapshot.serializer())
                subclass(Int32ValueSnapshot::class, Int32ValueSnapshot.serializer())
                subclass(Float64ValueSnapshot::class, Float64ValueSnapshot.serializer())
                subclass(TextValueSnapshot::class, TextValueSnapshot.serializer())
                subclass(LambdaValueSnapshot::class, LambdaValueSnapshot.serializer())
                subclass(EnumValueSnapshot::class, EnumValueSnapshot.serializer())
                subclass(EnumEntriesValueSnapshot::class, EnumEntriesValueSnapshot.serializer())
                subclass(EnumEntriesIteratorValueSnapshot::class, EnumEntriesIteratorValueSnapshot.serializer())
                subclass(RuntimeListValueSnapshot::class, RuntimeListValueSnapshot.serializer())
                subclass(RuntimeMapValueSnapshot::class, RuntimeMapValueSnapshot.serializer())
                subclass(RuntimePairValueSnapshot::class, RuntimePairValueSnapshot.serializer())
                subclass(RuntimeIteratorValueSnapshot::class, RuntimeIteratorValueSnapshot.serializer())
                subclass(RuntimeMapEntryValueSnapshot::class, RuntimeMapEntryValueSnapshot.serializer())
                codecsByTypeId.values.forEach {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        it.snapshotClass as KClass<ValueSnapshot>,
                        it.snapshotSerializer as KSerializer<ValueSnapshot>,
                    )
                }
            }
        }
    }
}

internal const val ROOT_CALL_FRAME_ID: Int = 0
internal const val ROOT_CALL_FRAME_FUNCTION_ID: String = "__main__"
internal const val KATARI_ENUM_ENTRIES_TYPE_ID: String = "__katari_enum_entries"
internal const val KATARI_ENUM_ENTRIES_ITERATOR_TYPE_ID: String = "__katari_enum_entries_iterator"
