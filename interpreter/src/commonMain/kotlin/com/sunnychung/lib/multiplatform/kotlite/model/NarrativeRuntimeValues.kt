package com.sunnychung.lib.multiplatform.kotlite.model

class NarrativeLambdaValue(
    val lambdaId: String,
    private val symbolTable: SymbolTable,
) : RuntimeValue {
    override fun type(): DataType = FunctionType(
        arguments = emptyList(),
        returnType = AnyType(),
        isNullable = false,
    )
    override fun convertToString(): String = "Lambda($lambdaId)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NarrativeLambdaValue) return false
        return lambdaId == other.lambdaId
    }

    override fun hashCode(): Int = lambdaId.hashCode()
}

private fun syntheticEnumClass(typeId: String, symbolTable: SymbolTable): ClassDefinition {
    return symbolTable.findClass(typeId)?.first ?: ClassDefinition(
        currentScope = symbolTable,
        name = typeId,
        modifiers = emptySet(),
        typeParameters = emptyList(),
        isInstanceCreationAllowed = false,
        orderedInitializersAndPropertyDeclarations = emptyList(),
        declarations = emptyList(),
        rawMemberProperties = emptyList(),
        memberFunctions = emptyList(),
        primaryConstructor = null,
    )
}

class NarrativeEnumValue(
    val typeId: String,
    val entryName: String,
    val ordinal: Int,
    val properties: Map<String, RuntimeValue> = emptyMap(),
    private val symbolTable: SymbolTable,
) : RuntimeValue {
    private val cachedType: ObjectType by lazy {
        ObjectType(
            clazz = syntheticEnumClass(typeId, symbolTable),
            arguments = emptyList(),
            isNullable = false,
            superTypes = emptyList(),
        )
    }

    override fun type(): DataType = cachedType

    override fun convertToString(): String = entryName

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NarrativeEnumValue) return false
        return typeId == other.typeId && entryName == other.entryName
    }

    override fun hashCode(): Int {
        var result = typeId.hashCode()
        result = 31 * result + entryName.hashCode()
        return result
    }
}

class NarrativeEnumEntriesValue(
    val typeId: String,
    val entries: List<NarrativeEnumValue>,
    private val symbolTable: SymbolTable,
) : RuntimeValue {
    private val cachedType: ObjectType by lazy {
        ObjectType(
            clazz = syntheticEnumClass(typeId, symbolTable),
            arguments = emptyList(),
            isNullable = false,
            superTypes = emptyList(),
        )
    }

    override fun type(): DataType = ObjectType(
        clazz = syntheticEnumClass("__katari_enum_entries", symbolTable),
        arguments = emptyList(),
        isNullable = false,
        superTypes = emptyList(),
    )

    override fun convertToString(): String = entries.joinToString(prefix = "[", postfix = "]") { it.entryName }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NarrativeEnumEntriesValue) return false
        return typeId == other.typeId && entries == other.entries
    }

    override fun hashCode(): Int {
        var result = typeId.hashCode()
        result = 31 * result + entries.hashCode()
        return result
    }
}

class NarrativeHostValue(
    val typeId: String,
    override val value: Any,
    private val symbolTable: SymbolTable,
) : RuntimeValue, KotlinValueHolder<Any> {
    override fun type(): DataType {
        return symbolTable.findClass(typeId)?.first?.let { clazz ->
            symbolTable.resolveObjectType(clazz, typeArguments = emptyList(), isNullable = false)
        } ?: ObjectType(
            clazz = syntheticEnumClass(typeId, symbolTable),
            arguments = emptyList(),
            isNullable = false,
            superTypes = emptyList(),
        )
    }

    override fun convertToString(): String = value.toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NarrativeHostValue) return false
        return typeId == other.typeId && value === other.value
    }

    override fun hashCode(): Int {
        var result = typeId.hashCode()
        result = 31 * result + (value?.hashCode() ?: 0)
        return result
    }
}

internal class EnumEntriesIteratorValue(
    val entries: List<NarrativeEnumValue>,
    var index: Int = 0,
)

class KatariTaskValue(
    val taskId: String,
    val entryPointer: Int,
    val rootFrameId: Int,
    val capturedVariables: Map<String, RuntimeValue>,
    var started: Boolean,
    private val symbolTable: SymbolTable,
) : RuntimeValue {
    override fun type(): DataType = ObjectType(
        clazz = syntheticEnumClass(KATARI_TASK_TYPE_ID, symbolTable),
        arguments = emptyList(),
        isNullable = false,
        superTypes = emptyList(),
    )

    override fun convertToString(): String = "KatariTask($taskId)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KatariTaskValue) return false
        return taskId == other.taskId
    }

    override fun hashCode(): Int = taskId.hashCode()
}

const val KATARI_TASK_TYPE_ID: String = "KatariTask"
