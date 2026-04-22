package com.sunnychung.lib.multiplatform.kotlite.katari

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
)

sealed interface KatariInstruction {
    val position: SourcePosition?
}

data class CallFunctionInstruction(
    val functionId: String,
    val arguments: List<KatariExpression>,
    val resultTarget: ResultTarget? = null,
    override val position: SourcePosition? = null,
) : KatariInstruction

data class SetVariableInstruction(
    val name: String,
    val expression: KatariExpression,
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
    val value: KatariValue,
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
    data class Variable(val name: String) : ResultTarget
    data class Slot(val slot: Int) : ResultTarget
}

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

sealed interface KatariValue {
    data object Null : KatariValue
    data class Bool(val value: Boolean) : KatariValue
    data class Int32(val value: Int) : KatariValue
    data class Float64(val value: Double) : KatariValue
    data class Text(val value: String) : KatariValue
    data class Lambda(val id: String) : KatariValue
    data class HostObject(val typeId: String, val value: Any) : KatariValue
}

data class KatariState(
    val programVersion: Int,
    val tasks: List<TaskState>,
    val globals: Map<String, KatariValue> = emptyMap(),
)

data class TaskState(
    val id: String,
    val instructionPointer: Int = 0,
    val localVariables: Map<String, KatariValue> = emptyMap(),
    val callFrames: List<CallFrameState> = emptyList(),
    val nextCallFrameId: Int = ROOT_CALL_FRAME_ID + 1,
    val slots: Map<Int, SlotValue> = emptyMap(),
    val status: TaskStatus = TaskStatus.Ready,
)

data class CallFrameState(
    val id: Int,
    val functionId: String,
    val lexicalParentFrameId: Int?,
    val localVariables: Map<String, KatariValue> = emptyMap(),
)

sealed interface SlotValue {
    data class VariableReference(
        val name: String,
        val frameId: Int? = null,
    ) : SlotValue
}

sealed interface TaskStatus {
    data object Ready : TaskStatus
    data class SuspendedCall(
        val resultTarget: ResultTarget?,
        val nextInstructionPointer: Int,
    ) : TaskStatus
    data class Failed(
        val message: String,
    ) : TaskStatus
    data object Completed : TaskStatus
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
    data class SuspendedCall(
        val resultTarget: ResultTargetSnapshot?,
        val nextInstructionPointer: Int,
    ) : TaskStatusSnapshot

    @Serializable
    data class Failed(
        val message: String,
    ) : TaskStatusSnapshot

    @Serializable
    data object Completed : TaskStatusSnapshot
}

@Serializable
sealed interface ResultTargetSnapshot {
    @Serializable
    data class Variable(val name: String) : ResultTargetSnapshot

    @Serializable
    data class Slot(val slot: Int) : ResultTargetSnapshot
}

@Serializable
sealed interface SlotSnapshot {
    @Serializable
    data class VariableReference(val name: String, val frameId: Int? = null) : SlotSnapshot
}

interface FunctionResponse {
    data object Ack : FunctionResponse
    data class ChoiceSelection(val optionId: String) : FunctionResponse
}

interface KatariFunctionContext {
    val state: KatariState
    val task: TaskState
}

interface KatariFunctionDispatchContext : KatariFunctionContext

data class DefaultKatariFunctionContext(
    override val state: KatariState,
    override val task: TaskState,
) : KatariFunctionContext, KatariFunctionDispatchContext

sealed interface FunctionResult {
    data class Returned(
        val value: KatariValue = KatariValue.Null,
    ) : FunctionResult

    data object Suspended : FunctionResult
}

interface KatariFunctionDefinition {
    val id: String

    suspend fun startCall(arguments: List<KatariValue>, context: KatariFunctionContext): FunctionResult

    suspend fun resumeCall(
        arguments: List<KatariValue>,
        response: FunctionResponse?,
        context: KatariFunctionContext,
    ): FunctionResult

    fun dispatch(
        arguments: List<KatariValue>,
        context: KatariFunctionDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    )
}

data class KatariFunctionRegistry(
    private val functionsById: Map<String, KatariFunctionDefinition>,
) {
    constructor(functions: List<KatariFunctionDefinition>) : this(
        functions.associateBy { it.id },
    )

    fun definition(id: String): KatariFunctionDefinition {
        return functionsById[id]
            ?: throw IllegalArgumentException("No katari function is registered for id `$id`")
    }
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

data class KatariValueCodecRegistry(
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
