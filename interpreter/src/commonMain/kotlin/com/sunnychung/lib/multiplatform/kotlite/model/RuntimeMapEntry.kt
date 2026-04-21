package com.sunnychung.lib.multiplatform.kotlite.model

data class RuntimeMapEntry(
    override val key: RuntimeValue,
    override val value: RuntimeValue,
) : Map.Entry<RuntimeValue, RuntimeValue>
