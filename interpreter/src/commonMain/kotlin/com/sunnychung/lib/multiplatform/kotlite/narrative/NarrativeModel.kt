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
    val resultSlot: Int? = null,
    override val position: SourcePosition? = null,
) : NarrativeInstruction

data class SetVariableInstruction(
    val name: String,
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

data class ChoiceOption(
    val id: String,
    val text: NarrativeExpression,
    val target: Int,
)

sealed interface NarrativeExpression

data class LiteralExpression(val value: NarrativeValue) : NarrativeExpression

data class VariableExpression(val name: String) : NarrativeExpression

data class SlotExpression(val slot: Int) : NarrativeExpression

data class BinaryExpression(
    val left: NarrativeExpression,
    val operator: NarrativeBinaryOperator,
    val right: NarrativeExpression,
) : NarrativeExpression

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
    data class Text(val value: String) : NarrativeValue
    data class Entity(val id: String) : NarrativeValue
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
    val slots: Map<Int, NarrativeValue> = emptyMap(),
    val status: NarrativeTaskStatus = NarrativeTaskStatus.Ready,
)

sealed interface NarrativeTaskStatus {
    data object Ready : NarrativeTaskStatus
    data class SuspendedCall(
        val effectId: String,
        val functionId: String,
        val resultSlot: Int?,
        val nextInstructionPointer: Int,
        val payload: NarrativeFunctionEffectPayload,
        val continuation: NarrativeFunctionStateSnapshot,
    ) : NarrativeTaskStatus
    data object Completed : NarrativeTaskStatus
}

@Serializable
data class ChoiceOptionSnapshot(
    val id: String,
    val text: String,
    val target: Int,
)

sealed interface NarrativeEffect {
    val effectId: String
    val taskId: String
}

data class FunctionEffect(
    override val effectId: String,
    override val taskId: String,
    val functionId: String,
    val payload: NarrativeFunctionEffectPayload,
) : NarrativeEffect

sealed interface NarrativeStepResult {
    data class EffectEmitted(val state: NarrativeState, val effect: NarrativeEffect) : NarrativeStepResult
    data class Advanced(val state: NarrativeState) : NarrativeStepResult
    data class Completed(val state: NarrativeState) : NarrativeStepResult
}

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
    val slots: Map<Int, @Polymorphic NarrativeValueSnapshot>,
    val status: NarrativeTaskStatusSnapshot,
)

@Serializable
sealed interface NarrativeTaskStatusSnapshot {
    @Serializable
    data object Ready : NarrativeTaskStatusSnapshot

    @Serializable
    data class SuspendedCall(
        val effectId: String,
        val functionId: String,
        val resultSlot: Int?,
        val nextInstructionPointer: Int,
        val payload: @Polymorphic NarrativeFunctionEffectPayload,
        val continuation: @Polymorphic NarrativeFunctionStateSnapshot,
    ) : NarrativeTaskStatusSnapshot

    @Serializable
    data object Completed : NarrativeTaskStatusSnapshot
}

@Serializable
abstract class NarrativeFunctionStateSnapshot

@Serializable
@SerialName("empty_function_state")
data object EmptyFunctionStateSnapshot : NarrativeFunctionStateSnapshot()

@Serializable
abstract class NarrativeFunctionEffectPayload

@Serializable
@SerialName("say_effect")
data class SayEffectPayload(
    val speaker: @Polymorphic NarrativeValueSnapshot?,
    val text: String,
) : NarrativeFunctionEffectPayload()

@Serializable
@SerialName("choice_effect")
data class ChoiceEffectPayload(
    val options: List<ChoiceOptionSnapshot>,
) : NarrativeFunctionEffectPayload()

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

sealed interface NarrativeFunctionResult<out S : NarrativeFunctionStateSnapshot, out P : NarrativeFunctionEffectPayload> {
    data class Returned<out S : NarrativeFunctionStateSnapshot, out P : NarrativeFunctionEffectPayload>(
        val value: NarrativeValue = NarrativeValue.Null,
    ) : NarrativeFunctionResult<S, P>

    data class Suspended<out S : NarrativeFunctionStateSnapshot, out P : NarrativeFunctionEffectPayload>(
        val payload: P,
        val continuation: S,
    ) : NarrativeFunctionResult<S, P>
}

interface NarrativeFunctionDefinition<S : NarrativeFunctionStateSnapshot, P : NarrativeFunctionEffectPayload> {
    val id: String
    val stateSnapshotClass: KClass<S>
    val stateSnapshotSerializer: KSerializer<S>
    val payloadClass: KClass<P>
    val payloadSerializer: KSerializer<P>

    suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult<S, P>

    suspend fun resumeCall(
        continuation: S,
        response: NarrativeFunctionResponse?,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult<S, P>

    fun dispatch(
        payload: P,
        context: NarrativeFunctionDispatchContext,
        resume: (NarrativeFunctionResponse?) -> Unit,
    )
}

data class NarrativeFunctionRegistry(
    private val functionsById: Map<String, NarrativeFunctionDefinition<out NarrativeFunctionStateSnapshot, out NarrativeFunctionEffectPayload>>,
) {
    constructor(functions: List<NarrativeFunctionDefinition<out NarrativeFunctionStateSnapshot, out NarrativeFunctionEffectPayload>>) : this(
        functions.associateBy { it.id },
    )

    fun definition(id: String): NarrativeFunctionDefinition<out NarrativeFunctionStateSnapshot, out NarrativeFunctionEffectPayload> {
        return functionsById[id]
            ?: throw IllegalArgumentException("No narrative function is registered for id `$id`")
    }

    fun serializersModule(): SerializersModule {
        val uniqueStateDefinitions = functionsById.values
            .distinctBy { it.stateSnapshotClass }
        val uniquePayloadDefinitions = functionsById.values
            .distinctBy { it.payloadClass }
        return SerializersModule {
            polymorphic(NarrativeFunctionStateSnapshot::class) {
                subclass(EmptyFunctionStateSnapshot::class, EmptyFunctionStateSnapshot.serializer())
                uniqueStateDefinitions.forEach {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        it.stateSnapshotClass as KClass<NarrativeFunctionStateSnapshot>,
                        it.stateSnapshotSerializer as KSerializer<NarrativeFunctionStateSnapshot>,
                    )
                }
            }
            polymorphic(NarrativeFunctionEffectPayload::class) {
                subclass(SayEffectPayload::class, SayEffectPayload.serializer())
                subclass(ChoiceEffectPayload::class, ChoiceEffectPayload.serializer())
                uniquePayloadDefinitions.forEach {
                    @Suppress("UNCHECKED_CAST")
                    subclass(
                        it.payloadClass as KClass<NarrativeFunctionEffectPayload>,
                        it.payloadSerializer as KSerializer<NarrativeFunctionEffectPayload>,
                    )
                }
            }
        }
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
@SerialName("text")
data class TextValueSnapshot(val value: String) : NarrativeValueSnapshot()

@Serializable
@SerialName("entity")
data class EntityValueSnapshot(val id: String) : NarrativeValueSnapshot()

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
                subclass(TextValueSnapshot::class, TextValueSnapshot.serializer())
                subclass(EntityValueSnapshot::class, EntityValueSnapshot.serializer())
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
