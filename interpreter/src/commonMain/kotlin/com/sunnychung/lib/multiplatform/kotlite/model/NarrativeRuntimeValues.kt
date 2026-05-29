package com.sunnychung.lib.multiplatform.kotlite.model

class NarrativeLambdaValue(
    val lambdaId: String,
    val capturedVariables: Map<String, RuntimeValue> = emptyMap(),
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
        return lambdaId == other.lambdaId && capturedVariables == other.capturedVariables
    }

    override fun hashCode(): Int {
        var result = lambdaId.hashCode()
        result = 31 * result + capturedVariables.hashCode()
        return result
    }
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

private fun syntheticDataClass(typeId: String, symbolTable: SymbolTable): ClassDefinition {
    return symbolTable.findClass(typeId)?.first ?: ClassDefinition(
        currentScope = symbolTable,
        name = typeId,
        fullQualifiedName = typeId,
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

sealed interface KatariDataValue : RuntimeValue

data class XmlAttributeValue(
    val name: String,
    val value: RuntimeValue,
)

class XmlValue(
    val name: String,
    val attributes: List<XmlAttributeValue>,
    val children: List<XmlValue>,
    private val symbolTable: SymbolTable,
) : KatariDataValue {
    override fun type(): DataType = ObjectType(
        clazz = syntheticDataClass(XML_VALUE_TYPE_ID, symbolTable),
        arguments = emptyList(),
        isNullable = false,
        superTypes = listOf(AnyType()),
    )

    override fun convertToString(): String {
        if (name == XML_TEXT_NODE_NAME) {
            return attributes.firstOrNull { it.name == XML_TEXT_VALUE_ATTRIBUTE }?.value?.convertToString().orEmpty()
        }
        val attrs = attributes.joinToString("") { " ${it.name}=\"${it.value.convertToString()}\"" }
        if (children.isEmpty()) return "<$name$attrs />"
        return "<$name$attrs>${children.joinToString("") { it.convertToString() }}</$name>"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is XmlValue) return false
        return name == other.name && attributes == other.attributes && children == other.children
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + attributes.hashCode()
        result = 31 * result + children.hashCode()
        return result
    }
}

class StructValue(
    val fields: Map<String, RuntimeValue>,
    private val symbolTable: SymbolTable,
) : KatariDataValue {
    override fun type(): DataType = ObjectType(
        clazz = syntheticDataClass(STRUCT_VALUE_TYPE_ID, symbolTable),
        arguments = emptyList(),
        isNullable = false,
        superTypes = listOf(AnyType()),
    )

    override fun convertToString(): String {
        return fields.entries.joinToString(prefix = "struct {", postfix = "}") { (key, value) ->
            "$key: ${value.convertToString()}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StructValue) return false
        return fields == other.fields
    }

    override fun hashCode(): Int = fields.hashCode()
}

class StructArrayValue(
    val elements: List<RuntimeValue>,
    private val symbolTable: SymbolTable,
) : KatariDataValue {
    override fun type(): DataType = ObjectType(
        clazz = syntheticDataClass(STRUCT_ARRAY_VALUE_TYPE_ID, symbolTable),
        arguments = emptyList(),
        isNullable = false,
        superTypes = listOf(AnyType()),
    )

    override fun convertToString(): String {
        return elements.joinToString(prefix = "[", postfix = "]") { it.convertToString() }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StructArrayValue) return false
        return elements == other.elements
    }

    override fun hashCode(): Int = elements.hashCode()
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
const val XML_VALUE_TYPE_ID: String = "XmlValue"
const val XML_TEXT_NODE_NAME: String = "#text"
const val XML_TEXT_VALUE_ATTRIBUTE: String = "value"
const val STRUCT_VALUE_TYPE_ID: String = "StructValue"
const val STRUCT_ARRAY_VALUE_TYPE_ID: String = "StructArrayValue"
