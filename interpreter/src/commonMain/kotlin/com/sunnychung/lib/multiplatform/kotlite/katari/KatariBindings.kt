package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.Interpreter
import com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter
import com.sunnychung.lib.multiplatform.kotlite.evalKotliteExpression
import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.ClassModifier
import com.sunnychung.lib.multiplatform.kotlite.model.ClassDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter
import com.sunnychung.lib.multiplatform.kotlite.model.DataType
import com.sunnychung.lib.multiplatform.kotlite.model.DefaultArgumentMarker
import com.sunnychung.lib.multiplatform.kotlite.model.DelegatedValue
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.ExtensionProperty
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionDeclarationNode
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionValueParameterModifier
import com.sunnychung.lib.multiplatform.kotlite.model.GlobalProperty
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.LibraryModule
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallContext
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallDispatchContext
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallResult
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallable
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeEnumValue
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeHostValue
import com.sunnychung.lib.multiplatform.kotlite.model.NullValue
import com.sunnychung.lib.multiplatform.kotlite.model.ObjectType
import com.sunnychung.lib.multiplatform.kotlite.model.ProvidedClassDefinition
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.SourcePosition
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.model.SymbolTable
import com.sunnychung.lib.multiplatform.kotlite.model.TypeNode
import com.sunnychung.lib.multiplatform.kotlite.model.TypeParameter
import com.sunnychung.lib.multiplatform.kotlite.model.TypeParameterNode
import com.sunnychung.lib.multiplatform.kotlite.model.UnitValue
import com.sunnychung.lib.multiplatform.kotlite.model.toTypeNode
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

data class KatariBindings(
    val globals: Map<String, RuntimeValue>,
    val executionEnvironment: ExecutionEnvironment,
    val snapshotCodec: StateSnapshotCodec,
    val enumDefinitions: Map<String, KatariEnumDefinition> = emptyMap(),
    val importAliases: Map<String, String> = emptyMap(),
)

class NarrativeBindingsBuilder {
    private val executionEnvironment = ExecutionEnvironment()
    private var importExecutionEnvironmentFunctions = true
    private val narrativeCallables = mutableListOf<NarrativeCallable>()
    private val valueCodecs = mutableListOf<ValueCodec<out ValueSnapshot>>()
    private val globals = linkedMapOf<String, RuntimeValue>()
    private val enumDefinitions = linkedMapOf<String, KatariEnumDefinition>()
    private val importAliases = linkedMapOf<String, String>()
    private val importWildcards = mutableListOf<String>()
    private val hostTypeIdByClass = linkedMapOf<KClass<*>, String>()

    fun register(callable: NarrativeCallable): NarrativeBindingsBuilder = apply {
        narrativeCallables += callable
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

    fun immediateFunction(
        name: String,
        valueParameters: List<CustomFunctionParameter> = emptyList(),
        receiverType: String? = null,
        returnType: String = "Unit",
        typeParameters: List<TypeParameter> = emptyList(),
        parameterDefaults: List<RuntimeValue?> = emptyList(),
        execute: suspend (
            arguments: List<RuntimeValue>,
            context: NarrativeCallContext,
        ) -> RuntimeValue = { _, _ -> NullValue },
    ): NarrativeBindingsBuilder = apply {
        val resolvedParameterDefaults = resolveParameterDefaults(valueParameters, parameterDefaults)
        register(object : NarrativeCallable {
            override val id: String = name
            override val receiverType: String? = receiverType
            override val returnType: String = returnType
            override val typeParameters: List<TypeParameter> = typeParameters
            override val valueParameters: List<CustomFunctionParameter> = valueParameters
            override val parameterDefaults: List<RuntimeValue?> = resolvedParameterDefaults

            override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
                return NarrativeCallResult.Returned(execute(arguments, context))
            }

            override suspend fun resumeCall(
                arguments: List<RuntimeValue>,
                response: FunctionResponse?,
                context: NarrativeCallContext,
            ): NarrativeCallResult {
                throw IllegalStateException("Immediate function `$id` cannot be resumed because it never suspends")
            }

            override fun dispatch(
                arguments: List<RuntimeValue>,
                context: NarrativeCallDispatchContext,
                resume: (FunctionResponse?) -> Unit,
            ) {
                throw IllegalStateException("Immediate function `$id` cannot be dispatched because it never suspends")
            }
        })
    }

    fun immediateMemberFunction(
        dispatchReceiverType: String,
        name: String,
        valueParameters: List<CustomFunctionParameter> = emptyList(),
        returnType: String = "Unit",
        execute: suspend (
            arguments: List<RuntimeValue>,
            context: NarrativeCallContext,
        ) -> RuntimeValue = { _, _ -> NullValue },
    ): NarrativeBindingsBuilder = immediateFunction(
        name = name,
        valueParameters = valueParameters,
        receiverType = dispatchReceiverType,
        returnType = returnType,
        execute = execute,
    )

    fun suspendableFunction(
        name: String,
        valueParameters: List<CustomFunctionParameter> = emptyList(),
        receiverType: String? = null,
        returnType: String = "Unit",
        typeParameters: List<TypeParameter> = emptyList(),
        parameterDefaults: List<RuntimeValue?> = emptyList(),
        onStart: suspend (arguments: List<RuntimeValue>, context: NarrativeCallContext) -> Unit = { _, _ -> },
        onDispatch: (
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) -> Unit,
        onResume: suspend (
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ) -> RuntimeValue = { _, _, _ -> NullValue },
    ): NarrativeBindingsBuilder = apply {
        val resolvedParameterDefaults = resolveParameterDefaults(valueParameters, parameterDefaults)
        register(object : NarrativeCallable {
            override val id: String = name
            override val receiverType: String? = receiverType
            override val returnType: String = returnType
            override val typeParameters: List<TypeParameter> = typeParameters
            override val valueParameters: List<CustomFunctionParameter> = valueParameters
            override val parameterDefaults: List<RuntimeValue?> = resolvedParameterDefaults

            override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
                onStart(arguments, context)
                return NarrativeCallResult.Suspended
            }

            override suspend fun resumeCall(
                arguments: List<RuntimeValue>,
                response: FunctionResponse?,
                context: NarrativeCallContext,
            ): NarrativeCallResult {
                return NarrativeCallResult.Returned(onResume(arguments, response, context))
            }

            override fun dispatch(
                arguments: List<RuntimeValue>,
                context: NarrativeCallDispatchContext,
                resume: (FunctionResponse?) -> Unit,
            ) {
                onDispatch(arguments, context, resume)
            }
        })
    }

    fun registerBuiltinFunctions(host: NarrativeHost): NarrativeBindingsBuilder = apply {
        narrativeCallables += NarrativeBuiltinFunctions.definitions(host)
    }

    fun register(callables: Iterable<NarrativeCallable>): NarrativeBindingsBuilder = apply {
        narrativeCallables += callables
    }

    fun <T : Any> registerHostType(
        typeClass: KClass<T>,
        typeId: String = typeClass.qualifiedName ?: typeClass.simpleName!!,
        superTypeIds: List<String> = emptyList(),
    ): NarrativeBindingsBuilder = apply {
        hostTypeIdByClass[typeClass] = typeId
        registerNarrativeTypeIfNeeded(typeId, superTypeIds = superTypeIds)
    }

    fun <T : Enum<T>> registerEnum(
        typeClass: KClass<T>,
        typeId: String = typeClass.qualifiedName ?: typeClass.simpleName!!,
        values: List<T>,
    ): NarrativeBindingsBuilder = apply {
        hostTypeIdByClass[typeClass] = typeId
        val st = StateSnapshotCodec(executionEnvironment = executionEnvironment).symbolTable()
        enumDefinitions[typeId] = KatariEnumDefinition(
            typeId = typeId,
            entries = values.map { value ->
                NarrativeEnumValue(
                    typeId = typeId,
                    entryName = value.name,
                    ordinal = value.ordinal,
                    symbolTable = st,
                )
            },
        )
        registerNarrativeEnumSemanticSymbols(typeId, values.map { it.name })
    }

    fun <T : Any, S : ValueSnapshot> registerHostType(
        typeClass: KClass<T>,
        typeId: String = typeClass.qualifiedName ?: typeClass.simpleName!!,
        snapshotClass: KClass<S>,
        snapshotSerializer: KSerializer<S>,
        serialize: (T) -> S,
        deserialize: suspend (S, ValueRestoreContext) -> T,
    ): NarrativeBindingsBuilder = apply {
        registerHostType(typeClass, typeId)
        valueCodecs += object : ValueCodec<S> {
            override val typeId: String = typeId
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
        globals[name] = toRuntimeValue(value)
    }

    fun build(): KatariBindings {
        val codecRegistry = KatariValueCodecRegistry(valueCodecs)
        val explicitGlobalNames = globals.keys.toSet()
        val interpreter = KotliteInterpreter(
            filename = "<NarrativeBridge>",
            code = "",
            executionEnvironment = executionEnvironment,
        )
        val eeGlobalProperties = executionEnvironment.getGlobalProperties(interpreter.symbolTable())
        val eeGlobalPropertyNames = eeGlobalProperties.map { it.declaredName }.toSet()
        eeGlobalProperties.forEach { property ->
            if (property.declaredName !in globals) {
                val value = property.getter?.invoke(interpreter) ?: return@forEach
                globals[property.declaredName] = value
            }
        }
        val baseGlobals = globals.toMap()
        val importGlobals = importAliases.mapNotNull { (alias, target) ->
            baseGlobals[target]?.let { alias to it } ?: baseGlobals[target.substringAfterLast('.')]?.let { alias to it }
        }.toMap() + importWildcards.flatMap { prefix ->
            baseGlobals.mapNotNull { (name, value) ->
                name.removePrefix("$prefix.").takeIf { it != name && it.isNotBlank() }?.let { it to value }
            }
        }
        val normalizedGlobals = baseGlobals + importGlobals
        normalizedGlobals.forEach { (name, value) ->
            registerNarrativeTypeIfNeeded(value.narrativeTypeName())
            if (name in eeGlobalPropertyNames && name !in explicitGlobalNames && name !in importGlobals.keys) {
                return@forEach
            }
            executionEnvironment.registerGlobalProperty(
                GlobalProperty(
                    position = SourcePosition.BUILTIN,
                    declaredName = name,
                    type = value.narrativeTypeName(),
                    isMutable = false,
                    getter = { normalizedGlobals.getValue(name) },
                    setter = null,
                )
            )
        }

        val existingNarrativeIds = narrativeCallables.map { it.id }.toSet()
        val executionEnvironmentFunctionKeys = executionEnvironment
            .getBridgeableBuiltinFunctions(interpreter.symbolTable())
            .map { it.name to it.receiver?.name }
            .toSet()
        NarrativeBuiltinFunctions.definitions(NarrativeNoOpHost)
            .filter { it.id !in existingNarrativeIds }
            .filter { (it.id to it.receiverType?.narrativeBaseTypeId()) !in executionEnvironmentFunctionKeys }
            .forEach { narrativeCallables += it }

        narrativeCallables.forEach { callable ->
            callable.receiverType?.let { registerNarrativeTypeIfNeeded(it) }
            registerNarrativeTypeIfNeeded(callable.returnType)
            callable.valueParameters.forEach { registerNarrativeTypeIfNeeded(it.type) }
        }
        narrativeCallables.forEach { callable ->
            executionEnvironment.registerNarrativeCallable(callable)
        }
        if (importExecutionEnvironmentFunctions) {
            val declarations = executionEnvironment.getBridgeableBuiltinFunctions(interpreter.symbolTable())
            declarations.forEach { declaration ->
                val callable = BridgeFunctionCallable(declaration, interpreter)
                narrativeCallables += callable
                executionEnvironment.registerNarrativeCallable(callable)
            }

            val extensionProperties = executionEnvironment.getExtensionProperties(interpreter.symbolTable())
            extensionProperties.forEach { property ->
                property.toKatariDefinitions(interpreter).forEach {
                    narrativeCallables += it
                    executionEnvironment.registerNarrativeCallable(it)
                }
            }

            val classes = executionEnvironment.getBuiltinClasses(interpreter.symbolTable())
            classes.filter { it.isInstanceCreationAllowed }.forEach { clazz ->
                val name = clazz.fullQualifiedName.removeSuffix("?").removeSuffix(".Companion")
                if (narrativeCallables.none { it.id == name }) {
                    val ctorCallable = BridgeConstructorCallable(name, clazz, interpreter)
                    narrativeCallables += ctorCallable
                    executionEnvironment.registerNarrativeCallable(ctorCallable)
                }
            }
        }

        val enumCollectionDefs = NarrativeBuiltinFunctions.enumCollectionDefinitions()
            .filterNot { enumDef ->
                narrativeCallables.any { existing ->
                    existing.id == enumDef.id &&
                        existing.valueParameters.firstOrNull()?.type == enumDef.valueParameters.firstOrNull()?.type
                }
            }
        enumCollectionDefs.forEach {
            narrativeCallables += it
            executionEnvironment.registerNarrativeCallable(it)
        }

        return KatariBindings(
            globals = normalizedGlobals,
            executionEnvironment = executionEnvironment,
            snapshotCodec = StateSnapshotCodec(
                valueCodecs = codecRegistry,
                executionEnvironment = executionEnvironment,
            ),
            enumDefinitions = enumDefinitions,
            importAliases = importAliases,
        )
    }

    private fun registerNarrativeEnumSemanticSymbols(typeId: String, entryNames: List<String>) {
        registerNarrativeTypeIfNeeded(typeId, modifiers = setOf(ClassModifier.enum))
        executionEnvironment.registerExtensionProperty(
            ExtensionProperty(
                receiver = "$typeId.Companion",
                declaredName = "entries",
                type = "List<$typeId>",
                getter = { _, _, _ -> throw UnsupportedOperationException("Katari enum entries are compiled directly") },
            )
        )
        entryNames.forEach { entryName ->
            executionEnvironment.registerExtensionProperty(
                ExtensionProperty(
                    receiver = "$typeId.Companion",
                    declaredName = entryName,
                    type = typeId,
                    getter = { _, _, _ -> throw UnsupportedOperationException("Katari enum entries are compiled directly") },
                )
            )
        }
        executionEnvironment.registerFunction(
            CustomFunctionDefinition(
                position = SourcePosition.BUILTIN,
                receiverType = "$typeId.Companion",
                functionName = "valueOf",
                returnType = typeId,
                parameterTypes = listOf(CustomFunctionParameter("value", "String")),
                executable = { _, _, _, _ ->
                    throw UnsupportedOperationException("Katari enum valueOf is compiled directly")
                },
            )
        )
    }

    private fun registerNarrativeTypeIfNeeded(
        type: String,
        modifiers: Set<ClassModifier> = emptySet(),
        superTypeIds: List<String> = emptyList(),
    ) {
        val typeId = type.narrativeBaseTypeId() ?: return
        if (typeId in BUILTIN_NARRATIVE_TYPE_IDS) return
        if (executionEnvironment.findProvidedClass(typeId) != null) return
        executionEnvironment.registerClass(
            ProvidedClassDefinition(
                fullQualifiedName = typeId,
                typeParameters = emptyList(),
                isInstanceCreationAllowed = false,
                primaryConstructorParameters = emptyList(),
                constructInstance = { _, _, _ -> throw UnsupportedOperationException("Narrative type `$typeId` cannot be constructed by Kotlite") },
                modifiers = modifiers,
                superClassInvocationString = superTypeIds.firstOrNull()?.let { "$it()" },
                superInterfaceTypeNames = superTypeIds.drop(1),
                position = SourcePosition.BUILTIN,
            )
        )
    }

    private fun resolveParameterDefaults(
        valueParameters: List<CustomFunctionParameter>,
        parameterDefaults: List<RuntimeValue?>,
    ): List<RuntimeValue?> {
        if (parameterDefaults.isNotEmpty()) return parameterDefaults
        return valueParameters.map { parameter ->
            parameter.defaultValueExpression?.let { expression ->
                evalKotliteExpression(
                    filename = "<NarrativeDefault:${parameter.name}>",
                    code = expression,
                    executionEnvironment = executionEnvironment,
                )
            }
        }
    }

    private fun RuntimeValue.narrativeTypeName(): String {
        return when {
            this === NullValue -> "Any?"
            this is NarrativeHostValue -> typeId
            this is NarrativeEnumValue -> typeId
            this === UnitValue -> "Unit"
            else -> type().nameWithNullable
        }
    }

    private fun toRuntimeValue(value: Any?): RuntimeValue {
        val st = StateSnapshotCodec(executionEnvironment = executionEnvironment).symbolTable()
        return when (value) {
            null -> NullValue
            is RuntimeValue -> value
            is Boolean -> BooleanValue(value, st)
            is Int -> IntValue(value, st)
            is Double -> DoubleValue(value, st)
            is String -> StringValue(value, st)
            is Enum<*> -> {
                val typeId = hostTypeIdByClass[value::class]
                    ?: value::class.qualifiedName
                    ?: value::class.simpleName!!
                val definition = enumDefinitions[typeId]
                    ?: throw IllegalArgumentException(
                        "No Katari enum type is registered for `${value::class.qualifiedName}`. " +
                                "Register it with `registerEnum(...)` first."
                    )
                definition.entry(value.name)
            }
            else -> {
                val typeId = hostTypeIdByClass[value::class]
                    ?: value::class.qualifiedName
                    ?: value::class.simpleName!!
                NarrativeHostValue(typeId = typeId, value = value, symbolTable = st)
            }
        }
    }
}

fun NarrativeBindings(block: NarrativeBindingsBuilder.() -> Unit): KatariBindings {
    return NarrativeBindingsBuilder()
        .apply(block)
        .build()
}

private class BridgeFunctionCallable(
    val declaration: CustomFunctionDeclarationNode,
    val interpreter: Interpreter,
) : NarrativeCallable {
    override val id: String = declaration.name
    override val semanticFunctionDefinition: CustomFunctionDefinition? = null
    override val receiverType: String? = declaration.receiver?.name
    override val returnType: String = declaration.declaredReturnType?.name ?: "Unit"
    override val typeParameters: List<TypeParameter> = declaration.typeParameters.map {
        TypeParameter(it.name, it.typeUpperBound?.name)
    }
    override val valueParameters: List<CustomFunctionParameter> = declaration.valueParameters.map {
        CustomFunctionParameter(
            name = it.name,
            type = it.declaredType?.let { t -> buildTypeString(t) } ?: "Any",
            defaultValueExpression = it.defaultValue?.let { "default" },
            modifiers = if (it.modifiers.contains(FunctionValueParameterModifier.vararg)) setOf("vararg") else emptySet(),
        )
    }

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            val receiver = if (receiverType != null) arguments.firstOrNull() else null
            val valueArguments = if (receiverType != null) arguments.drop(1) else arguments
            val resolvedArgs = valueArguments.map { if (it === DefaultArgumentMarker) null else it }
            val typeArgs: Array<TypeNode> = if (declaration.typeParameters.isNotEmpty()) {
                val inferred = inferTypeArguments(declaration, receiver, resolvedArgs)
                Array(inferred.size) { inferred[it] }
            } else {
                emptyArray()
            }
            val args: Array<RuntimeValue?> = Array(resolvedArgs.size) { resolvedArgs[it] }
            val result = if (receiver != null) {
                interpreter.evalClassMemberAnyFunctionCall(
                    position = SourcePosition.NONE,
                    subject = receiver,
                    function = declaration,
                    arguments = args,
                    typeArguments = typeArgs,
                )
            } else {
                interpreter.evalFunctionCall(
                    arguments = args,
                    typeArguments = typeArgs,
                    callPosition = SourcePosition.NONE,
                    functionNode = declaration,
                    extraScopeParameters = emptyMap(),
                    extraTypeResolutions = emptyList(),
                ).result
            }
            return NarrativeCallResult.Returned(result)
        }

    private fun inferTypeArguments(
        declaration: FunctionDeclarationNode,
        receiver: RuntimeValue?,
        arguments: List<RuntimeValue?>,
    ): List<TypeNode> {
        val inferred = linkedMapOf<String, DataType>()
        declaration.receiver?.let { receiverType ->
            receiver?.let { collectTypeArguments(receiverType, it.type(), declaration.typeParameters, inferred) }
        }
        val parameterTypes = declaration.valueParameters.mapNotNull { it.declaredType }
        if (declaration.isVararg || declaration.valueParameters.firstOrNull()?.modifiers?.contains(FunctionValueParameterModifier.vararg) == true) {
            val parameterType = parameterTypes.singleOrNull()
            if (parameterType != null) {
                arguments.forEach { argument ->
                    argument?.let { collectTypeArguments(parameterType, it.type(), declaration.typeParameters, inferred) }
                }
            }
        } else {
            parameterTypes.zip(arguments).forEach { (parameterType, argument) ->
                argument?.let { collectTypeArguments(parameterType, it.type(), declaration.typeParameters, inferred) }
            }
        }
        return declaration.typeParameters.map { parameter ->
            val inferredType = inferred[parameter.name]
                ?: interpreter.symbolTable().assertToDataType(
                    parameter.typeUpperBound ?: TypeNode(SourcePosition.NONE, "Any", null, true)
                )
            inferredType.toTypeNode()
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
        val actualObjectType = actualType as? ObjectType ?: return
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

    override suspend fun resumeCall(
        arguments: List<RuntimeValue>,
        response: FunctionResponse?,
        context: NarrativeCallContext,
    ): NarrativeCallResult {
        throw IllegalStateException("Bridge function `$id` cannot be resumed because it never suspends")
    }

    override fun dispatch(
        arguments: List<RuntimeValue>,
        context: NarrativeCallDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        throw IllegalStateException("Bridge function `$id` cannot be dispatched because it never suspends")
    }
}

private class BridgeConstructorCallable(
    override val id: String,
    val clazz: ClassDefinition,
    val interpreter: Interpreter,
) : NarrativeCallable {
    override val semanticFunctionDefinition: CustomFunctionDefinition? = null
    override val receiverType: String? = null
    override val returnType: String = id
    override val typeParameters: List<TypeParameter> = emptyList()
    override val valueParameters: List<CustomFunctionParameter> = clazz.primaryConstructor?.parameters.orEmpty().map { param ->
        CustomFunctionParameter(
            name = param.parameter.name,
            type = param.parameter.declaredType?.name ?: "Any",
        )
    }

    override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
        val result = clazz.construct(
            interpreter = interpreter,
            callArguments = arguments.toTypedArray(),
            typeArguments = emptyArray(),
            callPosition = SourcePosition.NONE,
        )
        return NarrativeCallResult.Returned(result)
    }

    override suspend fun resumeCall(
        arguments: List<RuntimeValue>,
        response: FunctionResponse?,
        context: NarrativeCallContext,
    ): NarrativeCallResult {
        throw IllegalStateException("Constructor `$id` cannot be resumed")
    }

    override fun dispatch(
        arguments: List<RuntimeValue>,
        context: NarrativeCallDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    ) {
        throw IllegalStateException("Constructor `$id` cannot be dispatched")
    }
}

private fun ExtensionProperty.toKatariDefinitions(
    interpreter: com.sunnychung.lib.multiplatform.kotlite.Interpreter,
): List<NarrativeCallable> {
    val receiverTypeStr = receiver
    val valueTypeStr = type
    val typeParams = typeParameters

    return listOfNotNull(
        getter?.let { getterFn ->
            object : NarrativeCallable {
                override val id: String = declaredName
                override val semanticFunctionDefinition: CustomFunctionDefinition? = null
                override val receiverType: String? = receiverTypeStr
                override val returnType: String = valueTypeStr
                override val typeParameters: List<TypeParameter> = typeParams
                override val valueParameters: List<CustomFunctionParameter> = emptyList()

                override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
                    val result = getterFn(
                        interpreter,
                        arguments.first(),
                        emptyMap(),
                    )
                    return NarrativeCallResult.Returned(result)
                }

                override suspend fun resumeCall(
                    arguments: List<RuntimeValue>,
                    response: FunctionResponse?,
                    context: NarrativeCallContext,
                ): NarrativeCallResult = throw IllegalStateException("Property getter cannot be resumed")

                override fun dispatch(
                    arguments: List<RuntimeValue>,
                    context: NarrativeCallDispatchContext,
                    resume: (FunctionResponse?) -> Unit,
                ) = throw IllegalStateException("Property getter cannot be dispatched")
            }
        },
        setter?.let { setterFn ->
            object : NarrativeCallable {
                override val id: String = declaredName
                override val semanticFunctionDefinition: CustomFunctionDefinition? = null
                override val receiverType: String? = receiverTypeStr
                override val returnType: String = "Unit"
                override val typeParameters: List<TypeParameter> = typeParams
                override val valueParameters: List<CustomFunctionParameter> = listOf(
                    CustomFunctionParameter("value", valueTypeStr),
                )

                override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
                    setterFn(interpreter, arguments[0], arguments[1], emptyMap())
                    return NarrativeCallResult.Returned(NullValue)
                }

                override suspend fun resumeCall(
                    arguments: List<RuntimeValue>,
                    response: FunctionResponse?,
                    context: NarrativeCallContext,
                ): NarrativeCallResult = throw IllegalStateException("Property setter cannot be resumed")

                override fun dispatch(
                    arguments: List<RuntimeValue>,
                    context: NarrativeCallDispatchContext,
                    resume: (FunctionResponse?) -> Unit,
                ) = throw IllegalStateException("Property setter cannot be dispatched")
            }
        },
    )
}

private fun buildTypeString(type: com.sunnychung.lib.multiplatform.kotlite.model.TypeNode): String {
    if (type.arguments.isNullOrEmpty()) return type.name
    return "${type.name}<${type.arguments!!.joinToString(", ") { buildTypeString(it) }}>"
}

private val BUILTIN_NARRATIVE_TYPE_IDS = setOf(
    "Any",
    "Boolean",
    "Byte",
    "Char",
    "Class",
    "Double",
    "Float",
    "Function",
    "Int",
    "Long",
    "Nothing",
    "Short",
    "String",
    "Unit",
)

private fun String.narrativeBaseTypeId(): String? {
    val withoutNullability = removeSuffix("?")
    val withoutVararg = withoutNullability.removeSuffix("...")
    val base = withoutVararg.substringBefore('<').trim().removeSuffix(".Companion")
    if (base.isBlank()) return null
    if (base.length == 1 && base.first().isUpperCase()) return null
    return base
}
