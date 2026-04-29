package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.Interpreter
import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter
import com.sunnychung.lib.multiplatform.kotlite.model.*
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

data class KatariType<T : Any>(
    val kClass: KClass<T>,
    val typeId: String,
    val superTypes: List<KatariType<out Any>> = emptyList(),
)

fun <T : Any> KClass<T>.toKatari(
    typeId: String = defaultTypeId(),
    superTypes: List<KatariType<out Any>> = emptyList(),
): KatariType<T> {
    return KatariType(
        kClass = this,
        typeId = typeId,
        superTypes = superTypes,
    )
}

private fun KClass<*>.defaultTypeId(): String {
    return qualifiedName ?: simpleName
    ?: throw IllegalArgumentException("Cannot infer Kotlite type id for anonymous class `$this`")
}

data class KatariBindings(
    val functionRegistry: KatariFunctionRegistry,
    val propertyRegistry: KatariPropertyRegistry,
    val snapshotCodec: StateSnapshotCodec,
    val globals: Map<String, KatariValue>,
    val executionEnvironment: ExecutionEnvironment,
    val enumDefinitions: Map<String, KatariEnumDefinition> = emptyMap(),
    val importAliases: Map<String, String> = emptyMap(),
)

class NarrativeBindingsBuilder {
    private val executionEnvironment = ExecutionEnvironment()
    private var importExecutionEnvironmentFunctions = true
    private val functionDefinitions = mutableListOf<KatariFunctionDefinition>()
    private val valueCodecs = mutableListOf<ValueCodec<out ValueSnapshot>>()
    private val globals = linkedMapOf<String, KatariValue>()
    private val globalProperties = linkedMapOf<String, KatariGlobalPropertyDefinition>()
    private val hostTypes = mutableListOf<KatariType<out Any>>()
    private val enumDefinitions = linkedMapOf<String, KatariEnumDefinition>()
    private val importAliases = linkedMapOf<String, String>()
    private val importWildcards = mutableListOf<String>()

    fun register(function: KatariFunctionDefinition): NarrativeBindingsBuilder = apply {
        functionDefinitions += function
    }

    fun importExecutionEnvironmentFunctions(enabled: Boolean): NarrativeBindingsBuilder = apply {
        importExecutionEnvironmentFunctions = enabled
    }

    fun install(module: LibraryModule): NarrativeBindingsBuilder = apply {
        executionEnvironment.install(module)
    }

    fun import(path: String, alias: String? = null): NarrativeBindingsBuilder = apply {
        require(path.isNotBlank()) { "Katari binding import path cannot be blank" }
        val importedName = path.substringAfterLast('.')
        importAliases[alias ?: importedName] = path
    }

    fun importWildcard(path: String): NarrativeBindingsBuilder = apply {
        require(path.isNotBlank()) { "Katari binding wildcard import path cannot be blank" }
        importWildcards += path.removeSuffix(".*")
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
        type: KatariType<T>,
        name: String,
        execute: suspend (
            receiver: T,
            arguments: List<KatariValue>,
            context: KatariFunctionContext,
        ) -> KatariValue = { _, _, _ -> KatariValue.Null },
    ): NarrativeBindingsBuilder = apply {
        register(
            ImmediateKatariFunctionDefinition(
                id = name,
                signature = KatariCallableSignature(
                    dispatchReceiverType = type.asParameterType(),
                    returnType = KatariTypes.Unit,
                ),
                execute = { arguments, context ->
                    val receiver = arguments.extractHostReceiver(type, name)
                    execute(receiver, arguments.drop(1), context)
                },
            )
        )
    }

    fun <T : Any> registerSuspendableMember(
        type: KatariType<T>,
        name: String,
        onStart: suspend (
            receiver: T,
            arguments: List<KatariValue>,
            context: KatariFunctionContext,
        ) -> Unit = { _, _, _ -> },
        onDispatch: (
            receiver: T,
            arguments: List<KatariValue>,
            context: KatariFunctionDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) -> Unit,
        onResume: suspend (
            receiver: T,
            arguments: List<KatariValue>,
            response: FunctionResponse?,
            context: KatariFunctionContext,
        ) -> KatariValue = { _, _, _, _ -> KatariValue.Null },
    ): NarrativeBindingsBuilder = apply {
        register(
            SuspendableKatariFunctionDefinition(
                id = name,
                signature = KatariCallableSignature(
                    dispatchReceiverType = type.asParameterType(),
                    returnType = KatariTypes.Unit,
                ),
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

    fun register(functions: Iterable<KatariFunctionDefinition>): NarrativeBindingsBuilder = apply {
        functionDefinitions += functions
    }

    fun immediateFunction(
        name: String,
        valueParameters: List<KatariValueParameter> = emptyList(),
        dispatchReceiver: KatariParameterType? = null,
        returnType: KatariParameterType = KatariTypes.Unit,
        execute: suspend (
            receiver: KatariValue?,
            arguments: List<KatariValue>,
            context: KatariFunctionContext,
        ) -> KatariValue = { _, _, _ -> KatariValue.Null },
    ): NarrativeBindingsBuilder = apply {
        register(
            ImmediateKatariFunctionDefinition(
                id = name,
                signature = KatariCallableSignature(
                    dispatchReceiverType = dispatchReceiver,
                    valueParameters = valueParameters,
                    returnType = returnType,
                ),
                execute = { arguments, context ->
                    val receiverOffset = if (dispatchReceiver != null) 1 else 0
                    execute(
                        arguments.getOrNull(0).takeIf { dispatchReceiver != null },
                        arguments.drop(receiverOffset),
                        context
                    )
                },
            )
        )
    }

    fun <T : Any> immediateMemberFunction(
        type: KatariType<T>,
        name: String,
        valueParameters: List<KatariValueParameter> = emptyList(),
        returnType: KatariParameterType = KatariTypes.Unit,
        execute: suspend (
            receiver: T,
            arguments: List<KatariValue>,
            context: KatariFunctionContext,
        ) -> KatariValue = { _, _, _ -> KatariValue.Null },
    ): NarrativeBindingsBuilder = immediateFunction(
        name = name,
        valueParameters = valueParameters,
        dispatchReceiver = type.asParameterType(),
        returnType = returnType,
        execute = { receiver, arguments, context ->
            val hostReceiver = listOf(requireNotNull(receiver)).extractHostReceiver(type, name)
            execute(hostReceiver, arguments, context)
        },
    )

    fun globalProperty(
        name: String,
        type: KatariParameterType = KatariTypes.Any,
        getter: (() -> KatariValue)? = null,
        setter: ((KatariValue) -> Unit)? = null,
    ): NarrativeBindingsBuilder = apply {
        globalProperties[name] = KatariGlobalPropertyDefinition(name = name, type = type, getter = getter, setter = setter)
    }

    fun extensionProperty(
        name: String,
        receiver: KatariParameterType,
        valueType: KatariParameterType,
        getter: ((receiver: KatariValue, context: KatariFunctionContext) -> KatariValue)? = null,
        setter: ((receiver: KatariValue, value: KatariValue, context: KatariFunctionContext) -> Unit)? = null,
    ): NarrativeBindingsBuilder = apply {
        require(getter != null || setter != null) { "Extension property `$name` must declare getter or setter" }
        if (getter != null) {
            immediateFunction(
                name = name,
                dispatchReceiver = receiver,
                returnType = valueType,
                execute = { receiverValue, _, context -> getter(requireNotNull(receiverValue), context) },
            )
        }
        if (setter != null) {
            immediateFunction(
                name = name,
                dispatchReceiver = receiver,
                valueParameters = listOf(valueType.asValueParameter("value")),
                execute = { receiverValue, arguments, context ->
                    setter(requireNotNull(receiverValue), arguments.single(), context)
                    KatariValue.Null
                },
            )
        }
    }

    fun <T : Any> registerHostType(type: KatariType<T>): NarrativeBindingsBuilder = apply {
        hostTypes += type
    }

    fun <T : Enum<T>> registerEnum(
        type: KatariType<T>,
        values: List<T>,
    ): NarrativeBindingsBuilder = apply {
        registerHostType(type)
        enumDefinitions[type.typeId] = KatariEnumDefinition(
            typeId = type.typeId,
            entries = values.map { value ->
                KatariValue.EnumValue(
                    typeId = type.typeId,
                    entryName = value.name,
                    ordinal = value.ordinal,
                )
            },
        )
    }

    fun <T : Any, S : ValueSnapshot> registerHostType(
        type: KatariType<T>,
        snapshotClass: KClass<S>,
        snapshotSerializer: KSerializer<S>,
        serialize: (T) -> S,
        deserialize: suspend (S, ValueRestoreContext) -> T,
    ): NarrativeBindingsBuilder = apply {
        registerHostType(type)
        valueCodecs += object : ValueCodec<S> {
            override val typeId: String = type.typeId
            override val snapshotClass: KClass<S> = snapshotClass
            override val snapshotSerializer: KSerializer<S> = snapshotSerializer

            @Suppress("UNCHECKED_CAST")
            override fun serialize(value: Any): S {
                return serialize(value as T)
            }

            override suspend fun deserialize(snapshot: S, context: ValueRestoreContext): Any {
                return deserialize(snapshot, context)
            }
        }
    }

    fun global(name: String, value: Any?): NarrativeBindingsBuilder = apply {
        globals[name] = toNarrativeValue(value)
    }

    fun build(): KatariBindings {
        val codecRegistry = KatariValueCodecRegistry(valueCodecs)
        val hostTypeRegistry = KatariTypeRegistry(hostTypes)
        val bridge = if (importExecutionEnvironmentFunctions) {
            buildExecutionEnvironmentNarrativeBridge(executionEnvironment)
        } else {
            null
        }
        val typeRegistry = hostTypeRegistry.mergedWith(bridge?.typeRegistry ?: KatariTypeRegistry.Empty)
        val environmentDefinitions = bridge?.definitions ?: emptyList()
        val environmentGlobals = bridge?.globals ?: emptyMap()
        val baseGlobals = environmentGlobals + globals
        val importGlobals = importAliases.mapNotNull { (alias, target) ->
            baseGlobals[target]?.let { alias to it } ?: baseGlobals[target.substringAfterLast('.')]?.let { alias to it }
        }.toMap() + importWildcards.flatMap { prefix ->
            baseGlobals.mapNotNull { (name, value) ->
                name.removePrefix("$prefix.").takeIf { it != name && it.isNotBlank() }?.let { it to value }
            }
        }
        val normalizedGlobals = baseGlobals + importGlobals
        val baseDefinitions = environmentDefinitions + functionDefinitions
        val enumCollectionDefinitions = NarrativeBuiltinFunctions.enumCollectionDefinitions()
            .filterNot { enumDefinition ->
                baseDefinitions.any { definition ->
                    definition.id == enumDefinition.id &&
                        definition.signature.valueParameters.firstOrNull()?.type ==
                        enumDefinition.signature.valueParameters.firstOrNull()?.type
                }
            }
        return KatariBindings(
            functionRegistry = KatariFunctionRegistry(
                baseDefinitions + enumCollectionDefinitions,
                typeRegistry,
            ),
            propertyRegistry = KatariPropertyRegistry(globalProperties.values.toList()),
            snapshotCodec = StateSnapshotCodec(
                valueCodecs = codecRegistry,
                executionEnvironment = executionEnvironment,
            ),
            globals = normalizedGlobals,
            executionEnvironment = executionEnvironment,
            enumDefinitions = enumDefinitions,
            importAliases = importAliases,
        )
    }

    private fun toNarrativeValue(value: Any?): KatariValue {
        return when (value) {
            null -> KatariValue.Null
            is KatariValue -> value
            is Boolean -> KatariValue.Bool(value)
            is Int -> KatariValue.Int32(value)
            is Double -> KatariValue.Float64(value)
            is String -> KatariValue.Text(value)
            is Enum<*> -> {
                val hostType = hostTypes.firstOrNull { it.kClass.isInstance(value) }
                    ?: throw IllegalArgumentException(
                        "No Katari enum type is registered for `${value::class.qualifiedName}`. " +
                                "Register it with `registerEnum(...)` first."
                    )
                enumDefinitions.getValue(hostType.typeId).entry(value.name)
            }
            else -> {
                val hostType = hostTypes.firstOrNull { it.kClass.isInstance(value) }
                    ?: throw IllegalArgumentException(
                        "No Kotlite host type is registered for `${value::class.qualifiedName}`. " +
                                "Register `${value::class.simpleName}::class.toKotlite()` first."
                    )
                KatariValue.HostObject(typeId = hostType.typeId, value = value)
            }
        }
    }
}

fun NarrativeBindings(block: NarrativeBindingsBuilder.() -> Unit): KatariBindings {
    return NarrativeBindingsBuilder()
        .apply(block)
        .build()
}

private fun <T : Any> List<KatariValue>.extractHostReceiver(
    type: KatariType<T>,
    functionName: String,
): T {
    val receiverValue = firstOrNull()
        ?: throw IllegalArgumentException("Member function `$functionName` expects receiver `${type.typeId}`")
    val host = receiverValue as? KatariValue.HostObject
        ?: throw IllegalArgumentException("Member function `$functionName` expects host receiver `${type.typeId}`, got `$receiverValue`")
    require(host.typeId == type.typeId) {
        "Member function `$functionName` expects receiver type `${type.typeId}`, got `${host.typeId}`"
    }
    @Suppress("UNCHECKED_CAST")
    return host.value as T
}

class ImmediateKatariFunctionDefinition(
    override val id: String,
    override val signature: KatariCallableSignature,
    private val execute: suspend (arguments: List<KatariValue>, context: KatariFunctionContext) -> KatariValue = { _, _ ->
        KatariValue.Null
    },
) : KatariFunctionDefinition {

    override suspend fun startCall(
        arguments: List<KatariValue>,
        context: KatariFunctionContext,
    ): FunctionResult {
        return FunctionResult.Returned(execute(arguments, context))
    }

    override suspend fun resumeCall(
        arguments: List<KatariValue>,
        response: FunctionResponse?,
        context: KatariFunctionContext,
    ): FunctionResult {
        throw IllegalStateException("Immediate function `$id` cannot be resumed because it never suspends")
    }

    override fun dispatch(
        arguments: List<KatariValue>,
        context: KatariFunctionDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        throw IllegalStateException("Immediate function `$id` cannot be dispatched because it never suspends")
    }
}

class SuspendableKatariFunctionDefinition(
    override val id: String,
    override val signature: KatariCallableSignature,
    private val onStart: suspend (arguments: List<KatariValue>, context: KatariFunctionContext) -> Unit = { _, _ -> },
    private val onDispatch: (
        arguments: List<KatariValue>,
        context: KatariFunctionDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) -> Unit,
    private val onResume: suspend (
        arguments: List<KatariValue>,
        response: FunctionResponse?,
        context: KatariFunctionContext,
    ) -> KatariValue = { _, _, _ -> KatariValue.Null },
) : KatariFunctionDefinition {

    override suspend fun startCall(
        arguments: List<KatariValue>,
        context: KatariFunctionContext,
    ): FunctionResult {
        onStart(arguments, context)
        return FunctionResult.Suspended
    }

    override suspend fun resumeCall(
        arguments: List<KatariValue>,
        response: FunctionResponse?,
        context: KatariFunctionContext,
    ): FunctionResult {
        return FunctionResult.Returned(onResume(arguments, response, context))
    }

    override fun dispatch(
        arguments: List<KatariValue>,
        context: KatariFunctionDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        onDispatch(arguments, context, resume)
    }
}

private data class ExecutionEnvironmentNarrativeBridge(
    val definitions: List<KatariFunctionDefinition>,
    val globals: Map<String, KatariValue>,
    val typeRegistry: KatariTypeRegistry,
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
    val classes = executionEnvironment.getBuiltinClasses(interpreter.symbolTable())
    val constructableClassNames = classes
        .filter { it.isInstanceCreationAllowed }
        .map { it.fullQualifiedName.removeSuffix("?").removeSuffix(".Companion") }
        .distinct()
    val extensionProperties =
        executionEnvironment.getExtensionProperties(interpreter.symbolTable()).onEach { property ->
            if (property.receiverType == null) {
                property.receiverType = property.receiver.toTypeNode("<NarrativeBridge>")
            }
            if (property.typeNode == null) {
                property.typeNode = property.type.toTypeNode("<NarrativeBridge>")
            }
        }
    val definitions =
        declarations.map { declaration ->
            ExecutionEnvironmentKatariFunctionDefinition(
                id = declaration.name,
                signature = declaration.toKatariSignature(),
                interpreter = interpreter,
                overload = declaration,
            )
        } +
            extensionProperties.flatMap { property -> property.toKatariDefinitions(interpreter) } +
            constructableClassNames.mapNotNull { name ->
                val clazz = interpreter.symbolTable().findClass(name)?.first ?: return@mapNotNull null
                ExecutionEnvironmentKatariFunctionDefinition(
                    id = name,
                    signature = clazz.toKatariConstructorSignature(),
                    interpreter = interpreter,
                    constructorClassName = name,
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
        typeRegistry = classes.toKatariTypeRegistry(),
    )
}

private fun List<ClassDefinition>.toKatariTypeRegistry(): KatariTypeRegistry {
    val directSuperTypesByTypeId = associate { clazz ->
        clazz.fullQualifiedName to (
            listOfNotNull(
                clazz.superClass?.fullQualifiedName,
                (clazz.superClassInvocation?.function as? TypeNode)?.name,
            ) +
                clazz.superInterfaces.map { it.fullQualifiedName } +
                clazz.superInterfaceTypes.map { it.name }
            )
    }
    return KatariTypeRegistry(directSuperTypesByTypeId)
}

private class ExecutionEnvironmentKatariFunctionDefinition(
    override val id: String,
    override val signature: KatariCallableSignature,
    private val interpreter: Interpreter,
    private val overload: FunctionDeclarationNode? = null,
    private val property: ExtensionProperty? = null,
    private val propertySetter: Boolean = false,
    private val constructorClassName: String? = null,
) : KatariFunctionDefinition {

    override suspend fun startCall(
        arguments: List<KatariValue>,
        context: KatariFunctionContext,
    ): FunctionResult {
        val runtimeResult = invokeOverload(arguments)
        return FunctionResult.Returned(runtimeResult.toNarrativeValue())
    }

    override suspend fun resumeCall(
        arguments: List<KatariValue>,
        response: FunctionResponse?,
        context: KatariFunctionContext,
    ): FunctionResult {
        throw IllegalStateException("ExecutionEnvironment-backed function `$id` cannot be resumed because it never suspends")
    }

    override fun dispatch(
        arguments: List<KatariValue>,
        context: KatariFunctionDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        throw IllegalStateException("ExecutionEnvironment-backed function `$id` cannot be dispatched because it never suspends")
    }

    private fun invokeOverload(arguments: List<KatariValue>): RuntimeValue {
        if (overload != null) {
            val (receiver, callArgs) = splitReceiver(overload, arguments)
            val inferredTypeArguments = inferTypeArguments(overload, receiver, callArgs)
            return interpreter.evalFunctionCall(
                arguments = callArgs.toTypedArray(),
                typeArguments = overload.typeParameters.mapNotNull { inferredTypeArguments[it.name] }.toTypedArray(),
                callPosition = SourcePosition.NONE,
                functionNode = overload,
                extraScopeParameters = emptyMap(),
                extraTypeResolutions = emptyList(),
                subject = receiver,
            ).result
        }
        invokePropertyIfAvailable(arguments)?.let { return it }
        return constructorClassName?.let { invokeConstructorIfAvailable(it, arguments) }
            ?: throw IllegalArgumentException(
                "ExecutionEnvironment callable `$id` has no target for signature ${signature.displayName}"
            )
    }

    private fun splitReceiver(
        overload: FunctionDeclarationNode,
        arguments: List<KatariValue>,
    ): Pair<RuntimeValue?, List<RuntimeValue?>> = if (overload.receiver != null) {
        arguments.first().toRuntimeValue(interpreter) to arguments.drop(1).map { it.toRuntimeValueOrDefault(interpreter) }
    } else {
        null to arguments.map { it.toRuntimeValueOrDefault(interpreter) }
    }

    private fun inferTypeArguments(
        overload: FunctionDeclarationNode,
        receiver: RuntimeValue?,
        arguments: List<RuntimeValue?>,
    ): Map<String, TypeNode> {
        if (overload.typeParameters.isEmpty()) {
            return emptyMap()
        }

        val inferred = linkedMapOf<String, DataType>()
        overload.receiver?.let { receiverType ->
            receiver?.let { collectTypeArguments(receiverType, it.type(), overload.typeParameters, inferred) }
        }

        val parameterTypes = overload.valueParameters.mapNotNull { it.declaredType }
        if (overload.isNarrativeVararg()) {
            val parameterType = parameterTypes.singleOrNull()
            if (parameterType != null) {
                arguments.forEach { argument ->
                    argument?.let { collectTypeArguments(parameterType, it.type(), overload.typeParameters, inferred) }
                }
            }
        } else {
            parameterTypes.zip(arguments).forEach { (parameterType, argument) ->
                argument?.let { collectTypeArguments(parameterType, it.type(), overload.typeParameters, inferred) }
            }
        }

        return overload.typeParameters.associate { parameter ->
            val inferredType = inferred[parameter.name]
                ?: interpreter.symbolTable().assertToDataType(
                    parameter.typeUpperBound ?: TypeNode(SourcePosition.NONE, "Any", null, true)
                )
            parameter.name to inferredType.toTypeNode()
        }
    }

    private fun collectTypeArguments(
        declaredType: TypeNode,
        actualType: DataType,
        typeParameters: List<TypeParameterNode>,
        inferred: MutableMap<String, DataType>,
    ) {
        val typeParameterNames = typeParameters.map { it.name }.toSet()
        if (declaredType.name in typeParameterNames) {
            mergeInferredType(declaredType.name, actualType, inferred)
            return
        }
        if (declaredType.name == "<Repeated>") {
            declaredType.arguments?.singleOrNull()?.let {
                collectTypeArguments(it, actualType, typeParameters, inferred)
            }
            return
        }
        val actualObjectType = actualType.asObjectType() ?: return
        val matchingType = if (declaredType.name == actualObjectType.name) {
            actualObjectType
        } else {
            actualObjectType.findSuperType(declaredType.name) ?: return
        }
        declaredType.arguments.orEmpty().zip(matchingType.arguments).forEach { (declaredArgument, actualArgument) ->
            collectTypeArguments(declaredArgument, actualArgument, typeParameters, inferred)
        }
    }

    private fun mergeInferredType(
        name: String,
        candidate: DataType,
        inferred: MutableMap<String, DataType>,
    ) {
        val existing = inferred[name]
        inferred[name] = when {
            existing == null -> candidate
            existing.isConvertibleFrom(candidate) -> existing
            candidate.isConvertibleFrom(existing) -> candidate
            else -> interpreter.symbolTable().AnyType.copyOf(existing.isNullable || candidate.isNullable)
        }
    }

    private fun invokeConstructorIfAvailable(name: String, arguments: List<KatariValue>): RuntimeValue? {
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

    private fun invokePropertyIfAvailable(arguments: List<KatariValue>): RuntimeValue? {
        if (property == null) {
            return null
        }
        val receiver = arguments.first().toRuntimeValue(interpreter)

        if (!propertySetter && receiver is ClassInstance) {
            receiver.clazz?.findMemberPropertyTransformedName(id)?.let { transformedName ->
                return when (val value = receiver.read(interpreter = interpreter, name = transformedName)) {
                    is RuntimeValue -> value
                    else -> throw UnsupportedOperationException("Unsupported member property value for `$id`")
                }
            }
        }

        return if (propertySetter) {
            val setter = property.setter ?: return null
            setter(interpreter, receiver, arguments[1].toRuntimeValue(interpreter), property.typeArgumentsMap(receiver.type()))
            NullValue
        } else {
            val getter = property.getter ?: return null
            getter(interpreter, receiver, property.typeArgumentsMap(receiver.type()))
        }
    }
}

private fun FunctionDeclarationNode.isNarrativeVararg(): Boolean {
    return valueParameters.firstOrNull()?.modifiers?.contains(FunctionValueParameterModifier.vararg) == true
}

private fun FunctionDeclarationNode.toKatariSignature(): KatariCallableSignature {
    val signatureTypeParameters = typeParameters.map { parameter ->
        KatariTypeParameter(
            name = parameter.name,
            upperBound = parameter.typeUpperBound
                ?.takeUnless { it.name == parameter.name }
                ?.toKatariParameterType(typeParameters)
                ?: KatariTypes.Any,
        )
    }
    val signatureValueParameters = if (isNarrativeVararg()) {
        valueParameters.singleOrNull()?.declaredType?.toKatariParameterType(typeParameters)?.repeated()
            ?.let { listOf(it.asValueParameter(valueParameters.single().name)) }
            ?: listOf(KatariTypes.Any.repeated().asValueParameter("args"))
    } else {
        valueParameters.map { parameter ->
            KatariValueParameter(
                name = parameter.name,
                type = parameter.declaredType?.toKatariParameterType(typeParameters) ?: KatariTypes.Any,
                hasDefault = parameter.defaultValue != null,
            )
        }
    }
    return KatariCallableSignature(
        dispatchReceiverType = receiver?.toKatariParameterType(typeParameters),
        valueParameters = signatureValueParameters,
        typeParameters = signatureTypeParameters,
        returnType = declaredReturnType?.toKatariParameterType(typeParameters) ?: KatariTypes.Any,
    )
}

private fun ExtensionProperty.toKatariDefinitions(interpreter: Interpreter): List<KatariFunctionDefinition> {
    val sourceTypeParameters = typeParameters.toTypeParameterNodes()
    val receiver = receiverType?.toKatariParameterType(sourceTypeParameters)
        ?: receiver.toTypeNode("<NarrativeBridge>").toKatariParameterType(sourceTypeParameters)
    val value = typeNode?.toKatariParameterType(sourceTypeParameters)
        ?: type.toTypeNode("<NarrativeBridge>").toKatariParameterType(sourceTypeParameters)
    val signatureTypeParameters = typeParameters.map {
        KatariTypeParameter(
            name = it.name,
            upperBound = it.typeUpperBound
                ?.toTypeNode("<NarrativeBridge>")
                ?.takeUnless { upperBound -> upperBound.name == it.name }
                ?.toKatariParameterType(sourceTypeParameters)
                ?: KatariTypes.Any,
        )
    }
    return listOfNotNull(
        getter?.let {
            ExecutionEnvironmentKatariFunctionDefinition(
                id = declaredName,
                signature = KatariCallableSignature(
                    dispatchReceiverType = receiver,
                    typeParameters = signatureTypeParameters,
                    returnType = value,
                ),
                interpreter = interpreter,
                property = this,
            )
        },
        setter?.let {
            ExecutionEnvironmentKatariFunctionDefinition(
                id = declaredName,
                signature = KatariCallableSignature(
                    dispatchReceiverType = receiver,
                    valueParameters = listOf(value.asValueParameter("value")),
                    typeParameters = signatureTypeParameters,
                    returnType = KatariTypes.Unit,
                ),
                interpreter = interpreter,
                property = this,
                propertySetter = true,
            )
        },
    )
}

private fun ClassDefinition.toKatariConstructorSignature(): KatariCallableSignature {
    return KatariCallableSignature(
        valueParameters = primaryConstructor?.parameters.orEmpty().map { parameter ->
            KatariValueParameter(
                name = parameter.parameter.name,
                type = parameter.parameter.declaredType?.toKatariParameterType(typeParameters) ?: KatariTypes.Any,
                hasDefault = parameter.parameter.defaultValue != null,
            )
        },
        typeParameters = typeParameters.map { parameter ->
            KatariTypeParameter(
                name = parameter.name,
                upperBound = parameter.typeUpperBound
                    ?.takeUnless { it.name == parameter.name }
                    ?.toKatariParameterType(typeParameters)
                    ?: KatariTypes.Any,
            )
        },
        returnType = KatariParameterType(fullQualifiedName),
    )
}

private fun TypeNode.toKatariParameterType(
    typeParameters: List<TypeParameterNode> = emptyList(),
): KatariParameterType {
    val typeParameter = typeParameters.firstOrNull { it.name == name }
    if (typeParameter != null) {
        return KatariTypes.typeParameter(
            name = typeParameter.name,
        ).copy(isNullable = isNullable)
    }
    if (name == "<Repeated>") {
        return arguments?.singleOrNull()?.toKatariParameterType(typeParameters)?.repeated()
            ?: throw IllegalArgumentException("Repeated type `$this` must declare exactly one element type")
    }
    return KatariParameterType(
        typeId = name,
        isNullable = isNullable,
        typeArguments = arguments.orEmpty().map { it.toKatariParameterType(typeParameters) },
    )
}

private fun DataType.asObjectType(): ObjectType? {
    return when (this) {
        is ObjectType -> this
        is TypeParameterType -> upperBound.asObjectType()
        is RepeatedType -> actualTypeOrAny().asObjectType()
        else -> null
    }
}

private fun KatariValue.toRuntimeValue(interpreter: Interpreter): RuntimeValue {
    val symbolTable = interpreter.symbolTable()
    return when (this) {
        KatariValue.DefaultArgument -> throw IllegalArgumentException("Default argument marker cannot be converted to RuntimeValue directly")
        KatariValue.Null -> NullValue
        is KatariValue.Bool -> BooleanValue(value, symbolTable)
        is KatariValue.Int32 -> IntValue(value, symbolTable)
        is KatariValue.Float64 -> DoubleValue(value, symbolTable)
        is KatariValue.Text -> StringValue(value, symbolTable)
        is KatariValue.Lambda -> throw IllegalArgumentException(
            "ExecutionEnvironment bridge cannot convert Narrative lambda `$id` to RuntimeValue."
        )
        is KatariValue.EnumValue -> throw IllegalArgumentException(
            "ExecutionEnvironment bridge cannot convert Katari enum `$typeId.$entryName` to RuntimeValue automatically."
        )
        is KatariValue.EnumEntries -> throw IllegalArgumentException(
            "ExecutionEnvironment bridge cannot convert Katari enum entries `$typeId.entries` to RuntimeValue automatically."
        )

        is KatariValue.HostObject -> {
            value as? RuntimeValue
                ?: throw IllegalArgumentException(
                    "ExecutionEnvironment bridge cannot convert HostObject(typeId=$typeId) to RuntimeValue automatically. " +
                            "Use RuntimeValue-backed objects or narrative-native functions for this call."
                )
        }
    }
}

private fun KatariValue.toRuntimeValueOrDefault(interpreter: Interpreter): RuntimeValue? {
    return if (this == KatariValue.DefaultArgument) {
        null
    } else {
        toRuntimeValue(interpreter)
    }
}

private fun RuntimeValue.toNarrativeValue(): KatariValue {
    return when (this) {
        NullValue -> KatariValue.Null
        is BooleanValue -> KatariValue.Bool(value)
        is IntValue -> KatariValue.Int32(value)
        is DoubleValue -> KatariValue.Float64(value)
        is StringValue -> KatariValue.Text(value)
        is KotlinValueHolder<*> -> {
            if (value == null) {
                KatariValue.Null
            } else {
                KatariValue.HostObject(typeId = type().name, value = this)
            }
        }

        else -> KatariValue.HostObject(typeId = type().name, value = this)
    }
}
