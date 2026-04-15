package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.reflect.KClass

data class NarrativeProgram(
    val instructions: List<NarrativeInstruction>,
    val entryTaskId: String = "main",
    val version: Int = 1,
)

sealed interface NarrativeInstruction {
    val position: SourcePosition?
}

data class CallFunctionInstruction(
    val functionId: String,
    val arguments: List<NarrativeExpression>,
    val resultTarget: NarrativeResultTarget? = null,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class SetVariableInstruction(
    val name: String,
    val expression: NarrativeExpression,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class SetResultInstruction(
    val target: NarrativeResultTarget,
    val expression: NarrativeExpression,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class ConditionalJumpInstruction(
    val condition: NarrativeExpression,
    val falseTarget: Int,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class JumpInstruction(
    val target: Int,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class EndInstruction(
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class EnterCallFrameInstruction(
    val functionId: String,
    val lexicalParentFrameId: Int? = null,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class ExitCallFrameInstruction(
    val returnExpression: NarrativeExpression? = null,
    val resultTarget: NarrativeResultTarget? = null,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class RemoveVariablesInstruction(
    val names: List<String>,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class ChoiceOption(
    val id: String,
    val text: NarrativeExpression,
    val target: Int,
)

sealed interface NarrativeExpression {
    val position: SourcePosition?
}

data class LiteralExpression(
    val value: NarrativeValue,
    override val position: SourcePosition? = null,
) : NarrativeExpression

data class VariableExpression(
    val name: String,
    override val position: SourcePosition? = null,
) : NarrativeExpression

data class SlotExpression(
    val slot: Int,
    override val position: SourcePosition? = null,
) : NarrativeExpression

data class LambdaLiteralExpression(
    val lambdaId: String,
    override val position: SourcePosition? = null,
) : NarrativeExpression

data class UnaryExpression(
    val operator: NarrativeUnaryOperator,
    val operand: NarrativeExpression,
    override val position: SourcePosition? = null,
) : NarrativeExpression

data class BinaryExpression(
    val left: NarrativeExpression,
    val operator: NarrativeBinaryOperator,
    val right: NarrativeExpression,
    override val position: SourcePosition? = null,
) : NarrativeExpression

sealed interface NarrativeResultTarget {
    data class Variable(val name: String) : NarrativeResultTarget
    data class Slot(val slot: Int) : NarrativeResultTarget
}

enum class NarrativeUnaryOperator {
    Plus,
    Minus,
    Not,
}

enum class NarrativeBinaryOperator {
    Add,
    Subtract,
    LessThan,
    LessThanOrEquals,
    GreaterThan,
    GreaterThanOrEquals,
    Equals,
    NotEquals,
    And,
    Or,
}

sealed interface NarrativeValue {
    data object Null : NarrativeValue
    data class Bool(val value: Boolean) : NarrativeValue
    data class Int32(val value: Int) : NarrativeValue
    data class Float64(val value: Double) : NarrativeValue
    data class Text(val value: String) : NarrativeValue
    data class Lambda(val id: String) : NarrativeValue
    data class HostObject(val typeId: String, val value: Any) : NarrativeValue
}

data class NarrativeState(
    val programVersion: Int,
    val tasks: List<NarrativeTaskState>,
    val globals: Map<String, NarrativeValue> = emptyMap(),
)

data class NarrativeTaskState(
    val id: String,
    val instructionPointer: Int = 0,
    val localVariables: Map<String, NarrativeValue> = emptyMap(),
    val callFrames: List<NarrativeCallFrameState> = emptyList(),
    val nextCallFrameId: Int = ROOT_CALL_FRAME_ID + 1,
    val slots: Map<Int, NarrativeSlotValue> = emptyMap(),
    val status: NarrativeTaskStatus = NarrativeTaskStatus.Ready,
)

data class NarrativeCallFrameState(
    val id: Int,
    val functionId: String,
    val lexicalParentFrameId: Int?,
    val localVariables: Map<String, NarrativeValue> = emptyMap(),
)

sealed interface NarrativeSlotValue {
    data class VariableReference(
        val name: String,
        val frameId: Int? = null,
    ) : NarrativeSlotValue
}

sealed interface NarrativeTaskStatus {
    data object Ready : NarrativeTaskStatus
    data class SuspendedCall(
        val resultTarget: NarrativeResultTarget?,
        val nextInstructionPointer: Int,
    ) : NarrativeTaskStatus
    data class Failed(
        val message: String,
    ) : NarrativeTaskStatus
    data object Completed : NarrativeTaskStatus
}

@Serializable
data class ChoiceOptionSnapshot(
    val id: String,
    val text: String,
    val target: Int,
    val enabled: Boolean = true,
)

@Serializable
data class NarrativeStateSnapshot(
    val programVersion: Int,
    val tasks: List<NarrativeTaskSnapshot>,
    val globals: Map<String, @Polymorphic NarrativeValueSnapshot> = emptyMap(),
)

@Serializable
data class NarrativeTaskSnapshot(
    val id: String,
    val instructionPointer: Int,
    val localVariables: Map<String, @Polymorphic NarrativeValueSnapshot>,
    val callFrames: List<NarrativeCallFrameSnapshot>,
    val nextCallFrameId: Int,
    val slots: Map<Int, NarrativeSlotSnapshot>,
    val status: NarrativeTaskStatusSnapshot,
)

@Serializable
data class NarrativeCallFrameSnapshot(
    val id: Int,
    val functionId: String,
    val lexicalParentFrameId: Int? = null,
    val localVariables: Map<String, @Polymorphic NarrativeValueSnapshot> = emptyMap(),
)

@Serializable
sealed interface NarrativeTaskStatusSnapshot {
    @Serializable
    data object Ready : NarrativeTaskStatusSnapshot

    @Serializable
    data class SuspendedCall(
        val resultTarget: NarrativeResultTargetSnapshot?,
        val nextInstructionPointer: Int,
    ) : NarrativeTaskStatusSnapshot

    @Serializable
    data class Failed(
        val message: String,
    ) : NarrativeTaskStatusSnapshot

    @Serializable
    data object Completed : NarrativeTaskStatusSnapshot
}

@Serializable
sealed interface NarrativeResultTargetSnapshot {
    @Serializable
    data class Variable(val name: String) : NarrativeResultTargetSnapshot

    @Serializable
    data class Slot(val slot: Int) : NarrativeResultTargetSnapshot
}

@Serializable
sealed interface NarrativeSlotSnapshot {
    @Serializable
    data class VariableReference(val name: String, val frameId: Int? = null) : NarrativeSlotSnapshot
}

interface NarrativeFunctionResponse {
    data object Ack : NarrativeFunctionResponse
    data class ChoiceSelection(val optionId: String) : NarrativeFunctionResponse
}

interface NarrativeFunctionContext {
    val state: NarrativeState
    val task: NarrativeTaskState
}

interface NarrativeFunctionDispatchContext : NarrativeFunctionContext

data class DefaultNarrativeFunctionContext(
    override val state: NarrativeState,
    override val task: NarrativeTaskState,
) : NarrativeFunctionContext, NarrativeFunctionDispatchContext

sealed interface NarrativeFunctionResult {
    data class Returned(
        val value: NarrativeValue = NarrativeValue.Null,
    ) : NarrativeFunctionResult

    data object Suspended : NarrativeFunctionResult
}

interface NarrativeFunctionDefinition {
    val id: String

    suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult

    suspend fun resumeCall(
        arguments: List<NarrativeValue>,
        response: NarrativeFunctionResponse?,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult

    fun dispatch(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionDispatchContext,
        resume: (NarrativeFunctionResponse?) -> Unit,
    )
}

data class NarrativeFunctionRegistry(
    private val functionsById: Map<String, NarrativeFunctionDefinition>,
) {
    constructor(functions: List<NarrativeFunctionDefinition>) : this(
        functions.associateBy { it.id },
    )

    fun definition(id: String): NarrativeFunctionDefinition {
        return functionsById[id]
            ?: throw IllegalArgumentException("No narrative function is registered for id `$id`")
    }
}

@Serializable
abstract class NarrativeValueSnapshot

@Serializable
@SerialName("null")
data object NullValueSnapshot : NarrativeValueSnapshot()

@Serializable
@SerialName("bool")
data class BoolValueSnapshot(val value: Boolean) : NarrativeValueSnapshot()

@Serializable
@SerialName("int")
data class Int32ValueSnapshot(val value: Int) : NarrativeValueSnapshot()

@Serializable
@SerialName("double")
data class Float64ValueSnapshot(val value: Double) : NarrativeValueSnapshot()

@Serializable
@SerialName("text")
data class TextValueSnapshot(val value: String) : NarrativeValueSnapshot()

@Serializable
@SerialName("lambda")
data class LambdaValueSnapshot(val id: String) : NarrativeValueSnapshot()

@Serializable
@SerialName("choice_option")
data class ChoiceOptionValueSnapshot(
    val id: String,
    val text: String,
    val visible: Boolean,
    val enabled: Boolean,
    val disabledText: String? = null,
) : NarrativeValueSnapshot()

interface NarrativeValueCodec<S : NarrativeValueSnapshot> {
    val typeId: String
    val snapshotClass: KClass<S>
    val snapshotSerializer: KSerializer<S>

    fun serialize(value: Any): S

    suspend fun deserialize(snapshot: S, context: NarrativeValueRestoreContext): Any
}

interface NarrativeValueRestoreContext

data object EmptyNarrativeValueRestoreContext : NarrativeValueRestoreContext

data class NarrativeValueCodecRegistry(
    private val codecsByTypeId: Map<String, NarrativeValueCodec<out NarrativeValueSnapshot>>,
    private val codecsBySnapshotClass: Map<KClass<out NarrativeValueSnapshot>, NarrativeValueCodec<out NarrativeValueSnapshot>>,
) {
    constructor(codecs: List<NarrativeValueCodec<out NarrativeValueSnapshot>>) : this(
        codecs.associateBy { it.typeId },
        codecs.associateBy { it.snapshotClass },
    )

    fun codec(typeId: String): NarrativeValueCodec<out NarrativeValueSnapshot> {
        return codecsByTypeId[typeId]
            ?: throw IllegalArgumentException("No external narrative value codec is registered for type `$typeId`")
    }

    @Suppress("UNCHECKED_CAST")
    fun <S : NarrativeValueSnapshot> codec(snapshot: S): NarrativeValueCodec<S> {
        return codecsBySnapshotClass[snapshot::class] as? NarrativeValueCodec<S>
            ?: throw IllegalArgumentException("No external narrative value codec is registered for snapshot `${snapshot::class.simpleName}`")
    }

    fun serializersModule(): SerializersModule {
        return SerializersModule {
            polymorphic(NarrativeValueSnapshot::class) {
                subclass(NullValueSnapshot::class, NullValueSnapshot.serializer())
                subclass(BoolValueSnapshot::class, BoolValueSnapshot.serializer())
                subclass(Int32ValueSnapshot::class, Int32ValueSnapshot.serializer())
                subclass(Float64ValueSnapshot::class, Float64ValueSnapshot.serializer())
                subclass(TextValueSnapshot::class, TextValueSnapshot.serializer())
                subclass(LambdaValueSnapshot::class, LambdaValueSnapshot.serializer())
                codecsByTypeId.values.forEach {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        it.snapshotClass as KClass<NarrativeValueSnapshot>,
                        it.snapshotSerializer as KSerializer<NarrativeValueSnapshot>,
                    )
                }
            }
        }
    }
}

internal const val ROOT_CALL_FRAME_ID: Int = 0
internal const val ROOT_CALL_FRAME_FUNCTION_ID: String = "__main__"
