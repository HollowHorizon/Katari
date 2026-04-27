package com.sunnychung.lib.multiplatform.kotlite.katari

data class KatariParameterType(
    val typeId: String,
)

object KatariTypes {
    val Any = KatariParameterType("Any")
    val Text = KatariParameterType("String")
    val Boolean = KatariParameterType("Boolean")
    val Int = KatariParameterType("Int")
    val Double = KatariParameterType("Double")
    val Function = KatariParameterType("Function")

    fun host(type: KatariType<out Any>): KatariParameterType = KatariParameterType(type.typeId)
    fun nullable(type: KatariParameterType): KatariParameterType = KatariParameterType("${type.typeId}?")
}

fun KatariType<out Any>.asParameterType(): KatariParameterType = KatariTypes.host(this)

data class KatariGlobalPropertyDefinition(
    val name: String,
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
