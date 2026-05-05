package com.sunnychung.lib.multiplatform.kotlite.model

/**
 * ExecutionEnvironment is stateful. Need to pass the same ExecutionEnvironment instance into both
 * SemanticAnalyzer and Interpreter.
 */
class ExecutionEnvironment(
//    private val registrationFilter: BuiltinFunctionRegistrationFilter = BuiltinFunctionRegistrationFilter { _ -> true }
    private val functionRegistrationFilter: (CustomFunctionDefinition) -> Boolean = { true },
    private val extensionPropertyRegistrationFilter: (ExtensionProperty) -> Boolean = { true },
    private val globalPropertyRegistrationFilter: (GlobalProperty) -> Boolean = { true },
    private val classRegistrationFilter: (String) -> Boolean = { true },
) {
    private val builtinFunctions: MutableList<CustomFunctionDeclarationNode> = mutableListOf()
    private val narrativeSemanticFunctions: MutableSet<CustomFunctionDeclarationNode> = mutableSetOf()
    private val extensionProperties: MutableList<ExtensionProperty> = mutableListOf()
    private val globalProperties: MutableList<GlobalProperty> = mutableListOf()
    private val providedClasses: MutableList<ProvidedClassDefinition> = mutableListOf()
    private val initiallyProvidedClasses: MutableList<ProvidedClassDefinition> = mutableListOf()

    private val generatedMapping: MutableMap<MappingKey, AnalyzedMapping> = mutableMapOf()
    private val specialFunctionLookupCache: MutableMap<MappingKey, FunctionDeclarationNode> = mutableMapOf()

    private val narrativeCallables: MutableMap<String, MutableList<NarrativeCallable>> = mutableMapOf()

    init {
        registerInitClass(AnyClass.clazz)
        registerInitClass(ComparableInterface.interfaze)

        registerClass(PairClass.clazz)
        PairClass.properties.forEach {
            registerExtensionProperty(it)
        }

        registerInitClass(ThrowableValue.clazz)
        ThrowableValue.properties.forEach {
            registerExtensionProperty(it)
        }
        ThrowableValue.functions.forEach {
            registerFunction(it)
        }
        registerClass(ExceptionValue.clazz)
        registerClass(NullPointerExceptionValue.clazz)
        registerClass(TypeCastExceptionValue.clazz)

        registerClass(IteratorClass.clazz)
        IteratorClass.functions.forEach {
            registerFunction(it)
        }

        registerClass(IterableInterface.clazz)
        IterableInterface.functions.forEach {
            registerFunction(it)
        }

        registerClass(PrimitiveIteratorClass.clazz)
        PrimitiveIteratorClass.functions.forEach {
            registerFunction(it)
        }

        registerClass(PrimitiveIterableInterface.clazz)
        PrimitiveIterableInterface.functions.forEach {
            registerFunction(it)
        }

        registerClass(CollectionInterface.collectionClazz)
        registerClass(ListClass.clazz)
    }

    fun registerFunction(function: CustomFunctionDefinition) {
        if (functionRegistrationFilter(function)) {
            builtinFunctions += function.let {
                CustomFunctionDeclarationNode(it)
            }
        }
    }

    fun registerExtensionProperty(property: ExtensionProperty) {
        if (extensionPropertyRegistrationFilter(property)) {
            extensionProperties += property
        }
    }

    fun registerGlobalProperty(property: GlobalProperty) {
        if (globalPropertyRegistrationFilter(property)) {
            globalProperties += property
        }
    }

    fun registerClass(clazz: ProvidedClassDefinition) {
        if (classRegistrationFilter(clazz.fullQualifiedName)) {
            providedClasses += clazz
        }
    }

    fun registerInitClass(clazz: ProvidedClassDefinition) {
        if (classRegistrationFilter(clazz.fullQualifiedName)) {
            initiallyProvidedClasses += clazz
        }
    }

    fun getBuiltinFunctions(topmostSymbolTable: SymbolTable): List<CustomFunctionDeclarationNode> {
        return builtinFunctions.toList()
    }

    fun getBridgeableBuiltinFunctions(topmostSymbolTable: SymbolTable): List<CustomFunctionDeclarationNode> {
        return builtinFunctions.filterNot { it in narrativeSemanticFunctions }
    }

    fun getExtensionProperties(topmostSymbolTable: SymbolTable): List<ExtensionProperty> {
        return extensionProperties.toList()
    }

    fun getGlobalProperties(topmostSymbolTable: SymbolTable): List<GlobalProperty> {
        return globalProperties.toList()
    }

    fun getBuiltinClasses(topmostSymbolTable: SymbolTable): List<ClassDefinition> {
        return initiallyProvidedClasses.filter { classRegistrationFilter(it.fullQualifiedName) }
            .flatMap {
                listOf(
                    it.copyClassDefinition(),
                    it.copyNullableClassDefinition(),
                    it.copyCompanionClassDefinition(),
                )
            } +
                listOf("Int", "Short", "Float", "Double", "Long", "Boolean", "String", "Char", "Byte", "Unit", "Nothing", "Function", "Class").flatMap { className ->
                    if (!classRegistrationFilter(className)) return@flatMap emptyList()
                    fun createTypeParameters(typeName: String): List<TypeParameterNode> {
                        return when (typeName) {
                            "Class" -> listOf(
                                TypeParameterNode(
                                    SourcePosition.BUILTIN,
                                    "T",
                                    TypeNode(SourcePosition.NONE, "Any", null, false)
                                )
                            )

                            else -> emptyList()
                        }
                    }

                    val interfaces = when (className) {
                        in setOf("Int", "Short", "Float", "Double", "Long", "Boolean", "String", "Char") -> {
                            listOf(
                                TypeNode(
                                    position = SourcePosition.BUILTIN,
                                    name = "Comparable",
                                    arguments = listOf(
                                        TypeNode(
                                            position = SourcePosition.BUILTIN,
                                            name = className,
                                            arguments = null,
                                            isNullable = false,
                                        )
                                    ),
                                    isNullable = false,
                                ) to ComparableInterface.memberFunctions.map {
                                    CustomFunctionDeclarationNode(
                                        it.copy(
                                            modifiers = setOf(
                                                // Intentionally drop "operator" modifier to lessen performance penalty.
                                                // Otherwise, it won't pass LoopTest.
                                                // FunctionModifier.operator,
                                                FunctionModifier.open,
                                                FunctionModifier.override,
                                            ),
                                            parameterTypes = listOf(
                                                CustomFunctionParameter(name = "other", type = className)
                                            ),
                                        )
                                    )
                                }
                            )
                        }

                        else -> emptyList()
                    }
                    listOf(
                        ClassDefinition(
                            currentScope = topmostSymbolTable,
                            name = className,
                            modifiers = emptySet(),
                            typeParameters = createTypeParameters(className),
                            isInstanceCreationAllowed = false,
                            orderedInitializersAndPropertyDeclarations = emptyList(),
                            declarations = emptyList(),
                            rawMemberProperties = emptyList(),
                            memberFunctions = interfaces.flatMap { it.second },
                            superInterfaceTypes = interfaces.map { it.first },
                            primaryConstructor = null,
                        ),
                        ClassDefinition(
                            currentScope = topmostSymbolTable,
                            name = "$className?",
                            modifiers = emptySet(),
                            typeParameters = createTypeParameters(className),
                            isInstanceCreationAllowed = false,
                            orderedInitializersAndPropertyDeclarations = emptyList(),
                            declarations = emptyList(),
                            rawMemberProperties = emptyList(),
                            memberFunctions = emptyList(),
                            primaryConstructor = null,
                        ),
                        ClassDefinition(
                            currentScope = topmostSymbolTable,
                            name = "$className.Companion",
                            modifiers = emptySet(),
                            typeParameters = createTypeParameters(className),
                            isInstanceCreationAllowed = false,
                            orderedInitializersAndPropertyDeclarations = emptyList(),
                            declarations = emptyList(),
                            rawMemberProperties = emptyList(),
                            memberFunctions = emptyList(),
                            primaryConstructor = null,
                        ),
                    )
                } +
                providedClasses.filter { classRegistrationFilter(it.fullQualifiedName) }
                    .flatMap {
                        listOf(
                            it.copyClassDefinition(),
                            it.copyNullableClassDefinition(),
                            it.copyCompanionClassDefinition(),
                        )
                    }
    }

    internal fun registerGeneratedMapping(type: SymbolType, receiverType: String?, parentName: String? = null, name: String, transformedName: String) {
        val key = MappingKey(type = type, receiverType = receiverType, parentName = parentName, name = name)
        generatedMapping[key] = AnalyzedMapping(key = key, transformedName = transformedName)
    }

    internal fun findGeneratedMapping(type: SymbolType, receiverType: String?, parentName: String? = null, name: String): AnalyzedMapping {
        val key = MappingKey(type = type, receiverType = receiverType, name = name, parentName = parentName)
        return generatedMapping[key]
            ?: throw RuntimeException("$type ${receiverType?.let { "$it." }}${parentName?.let { "$it." }}$name is not analyzed")
    }

    internal fun registerSpecialFunction(type: SymbolType, receiverType: String?, parentName: String? = null, name: String, function: FunctionDeclarationNode) {
        val key = MappingKey(type = type, receiverType = receiverType, parentName = parentName, name = name)
        specialFunctionLookupCache[key] = function
    }

    internal fun findNullableSpecialFunction(type: SymbolType, receiverType: String?, parentName: String? = null, name: String): FunctionDeclarationNode? {
        val key = MappingKey(type = type, receiverType = receiverType, name = name, parentName = parentName)
        return specialFunctionLookupCache[key]
    }

    internal fun findSpecialFunction(type: SymbolType, receiverType: String?, parentName: String? = null, name: String): FunctionDeclarationNode {
        return findNullableSpecialFunction(
            type = type,
            receiverType = receiverType,
            parentName = parentName,
            name = name
        )
            ?: throw RuntimeException("Function cache of $type ${receiverType?.let { "$it." }}${parentName?.let { "$it." }}$name is not found")
    }


    fun install(module: LibraryModule) {
        module.classes.forEach {
            registerClass(it)
        }
        module.properties.forEach {
            registerExtensionProperty(it)
        }
        module.globalProperties.forEach {
            registerGlobalProperty(it)
        }
        module.functions.forEach {
            registerFunction(it)
        }
    }

    fun registerNarrativeCallable(callable: NarrativeCallable) {
        registerNarrativeCallableTypes(callable)
        narrativeCallables.getOrPut(callable.id) { mutableListOf() } += callable
        callable.semanticFunctionDefinition
            ?.takeIf { functionRegistrationFilter(it) }
            ?.let {
                CustomFunctionDeclarationNode(it).also { declaration ->
                    builtinFunctions += declaration
                    narrativeSemanticFunctions += declaration
                }
            }
    }

    private fun registerNarrativeCallableTypes(callable: NarrativeCallable) {
        callable.receiverType?.let { registerSyntheticNarrativeType(it) }
        registerSyntheticNarrativeType(callable.returnType)
        callable.valueParameters.forEach { registerSyntheticNarrativeType(it.type) }
    }

    private fun registerSyntheticNarrativeType(type: String) {
        val typeId = type.narrativeBaseTypeId() ?: return
        if (typeId in BUILTIN_NARRATIVE_TYPE_IDS) return
        if (findProvidedClass(typeId) != null) return
        registerClass(
            ProvidedClassDefinition(
                fullQualifiedName = typeId,
                typeParameters = emptyList(),
                isInstanceCreationAllowed = false,
                primaryConstructorParameters = emptyList(),
                constructInstance = { _, _, _ -> throw UnsupportedOperationException("Narrative type `$typeId` cannot be constructed by Kotlite") },
                position = SourcePosition.BUILTIN,
            )
        )
    }

    fun findProvidedClass(name: String): ClassDefinition? {
        return (providedClasses + initiallyProvidedClasses).firstOrNull { it.fullQualifiedName == name }
    }

    fun resolveNarrativeCallable(id: String, arguments: List<RuntimeValue>): NarrativeCallable {
        return resolveNarrativeCallableAndNormalize(id, arguments.mapIndexed { index, value ->
            NarrativeCallArgument(value = value)
        }).first
    }

    fun resolveNarrativeCallable(
        id: String,
        arguments: List<RuntimeValue>,
        argumentNames: List<String?>,
    ): Pair<NarrativeCallable, List<RuntimeValue>> {
        val argumentInfos = arguments.mapIndexed { index, value ->
            NarrativeCallArgument(name = argumentNames.getOrNull(index), value = value)
        }
        return resolveNarrativeCallableAndNormalize(id, argumentInfos)
    }

    private fun resolveNarrativeCallableAndNormalize(
        id: String,
        argumentInfos: List<NarrativeCallArgument>,
    ): Pair<NarrativeCallable, List<RuntimeValue>> {
        val candidates = narrativeCallables[id]
            ?: throw IllegalArgumentException("No narrative function is registered for id `$id`")
        val matched = candidates.mapNotNull { definition ->
            matchNarrativeSignature(definition, argumentInfos)?.let { match -> Triple(definition, match, match.normalizedArguments) }
        }
        val bestMatch = matched.minWithOrNull(
            compareBy<Triple<NarrativeCallable, NarrativeSignatureMatch, List<RuntimeValue>>> { it.second.totalDistance }
                .thenBy { it.second.anyMatches }
                .thenBy { it.second.defaultedArguments }
        )
            ?: throw IllegalArgumentException("No narrative function overload `$id` matches arguments: ${argumentInfos.map { it.value }}")
        val best = matched.filter { it.second == bestMatch.second }.map { it.first }
        require(best.size == 1) {
            "Narrative function call `$id` is ambiguous for arguments: ${argumentInfos.map { it.value }}. Candidates: ${
                best.joinToString { it.id }
            }"
        }
        return best.first() to bestMatch.third
    }

    private fun matchNarrativeSignature(
        callable: NarrativeCallable,
        arguments: List<NarrativeCallArgument>,
    ): NarrativeSignatureMatch? {
        val receiverOffset = if (callable.receiverType != null) 1 else 0
        val receiverArgument = if (callable.receiverType != null) arguments.firstOrNull() else null
        if (callable.receiverType != null && receiverArgument?.name != null) {
            return null
        }
        val valueArguments = arguments.drop(receiverOffset)
        val repeatedParameter = callable.valueParameters.lastOrNull()?.takeIf { it.isRepeated }
        val fixedParameters = callable.valueParameters.dropLast(if (repeatedParameter != null) 1 else 0)
        if (valueArguments.hasPositionalArgumentAfterNamedArgument()) {
            return null
        }
        val distances = mutableListOf<Int>()
        val normalizedArguments = mutableListOf<RuntimeValue>()
        callable.receiverType?.let {
            val receiver = receiverArgument?.value ?: return null
            distances += computeNarrativeTypeDistance(receiver, it) ?: return null
            normalizedArguments += receiver
        }
        val positionalArguments = valueArguments.takeWhile { it.name == null }.toMutableList()
        val namedArguments = valueArguments.drop(positionalArguments.size)
        val namedByName = linkedMapOf<String, NarrativeCallArgument>()
        namedArguments.forEach { argument ->
            val name = argument.name ?: return null
            if (namedByName.put(name, argument) != null) return null
        }
        fixedParameters.forEachIndexed { index, parameter ->
            val argument = if (positionalArguments.isNotEmpty()) {
                positionalArguments.removeAt(0)
            } else {
                namedByName.remove(parameter.name)
            }
            if (argument != null) {
                distances += computeNarrativeTypeDistance(argument.value, parameter.type) ?: return null
                normalizedArguments += argument.value
            } else if (parameter.defaultValueExpression != null) {
                normalizedArguments += callable.parameterDefaults.getOrNull(index) ?: DefaultArgumentMarker
            } else {
                return null
            }
        }
        if (repeatedParameter != null) {
            val parameter = repeatedParameter
            val repeatedType = parameter.type
            val repeatedArguments = mutableListOf<NarrativeCallArgument>()
            repeatedArguments += positionalArguments
            namedByName.remove(parameter.name)?.let { repeatedArguments += it }
            repeatedArguments.forEach { argument ->
                distances += computeNarrativeTypeDistance(argument.value, repeatedType) ?: return null
                normalizedArguments += argument.value
            }
            positionalArguments.clear()
        } else if (positionalArguments.isNotEmpty()) {
            return null
        }
        if (namedByName.isNotEmpty()) {
            return null
        }
        return NarrativeSignatureMatch(
            distances = distances,
            anyMatches = distances.count { it == NARRATIVE_ANY_DISTANCE },
            defaultedArguments = normalizedArguments.count { it === DefaultArgumentMarker },
            normalizedArguments = normalizedArguments,
        )
    }

    private fun computeNarrativeTypeDistance(value: RuntimeValue, expectedTypeId: String): Int? {
        if (value === DefaultArgumentMarker) return 0
        val cleanExpected = expectedTypeId.removeSuffix("?")
        val expectedBase = cleanExpected.replace(Regex("<.*>"), "")
        if (cleanExpected == "Any" || expectedBase == "Any") return NARRATIVE_ANY_DISTANCE
        if (Regex("^[A-Z]$").matches(cleanExpected)) return NARRATIVE_ANY_DISTANCE
        val valueType = value.type()
        val valueName = valueType.name
        if (valueName == cleanExpected || valueName == expectedBase) return 0
        if ((cleanExpected == "Double" || expectedBase == "Double") && valueName == "Int") return 1
        if ((cleanExpected == "Function" || expectedBase == "Function") && value is NarrativeLambdaValue) return 0
        if (value === NullValue) return 0
        if (valueType is ObjectType) {
            return findTypeDistance(valueType, cleanExpected)
        }
        return null
    }

    private fun findTypeDistance(fromType: ObjectType, toTypeId: String): Int? {
        val cleanTo = toTypeId.removeSuffix("?")
        val cleanToBase = cleanTo.replace(Regex("<.*>"), "")
        if (fromType.name == cleanTo || fromType.name == cleanToBase) return 0
        fromType.superTypes.firstOrNull { superType ->
            superType.name == cleanTo || superType.name.replace(Regex("<.*>"), "") == cleanToBase
        }?.let {
            return 1
        }
        val visited = mutableSetOf<String>()
        var frontier = listOf(fromType.name to 0)
        val allClasses = providedClasses + initiallyProvidedClasses
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Pair<String, Int>>()
            frontier.forEach { (typeName, distance) ->
                if (!visited.add(typeName)) return@forEach
                val baseName = typeName.replace(Regex("<.+>"), "")
                val clazz = fromType.clazz.currentScope?.findClass(typeName)?.first
                    ?: allClasses.firstOrNull { it.fullQualifiedName == baseName }
                    ?: return@forEach
                val parentIds = clazz.superInterfaces.map { it.fullQualifiedName } +
                    listOfNotNull(clazz.superClass?.fullQualifiedName) +
                    clazz.superInterfaceTypes.mapNotNull { typeNode ->
                        buildString {
                            append(typeNode.name)
                            if (typeNode.arguments != null) {
                                append('<')
                                append(typeNode.arguments!!.joinToString(", ") { it.name })
                                append('>')
                            }
                        }
                    } +
                    listOfNotNull(clazz.superClassInvocation?.function?.let {
                        (it as? TypeNode)?.name
                    })
                parentIds.forEach { parent ->
                    if (parent == cleanTo || parent.replace(Regex("<.*>"), "") == cleanToBase) return distance + 1
                    next += parent to distance + 1
                }
            }
            frontier = next
        }
        return null
    }

    private fun List<NarrativeCallArgument>.hasPositionalArgumentAfterNamedArgument(): Boolean {
        var hasNamed = false
        forEach { argument ->
            if (argument.name != null) {
                hasNamed = true
            } else if (hasNamed) {
                return true
            }
        }
        return false
    }

    private val CustomFunctionParameter.isRepeated: Boolean
        get() = modifiers.contains("vararg") || type == "Any..."

    internal data class MappingKey(val type: SymbolType, val receiverType: String?, val parentName: String? = null, val name: String)
    internal data class AnalyzedMapping(val key: MappingKey, val transformedName: String)

    internal enum class SymbolType {
        Function, ExtensionFunction, Property, ExtensionProperty, ValueParameter
    }

    companion object {
        private const val NARRATIVE_ANY_DISTANCE = 1000
    }
}

internal data class NarrativeCallArgument(
    val name: String? = null,
    val value: RuntimeValue,
)

internal data class NarrativeSignatureMatch(
    val distances: List<Int>,
    val anyMatches: Int,
    val defaultedArguments: Int,
    val normalizedArguments: List<RuntimeValue>,
) {
    val totalDistance: Int = distances.sum()
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
