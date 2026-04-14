package com.sunnychung.lib.multiplatform.kotlite.narrative

import com.sunnychung.lib.multiplatform.kotlite.Interpreter
import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.ClassDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.ExtensionProperty
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.GlobalProperty
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.KotlinValueHolder
import com.sunnychung.lib.multiplatform.kotlite.model.LibraryModule
import com.sunnychung.lib.multiplatform.kotlite.model.NullValue
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.ProvidedClassDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

data class KotliteHostType<T : Any>(
    val kClass: KClass<T>,
    val typeId: String,
)

fun <T : Any> KClass<T>.toKotlite(typeId: String = defaultKotliteTypeId()): KotliteHostType<T> {
    return KotliteHostType(
        kClass = this,
        typeId = typeId,
    )
}

private fun KClass<*>.defaultKotliteTypeId(): String {
    return qualifiedName ?: simpleName
    ?: throw IllegalArgumentException("Cannot infer Kotlite type id for anonymous class `$this`")
}

data class NarrativeBindings(
    val functionRegistry: NarrativeFunctionRegistry,
    val snapshotCodec: NarrativeStateSnapshotCodec,
    val globals: Map<String, NarrativeValue>,
    val executionEnvironment: ExecutionEnvironment,
)

class NarrativeBindingsBuilder {
    private val executionEnvironment = ExecutionEnvironment()
    private var importExecutionEnvironmentFunctions = true
    private val functionDefinitions = mutableListOf<NarrativeFunctionDefinition>()
    private val valueCodecs = mutableListOf<NarrativeValueCodec<out NarrativeValueSnapshot>>()
    private val globals = linkedMapOf<String, NarrativeValue>()
    private val hostTypes = mutableListOf<KotliteHostType<out Any>>()

    fun register(function: NarrativeFunctionDefinition): NarrativeBindingsBuilder = apply {
        functionDefinitions += function
    }

    fun importExecutionEnvironmentFunctions(enabled: Boolean): NarrativeBindingsBuilder = apply {
        importExecutionEnvironmentFunctions = enabled
    }

    fun install(module: LibraryModule): NarrativeBindingsBuilder = apply {
        executionEnvironment.install(module)
    }

    fun registerKotliteFunction(function: CustomFunctionDefinition): NarrativeBindingsBuilder = apply {
        executionEnvironment.registerFunction(function)
    }

    fun registerKotliteExtensionProperty(property: ExtensionProperty): NarrativeBindingsBuilder = apply {
        executionEnvironment.registerExtensionProperty(property)
    }

    fun registerKotliteGlobalProperty(property: GlobalProperty): NarrativeBindingsBuilder = apply {
        executionEnvironment.registerGlobalProperty(property)
    }

    fun registerKotliteClass(clazz: ProvidedClassDefinition): NarrativeBindingsBuilder = apply {
        executionEnvironment.registerClass(clazz)
    }

    fun configureExecutionEnvironment(configure: ExecutionEnvironment.() -> Unit): NarrativeBindingsBuilder = apply {
        executionEnvironment.configure()
    }

    fun <T : Any> registerImmediateMember(
        type: KotliteHostType<T>,
        name: String,
        execute: suspend (
            receiver: T,
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionContext,
        ) -> NarrativeValue = { _, _, _ -> NarrativeValue.Null },
    ): NarrativeBindingsBuilder = apply {
        register(
            ImmediateNarrativeFunctionDefinition(
                id = name,
                execute = { arguments, context ->
                    val receiver = arguments.extractHostReceiver(type, name)
                    execute(receiver, arguments.drop(1), context)
                },
            )
        )
    }

    fun <T : Any> registerSuspendableMember(
        type: KotliteHostType<T>,
        name: String,
        onStart: suspend (
            receiver: T,
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionContext,
        ) -> Unit = { _, _, _ -> },
        onDispatch: (
            receiver: T,
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) -> Unit,
        onResume: suspend (
            receiver: T,
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ) -> NarrativeValue = { _, _, _, _ -> NarrativeValue.Null },
    ): NarrativeBindingsBuilder = apply {
        register(
            SuspendableNarrativeFunctionDefinition(
                id = name,
                onStart = { arguments, context ->
                    val receiver = arguments.extractHostReceiver(type, name)
                    onStart(receiver, arguments.drop(1), context)
                },
                onDispatch = { arguments, context, resume ->
                    val receiver = arguments.extractHostReceiver(type, name)
                    onDispatch(receiver, arguments.drop(1), context, resume)
                },
                onResume = { arguments, response, context ->
                    val receiver = arguments.extractHostReceiver(type, name)
                    onResume(receiver, arguments.drop(1), response, context)
                },
            )
        )
    }

    fun registerBuiltinFunctions(host: NarrativeHost): NarrativeBindingsBuilder = apply {
        functionDefinitions += NarrativeBuiltinFunctions.definitions(host)
    }

    fun register(functions: Iterable<NarrativeFunctionDefinition>): NarrativeBindingsBuilder = apply {
        functionDefinitions += functions
    }

    fun <T : Any> registerHostType(type: KotliteHostType<T>): NarrativeBindingsBuilder = apply {
        hostTypes += type
    }

    fun <T : Any, S : NarrativeValueSnapshot> registerHostType(
        type: KotliteHostType<T>,
        snapshotClass: KClass<S>,
        snapshotSerializer: KSerializer<S>,
        serialize: (T) -> S,
        deserialize: suspend (S, NarrativeValueRestoreContext) -> T,
    ): NarrativeBindingsBuilder = apply {
        registerHostType(type)
        valueCodecs += object : NarrativeValueCodec<S> {
            override val typeId: String = type.typeId
            override val snapshotClass: KClass<S> = snapshotClass
            override val snapshotSerializer: KSerializer<S> = snapshotSerializer

            @Suppress("UNCHECKED_CAST")
            override fun serialize(value: Any): S {
                return serialize(value as T)
            }

            override suspend fun deserialize(snapshot: S, context: NarrativeValueRestoreContext): Any {
                return deserialize(snapshot, context)
            }
        }
    }

    fun global(name: String, value: Any?): NarrativeBindingsBuilder = apply {
        globals[name] = toNarrativeValue(value)
    }

    fun build(): NarrativeBindings {
        val codecRegistry = NarrativeValueCodecRegistry(valueCodecs)
        val bridge = if (importExecutionEnvironmentFunctions) {
            buildExecutionEnvironmentNarrativeBridge(executionEnvironment)
        } else {
            null
        }
        val environmentDefinitions = bridge?.definitions ?: emptyList()
        val environmentGlobals = bridge?.globals ?: emptyMap()
        val normalizedGlobals = environmentGlobals + globals
        return NarrativeBindings(
            functionRegistry = NarrativeFunctionRegistry(environmentDefinitions + functionDefinitions),
            snapshotCodec = NarrativeStateSnapshotCodec(valueCodecs = codecRegistry),
            globals = normalizedGlobals,
            executionEnvironment = executionEnvironment,
        )
    }

    private fun toNarrativeValue(value: Any?): NarrativeValue {
        return when (value) {
            null -> NarrativeValue.Null
            is NarrativeValue -> value
            is Boolean -> NarrativeValue.Bool(value)
            is Int -> NarrativeValue.Int32(value)
            is Double -> NarrativeValue.Float64(value)
            is String -> NarrativeValue.Text(value)
            else -> {
                val hostType = hostTypes.firstOrNull { it.kClass.isInstance(value) }
                    ?: throw IllegalArgumentException(
                        "No Kotlite host type is registered for `${value::class.qualifiedName}`. " +
                            "Register `${value::class.simpleName}::class.toKotlite()` first."
                    )
                NarrativeValue.HostObject(typeId = hostType.typeId, value = value)
            }
        }
    }
}

fun NarrativeBindings(block: NarrativeBindingsBuilder.() -> Unit): NarrativeBindings {
    return NarrativeBindingsBuilder()
        .apply(block)
        .build()
}

private fun <T : Any> List<NarrativeValue>.extractHostReceiver(
    type: KotliteHostType<T>,
    functionName: String,
): T {
    val receiverValue = firstOrNull()
        ?: throw IllegalArgumentException("Member function `$functionName` expects receiver `${type.typeId}`")
    val host = receiverValue as? NarrativeValue.HostObject
        ?: throw IllegalArgumentException("Member function `$functionName` expects host receiver `${type.typeId}`, got `$receiverValue`")
    require(host.typeId == type.typeId) {
        "Member function `$functionName` expects receiver type `${type.typeId}`, got `${host.typeId}`"
    }
    @Suppress("UNCHECKED_CAST")
    return host.value as T
}

class ImmediateNarrativeFunctionDefinition(
    override val id: String,
    private val execute: suspend (arguments: List<NarrativeValue>, context: NarrativeFunctionContext) -> NarrativeValue = { _, _ ->
        NarrativeValue.Null
    },
) : NarrativeFunctionDefinition {

    override suspend fun startCall(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        return NarrativeFunctionResult.Returned(execute(arguments, context))
    }

    override suspend fun resumeCall(
        arguments: List<NarrativeValue>,
        response: NarrativeFunctionResponse?,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        throw IllegalStateException("Immediate function `$id` cannot be resumed because it never suspends")
    }

    override fun dispatch(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionDispatchContext,
        resume: (NarrativeFunctionResponse?) -> Unit,
    ) {
        throw IllegalStateException("Immediate function `$id` cannot be dispatched because it never suspends")
    }
}

class SuspendableNarrativeFunctionDefinition(
    override val id: String,
    private val onStart: suspend (arguments: List<NarrativeValue>, context: NarrativeFunctionContext) -> Unit = { _, _ -> },
    private val onDispatch: (
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionDispatchContext,
        resume: (NarrativeFunctionResponse?) -> Unit,
    ) -> Unit,
    private val onResume: suspend (
        arguments: List<NarrativeValue>,
        response: NarrativeFunctionResponse?,
        context: NarrativeFunctionContext,
    ) -> NarrativeValue = { _, _, _ -> NarrativeValue.Null },
) : NarrativeFunctionDefinition {

    override suspend fun startCall(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        onStart(arguments, context)
        return NarrativeFunctionResult.Suspended
    }

    override suspend fun resumeCall(
        arguments: List<NarrativeValue>,
        response: NarrativeFunctionResponse?,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        return NarrativeFunctionResult.Returned(onResume(arguments, response, context))
    }

    override fun dispatch(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionDispatchContext,
        resume: (NarrativeFunctionResponse?) -> Unit,
    ) {
        onDispatch(arguments, context, resume)
    }
}

private data class ExecutionEnvironmentNarrativeBridge(
    val definitions: List<NarrativeFunctionDefinition>,
    val globals: Map<String, NarrativeValue>,
)

private fun buildExecutionEnvironmentNarrativeBridge(
    executionEnvironment: ExecutionEnvironment,
): ExecutionEnvironmentNarrativeBridge {
    val interpreter = KotliteInterpreter(
        filename = "<NarrativeBridge>",
        code = "",
        executionEnvironment = executionEnvironment,
    )
    val declarations = executionEnvironment.getBuiltinFunctions(interpreter.symbolTable())
    val groupedByName = declarations.groupBy { it.name }
    val definitions = groupedByName.map { (name, overloads) ->
        ExecutionEnvironmentNarrativeFunctionDefinition(
            id = name,
            interpreter = interpreter,
            overloads = overloads,
        )
    }
    val globals = executionEnvironment.getGlobalProperties(interpreter.symbolTable())
        .mapNotNull { property ->
            val getter = property.getter ?: return@mapNotNull null
            property.declaredName to getter(interpreter).toNarrativeValue()
        }
        .toMap()
    return ExecutionEnvironmentNarrativeBridge(
        definitions = definitions,
        globals = globals,
    )
}

private class ExecutionEnvironmentNarrativeFunctionDefinition(
    override val id: String,
    private val interpreter: Interpreter,
    private val overloads: List<FunctionDeclarationNode>,
) : NarrativeFunctionDefinition {

    override suspend fun startCall(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        val runtimeResult = invokeOverload(arguments)
        return NarrativeFunctionResult.Returned(runtimeResult.toNarrativeValue())
    }

    override suspend fun resumeCall(
        arguments: List<NarrativeValue>,
        response: NarrativeFunctionResponse?,
        context: NarrativeFunctionContext,
    ): NarrativeFunctionResult {
        throw IllegalStateException("ExecutionEnvironment-backed function `$id` cannot be resumed because it never suspends")
    }

    override fun dispatch(
        arguments: List<NarrativeValue>,
        context: NarrativeFunctionDispatchContext,
        resume: (NarrativeFunctionResponse?) -> Unit,
    ) {
        throw IllegalStateException("ExecutionEnvironment-backed function `$id` cannot be dispatched because it never suspends")
    }

    private fun invokeOverload(arguments: List<NarrativeValue>): RuntimeValue {
        val filtered = overloads.filter { overload ->
            val receiverOffset = if (overload.receiver != null) 1 else 0
            if (arguments.size != overload.valueParameters.size + receiverOffset) {
                return@filter false
            }
            if (overload.receiver != null) {
                val receiverArgument = arguments.first()
                val receiverType = overload.receiver
                if (!receiverType.matches(receiverArgument)) {
                    return@filter false
                }
            }
            overload.valueParameters.withIndex().all { (index, parameter) ->
                val argumentIndex = if (overload.receiver != null) index + 1 else index
                val declaredType = parameter.declaredType ?: return@all true
                declaredType.matches(arguments[argumentIndex])
            }
        }
        val overload = filtered.firstOrNull()
        if (overload != null) {
            val (receiver, callArgs) = splitReceiver(overload, arguments)
            return overload.execute(
                interpreter = interpreter,
                receiver = receiver,
                arguments = callArgs,
                typeArguments = emptyMap(),
            )
        }
        return invokeConstructorIfAvailable(id, arguments)
            ?: throw IllegalArgumentException(
                "No ExecutionEnvironment callable of `$id` matches narrative arguments: $arguments"
            )
    }

    private fun splitReceiver(
        overload: FunctionDeclarationNode,
        arguments: List<NarrativeValue>,
    ): Pair<RuntimeValue?, List<RuntimeValue>> {
        val receiver = if (overload.receiver != null) {
            arguments.first().toRuntimeValue(interpreter)
        } else {
            null
        }
        val callArgs = if (overload.receiver != null) {
            arguments.drop(1)
        } else {
            arguments
        }.map { it.toRuntimeValue(interpreter) }
        return receiver to callArgs
    }

    private fun invokeConstructorIfAvailable(name: String, arguments: List<NarrativeValue>): RuntimeValue? {
        val clazz = interpreter.symbolTable().findClass(name)?.first ?: return null
        if (!clazz.isInstanceCreationAllowed) {
            return null
        }
        return clazz.construct(
            interpreter = interpreter,
            callArguments = arguments.map { it.toRuntimeValue(interpreter) }.toTypedArray(),
            typeArguments = emptyArray(),
            callPosition = SourcePosition.NONE,
        )
    }
}

private fun com.sunnychung.lib.multiplatform.kotlite.model.TypeNode.matches(value: NarrativeValue): Boolean {
    if (value == NarrativeValue.Null) {
        return isNullable
    }
    return when (name) {
        "Any" -> true
        "String" -> value is NarrativeValue.Text || value is NarrativeValue.Entity
        "Boolean" -> value is NarrativeValue.Bool
        "Int" -> value is NarrativeValue.Int32
        "Double" -> value is NarrativeValue.Float64 || value is NarrativeValue.Int32
        else -> value is NarrativeValue.HostObject && value.typeId == name
    }
}

private fun NarrativeValue.toRuntimeValue(interpreter: Interpreter): RuntimeValue {
    val symbolTable = interpreter.symbolTable()
    return when (this) {
        NarrativeValue.Null -> NullValue
        is NarrativeValue.Bool -> BooleanValue(value, symbolTable)
        is NarrativeValue.Int32 -> IntValue(value, symbolTable)
        is NarrativeValue.Float64 -> DoubleValue(value, symbolTable)
        is NarrativeValue.Text -> StringValue(value, symbolTable)
        is NarrativeValue.Entity -> StringValue(id, symbolTable)
        is NarrativeValue.HostObject -> {
            value as? RuntimeValue
                ?: throw IllegalArgumentException(
                    "ExecutionEnvironment bridge cannot convert HostObject(typeId=$typeId) to RuntimeValue automatically. " +
                        "Use RuntimeValue-backed objects or narrative-native functions for this call."
                )
        }
    }
}

private fun RuntimeValue.toNarrativeValue(): NarrativeValue {
    return when (this) {
        NullValue -> NarrativeValue.Null
        is BooleanValue -> NarrativeValue.Bool(value)
        is IntValue -> NarrativeValue.Int32(value)
        is DoubleValue -> NarrativeValue.Float64(value)
        is StringValue -> NarrativeValue.Text(value)
        is KotlinValueHolder<*> -> {
            val unwrapped = value
            if (unwrapped == null) {
                NarrativeValue.Null
            } else {
                NarrativeValue.HostObject(typeId = type().name, value = unwrapped)
            }
        }
        else -> NarrativeValue.HostObject(typeId = type().name, value = this)
    }
}
