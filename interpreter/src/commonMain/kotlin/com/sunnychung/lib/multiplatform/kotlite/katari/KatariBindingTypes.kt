package com.sunnychung.lib.multiplatform.kotlite.katari

data class KatariParameterType(
    val typeId: String,
    val isNullable: Boolean = false,
    val isRepeated: Boolean = false,
    val typeArguments: List<KatariParameterType> = emptyList(),
    val typeParameterName: String? = null,
    val upperBound: KatariParameterType? = null,
) {
    val displayName: String
        get() = buildString {
            append(typeParameterName ?: typeId)
            if (typeArguments.isNotEmpty()) {
                append(typeArguments.joinToString(prefix = "<", postfix = ">") { it.displayName })
            }
            if (isNullable) append("?")
            if (isRepeated) append("...")
        }

    fun nullable(): KatariParameterType = copy(isNullable = true)
    fun repeated(): KatariParameterType = copy(isRepeated = true)
}

data class KatariTypeParameter(
    val name: String,
    val upperBound: KatariParameterType = KatariTypes.Any,
)

data class KatariValueParameter(
    val name: String,
    val type: KatariParameterType,
    val defaultValue: KatariValue? = null,
    val hasDefault: Boolean = defaultValue != null,
) {
    val displayName: String
        get() = buildString {
            append(name)
            append(": ")
            append(type.displayName)
            if (hasDefault) append(" = ...")
        }
}

fun KatariParameterType.asValueParameter(
    name: String,
    defaultValue: KatariValue? = null,
    hasDefault: Boolean = defaultValue != null,
): KatariValueParameter {
    return KatariValueParameter(
        name = name,
        type = this,
        defaultValue = defaultValue,
        hasDefault = hasDefault,
    )
}

object KatariTypes {
    val Any = KatariParameterType("Any")
    val Unit = KatariParameterType("Unit")
    val Text = KatariParameterType("String")
    val Boolean = KatariParameterType("Boolean")
    val Int = KatariParameterType("Int")
    val Double = KatariParameterType("Double")
    val Function = KatariParameterType("Function")

    fun host(type: KatariType<out Any>): KatariParameterType = KatariParameterType(type.typeId)
    fun nullable(type: KatariParameterType): KatariParameterType = type.nullable()
    fun repeated(type: KatariParameterType): KatariParameterType = type.repeated()
    fun typeParameter(name: String, upperBound: KatariParameterType = Any): KatariParameterType =
        KatariParameterType(typeId = name, typeParameterName = name, upperBound = upperBound)
}

fun KatariType<out Any>.asParameterType(): KatariParameterType = KatariTypes.host(this)

data class KatariGlobalPropertyDefinition(
    val name: String,
    val type: KatariParameterType,
    val getter: (() -> KatariValue)? = null,
    val setter: ((KatariValue) -> Unit)? = null,
) {
    init {
        require(getter != null || setter != null) { "Global property `$name` must declare getter or setter" }
    }
}

data class KatariPropertyRegistry(
    private val globalsByName: Map<String, KatariGlobalPropertyDefinition> = emptyMap(),
) {
    constructor(globals: List<KatariGlobalPropertyDefinition>) : this(
        globals.associateBy { it.name },
    )

    fun hasGlobal(name: String): Boolean = name in globalsByName

    fun getGlobal(name: String): KatariValue? {
        val property = globalsByName[name] ?: return null
        return property.getter?.invoke()
            ?: throw IllegalArgumentException("Global property `$name` is write-only")
    }

    fun setGlobal(name: String, value: KatariValue): Unit? {
        val property = globalsByName[name] ?: return null
        property.setter?.invoke(value)
            ?: throw IllegalArgumentException("Global property `$name` is read-only")
        return Unit
    }
}
