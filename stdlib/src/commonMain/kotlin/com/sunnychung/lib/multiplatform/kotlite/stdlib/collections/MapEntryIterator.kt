package com.sunnychung.lib.multiplatform.kotlite.stdlib.collections

import com.sunnychung.lib.multiplatform.kotlite.model.DataType
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValueSnapshotIterator
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.SymbolTable

internal fun <K : RuntimeValue, V : RuntimeValue> Iterator<Map.Entry<K, V>>.wrap(keyType: DataType, valueType: DataType, symbolTable: SymbolTable) =
    RuntimeValueSnapshotIterator(
        this.asSequence().map { entry ->
            MapEntryValue(entry, keyType, valueType, symbolTable)
        }.toList()
    )

