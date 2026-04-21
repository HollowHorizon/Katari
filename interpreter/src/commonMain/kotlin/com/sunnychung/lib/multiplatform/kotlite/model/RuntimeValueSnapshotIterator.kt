package com.sunnychung.lib.multiplatform.kotlite.model

class RuntimeValueSnapshotIterator(
    private val elements: List<RuntimeValue>,
    private var index: Int = 0,
) : Iterator<RuntimeValue> {
    override fun hasNext(): Boolean = index < elements.size

    override fun next(): RuntimeValue {
        if (!hasNext()) {
            throw NoSuchElementException()
        }
        return elements[index++]
    }

    fun remainingElements(): List<RuntimeValue> = elements.subList(index, elements.size)
}
