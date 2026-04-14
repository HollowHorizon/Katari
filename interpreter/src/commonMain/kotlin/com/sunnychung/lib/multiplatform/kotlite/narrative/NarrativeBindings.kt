package com.sunnychung.lib.multiplatform.kotlite.narrative

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
)

class NarrativeBindingsBuilder {
    private val functionDefinitions = mutableListOf<NarrativeFunctionDefinition>()
    private val valueCodecs = mutableListOf<NarrativeValueCodec<out NarrativeValueSnapshot>>()
    private val globals = linkedMapOf<String, NarrativeValue>()
    private val hostTypes = mutableListOf<KotliteHostType<out Any>>()

    fun register(function: NarrativeFunctionDefinition): NarrativeBindingsBuilder = apply {
        functionDefinitions += function
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
        return NarrativeBindings(
            functionRegistry = NarrativeFunctionRegistry(functionDefinitions),
            snapshotCodec = NarrativeStateSnapshotCodec(valueCodecs = codecRegistry),
            globals = globals.toMap(),
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
