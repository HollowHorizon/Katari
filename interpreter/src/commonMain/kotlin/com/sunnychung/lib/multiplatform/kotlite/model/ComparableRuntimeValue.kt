package com.sunnychung.lib.multiplatform.kotlite.model

import com.sunnychung.lib.multiplatform.kotlite.extension.fullClassName

sealed interface ComparableRuntimeValue<T, C : Any> : RuntimeValue, Comparable<ComparableRuntimeValue<T, C>> {
    fun coerceIn(range_: ClosedRange<ComparableRuntimeValue<T, C>>): ComparableRuntimeValue<T, C> {
        return if (this is NumberValue<*> && this is PrimitiveValue) {
            val value = this.value
            when(value) {
                is Byte -> ByteValue(value.coerceIn(range_ as ClosedRange<Byte>), symbolTable)
                is Int -> IntValue(value.coerceIn(range_ as IntRange), symbolTable)
                is Long -> LongValue(value.coerceIn(range_ as LongRange), symbolTable)
                is Float -> FloatValue(value.coerceIn(range_ as ClosedRange<Float>), symbolTable)
                is Double -> DoubleValue(value.coerceIn(range_ as ClosedRange<Double>), symbolTable)
                is Short -> ShortValue(value.coerceIn(range_ as ClosedRange<Short>), symbolTable)
                else -> error("Unupported type $value")
            } as ComparableRuntimeValue<T, C>
        } else {
            when {
                this < range_.start -> range_.start
                this > range_.endInclusive -> range_.endInclusive
                else -> this
            }
        }
    }
}

sealed interface ComparableRuntimeValueHolder<T, C : Any> : ComparableRuntimeValue<T, C>, KotlinValueHolder<T> {
    override val value: T

    override fun compareTo(other: ComparableRuntimeValue<T, C>): Int {
        if (other !is ComparableRuntimeValueHolder<T, C>) {
            throw RuntimeException("Compare target is not a ComparableRuntimeValueHolder but ${other::class.fullClassName}")
        }
        return (value as Comparable<C>).compareTo(other.value as C)
    }
}
