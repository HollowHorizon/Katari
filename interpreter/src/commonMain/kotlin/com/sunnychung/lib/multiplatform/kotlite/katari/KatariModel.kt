package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.model.DataType
import com.sunnychung.lib.multiplatform.kotlite.model.ObjectType
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
    data class Variable(
        val name: String,
        val declaresLocal: Boolean = false,
    ) : ResultTarget
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
    val signature: KatariCallableSignature

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
    private val functionsById: Map<String, List<KatariFunctionDefinition>>,
    private val typeRegistry: KatariTypeRegistry = KatariTypeRegistry.Empty,
) {
    constructor(functions: List<KatariFunctionDefinition>, typeRegistry: KatariTypeRegistry = KatariTypeRegistry.Empty) : this(
        functions.groupBy { it.id },
        typeRegistry,
    )

    fun definition(id: String): KatariFunctionDefinition {
        val candidates = functionsById[id]
            ?: throw IllegalArgumentException("No narrative function is registered for id `$id`")
        require(candidates.size == 1) {
            "Katari function lookup `$id` requires arguments because ${candidates.size} overloads are registered"
        }
        return candidates.single()
    }

    fun definition(id: String, arguments: List<KatariValue>): KatariFunctionDefinition {
        val candidates = functionsById[id]
            ?: throw IllegalArgumentException("No narrative function is registered for id `$id`")
        val matched = candidates.mapNotNull { definition ->
            definition.signature.match(arguments, typeRegistry)?.let { match -> definition to match }
        }
        val bestMatch = matched.minWithOrNull(
            compareBy<Pair<KatariFunctionDefinition, KatariSignatureMatch>> { it.second.totalDistance }
                .thenBy { it.second.anyMatches }
        )
            ?: throw IllegalArgumentException("No katari function overload `$id` matches arguments: $arguments")
        val best = matched.filter { it.second == bestMatch.second }.map { it.first }
        require(best.size == 1) {
            "Katari function call `$id` is ambiguous for arguments: $arguments. Candidates: ${
                best.joinToString { it.signature.displayName }
            }"
        }
        return best.single()
    }
}

data class KatariCallableSignature(
    val dispatchReceiverType: KatariParameterType? = null,
    val valueTypes: List<KatariParameterType> = emptyList(),
    val typeParameters: List<KatariTypeParameter> = emptyList(),
) {
    val displayName: String
        get() = buildString {
            if (typeParameters.isNotEmpty()) {
                append(typeParameters.joinToString(prefix = "<", postfix = ">") { it.name })
            }
            dispatchReceiverType?.let { append("${it.displayName}.") }
            append("(")
            append(valueTypes.joinToString { it.displayName })
            append(")")
        }

    fun match(arguments: List<KatariValue>, typeRegistry: KatariTypeRegistry): KatariSignatureMatch? {
        val receiverOffset = if (dispatchReceiverType != null) 1 else 0
        val repeatedType = valueTypes.lastOrNull()?.takeIf { it.isRepeated }
        val fixedValueCount = valueTypes.size - if (repeatedType != null) 1 else 0
        val valueArgumentCount = arguments.size - receiverOffset
        if (valueArgumentCount < fixedValueCount) {
            return null
        }
        if (repeatedType == null && valueArgumentCount != valueTypes.size) {
            return null
        }
        val distances = mutableListOf<Int>()
        dispatchReceiverType?.let {
            distances += arguments.firstOrNull()?.matchType(it, typeRegistry) ?: return null
        }
        valueTypes.forEachIndexed { index, typeId ->
            if (typeId.isRepeated) {
                for (argumentIndex in index until valueArgumentCount) {
                    distances += arguments[argumentIndex + receiverOffset].matchType(typeId.copy(isRepeated = false), typeRegistry)
                        ?: return null
                }
            } else {
                distances += arguments[index + receiverOffset].matchType(typeId, typeRegistry) ?: return null
            }
        }
        return KatariSignatureMatch(
            distances = distances,
            anyMatches = distances.count { it == KatariTypeRegistry.AnyDistance },
        )
    }
}

data class KatariSignatureMatch(
    val distances: List<Int>,
    val anyMatches: Int,
) {
    val totalDistance: Int = distances.sum()
}

data class KatariTypeRegistry(
    private val directSuperTypesByTypeId: Map<String, List<String>>,
) {
    constructor(types: Iterable<KatariType<out Any>>) : this(
        types.associate { type -> type.typeId to type.superTypes.map { it.typeId } },
    )

    fun distance(actualTypeId: String, expectedTypeId: String): Int? {
        if (expectedTypeId == "Any") return AnyDistance
        if (actualTypeId == expectedTypeId) return 0
        val visited = mutableSetOf<String>()
        var frontier = listOf(actualTypeId to 0)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Pair<String, Int>>()
            frontier.forEach { (typeId, distance) ->
                if (!visited.add(typeId)) return@forEach
                directSuperTypesByTypeId[typeId].orEmpty().forEach { parent ->
                    if (parent == expectedTypeId) return distance + 1
                    next += parent to distance + 1
                }
            }
            frontier = next
        }
        return null
    }

    fun mergedWith(other: KatariTypeRegistry): KatariTypeRegistry {
        return KatariTypeRegistry(
            directSuperTypesByTypeId + other.directSuperTypesByTypeId,
        )
    }

    companion object {
        val Empty = KatariTypeRegistry(emptyMap())
        const val AnyDistance = 1000
    }
}

private fun KatariValue.matchType(type: KatariParameterType, typeRegistry: KatariTypeRegistry): Int? {
    if (this == KatariValue.Null) {
        return if (type.isNullable) 0 else null
    }
    if (type.typeParameterName != null) {
        return matchType(type.upperBound ?: KatariTypes.Any, typeRegistry)
    }
    val expectedTypeId = type.typeId
    val actualTypeId = katariRuntimeTypeId()
    return when (expectedTypeId) {
        "Any" -> KatariTypeRegistry.AnyDistance
        "String" -> if (this is KatariValue.Text) 0 else null
        "Boolean" -> if (this is KatariValue.Bool) 0 else null
        "Int" -> if (this is KatariValue.Int32) 0 else null
        "Double" -> when (this) {
            is KatariValue.Float64 -> 0
            is KatariValue.Int32 -> 1
            else -> null
        }
        "Function" -> if (this is KatariValue.Lambda) 0 else null
        else -> {
            actualTypeId?.let { typeRegistry.distance(it, expectedTypeId) }
                ?: runtimeValueTypeDistance(type)
        }
    }
}

private fun KatariValue.katariRuntimeTypeId(): String? = when (this) {
    KatariValue.Null -> null
    is KatariValue.Bool -> "Boolean"
    is KatariValue.Int32 -> "Int"
    is KatariValue.Float64 -> "Double"
    is KatariValue.Text -> "String"
    is KatariValue.Lambda -> "Function"
    is KatariValue.HostObject -> typeId
}

private fun KatariValue.runtimeValueTypeDistance(type: KatariParameterType): Int? {
    val value = (this as? KatariValue.HostObject)?.value as? RuntimeValue ?: return null
    return value.type().distanceTo(type)
}

private fun DataType.distanceTo(type: KatariParameterType): Int? {
    if (isNullable && !type.isNullable) return null
    if (name == type.typeId) return 0
    return (this as? ObjectType)
        ?.findSuperType(type.typeId)
        ?.takeIf { !it.isNullable || type.isNullable }
        ?.let { 1 }
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
