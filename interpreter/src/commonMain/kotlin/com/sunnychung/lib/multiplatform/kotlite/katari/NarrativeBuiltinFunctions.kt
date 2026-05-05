package com.sunnychung.lib.multiplatform.kotlite.katari

import com.sunnychung.lib.multiplatform.kotlite.model.BooleanValue
import com.sunnychung.lib.multiplatform.kotlite.model.CustomFunctionParameter
import com.sunnychung.lib.multiplatform.kotlite.model.DefaultArgumentMarker
import com.sunnychung.lib.multiplatform.kotlite.model.DoubleValue
import com.sunnychung.lib.multiplatform.kotlite.model.EnumEntriesIteratorValue
import com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment
import com.sunnychung.lib.multiplatform.kotlite.model.FunctionResponse
import com.sunnychung.lib.multiplatform.kotlite.model.IntValue
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallContext
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallDispatchContext
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallResult
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeCallable
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeEnumEntriesValue
import com.sunnychung.lib.multiplatform.kotlite.model.NarrativeHostValue
import com.sunnychung.lib.multiplatform.kotlite.model.NullValue
import com.sunnychung.lib.multiplatform.kotlite.model.RuntimeValue
import com.sunnychung.lib.multiplatform.kotlite.model.StringValue
import com.sunnychung.lib.multiplatform.kotlite.model.TypeParameter

interface NarrativeHost {
    fun narrate(text: String, resume: () -> Unit)
    fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit)
    fun readLine(question: String, resume: (String) -> Unit)
}

data object NarrativeNoOpHost : NarrativeHost {
    override fun narrate(text: String, resume: () -> Unit) = resume()
    override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
        resume(options.firstOrNull { it.enabled }?.id ?: "")
    }
    override fun readLine(question: String, resume: (String) -> Unit) = resume("")
}

object NarrativeBuiltinFunctions {

    fun environment(host: NarrativeHost): ExecutionEnvironment {
        val env = ExecutionEnvironment()
        definitions(host).forEach { env.registerNarrativeCallable(it) }
        return env
    }

    fun definitions(host: NarrativeHost): List<NarrativeCallable> {
        return listOf(
            NarrateFunction(host),
            ChooseFunction(host),
            ChooseIndexedFunction(host),
            ChooseExhaustibleFunction(host),
            ChoiceOptionFunction,
            ReadLineFunction(host),
        ) + enumCollectionDefinitions()
    }

    internal fun enumCollectionDefinitions(): List<NarrativeCallable> {
        return listOf(
            EnumEntriesIteratorFunction,
            EnumEntriesHasNextFunction,
            EnumEntriesNextFunction,
        )
    }

    private class NarrateFunction(private val host: NarrativeHost) : NarrativeCallable {
        override val id: String = "narrate"
        override val receiverType: String? = null
        override val returnType: String = "Unit"
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("text", "Any"),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            require(arguments.size == 1) { "`narrate` expects a single text argument" }
            return NarrativeCallResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult {
            require(arguments.size == 1) { "`narrate` expects a single text argument" }
            require(response == null || response == FunctionResponse.Ack) {
                "`narrate` only accepts acknowledgement"
            }
            return NarrativeCallResult.Returned(NullValue)
        }

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.narrate(arguments.single().asStringCompatible()) {
                resume(FunctionResponse.Ack)
            }
        }
    }

    private class ChooseFunction(private val host: NarrativeHost) : NarrativeCallable {
        override val id: String = "choose"
        override val receiverType: String? = null
        override val returnType: String = "String"
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("options", "Any?", modifiers = setOf("vararg")),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            require(arguments.isNotEmpty()) { "`choose` expects at least one option" }
            return NarrativeCallResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult {
            val selection = response as? FunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`choose` expects a choice selection response")
            val options = arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)
                .filter { it.enabled }
                .map { it.id }
            require(selection.optionId in options) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeCallResult.Returned(StringValue(selection.optionId, runtimeSymbolTable()))
        }

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)) { optionId ->
                resume(FunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ChooseIndexedFunction(private val host: NarrativeHost) : NarrativeCallable {
        override val id: String = "chooseIndexed"
        override val receiverType: String? = null
        override val returnType: String = "String"
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("options", "Any?", modifiers = setOf("vararg")),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            require(arguments.isNotEmpty()) { "`chooseIndexed` expects at least one option" }
            return NarrativeCallResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult {
            val selection = response as? FunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`chooseIndexed` expects a choice selection response")
            val options = arguments.toChoiceOptionsWithSourceIndex(useTextAsId = true, includeDisabled = true)
            val selected = options.firstOrNull { indexed ->
                indexed.option.enabled && indexed.option.id == selection.optionId
            }
            require(selected != null) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeCallResult.Returned(StringValue(selected.sourceIndex.toString(), runtimeSymbolTable()))
        }

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)) { optionId ->
                resume(FunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ChooseExhaustibleFunction(private val host: NarrativeHost) : NarrativeCallable {
        override val id: String = "chooseExhaustible"
        override val receiverType: String? = null
        override val returnType: String = "String"
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("options", "Any?", modifiers = setOf("vararg")),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            require(arguments.any { it != NullValue }) { "`chooseExhaustible` expects at least one non-null option" }
            return NarrativeCallResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult {
            val selection = response as? FunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`chooseExhaustible` expects a choice selection response")
            val options = arguments.toChoiceOptions(useTextAsId = true, includeDisabled = false)
                .map { it.id }
            require(selection.optionId in options) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeCallResult.Returned(StringValue(selection.optionId, runtimeSymbolTable()))
        }

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = false)) { optionId ->
                resume(FunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private data object ChoiceOptionFunction : NarrativeCallable {
        override val id: String = "choiceOption"
        override val receiverType: String? = null
        override val returnType: String = CHOICE_OPTION_TYPE_ID
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("text", "String"),
            CustomFunctionParameter("visible", "Boolean"),
            CustomFunctionParameter("enabled", "Boolean"),
            CustomFunctionParameter("disabledText", "String?"),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            require(arguments.size == 4) { "`choiceOption` expects (text, visible, enabled, disabledTextOrNull)" }
            val text = arguments[0].asStringCompatible()
            val visible = arguments[1].asBoolean()
            val enabled = arguments[2].asBoolean()
            val disabledText = arguments[3].asNullableText()
            return NarrativeCallResult.Returned(
                NarrativeHostValue(
                    typeId = CHOICE_OPTION_TYPE_ID,
                    value = ChoiceOptionValue(
                        id = text,
                        text = text,
                        visible = visible,
                        enabled = enabled,
                        disabledText = disabledText,
                    ),
                    symbolTable = runtimeSymbolTable(),
                )
            )
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult {
            throw IllegalStateException("`choiceOption` cannot be resumed because it never suspends")
        }

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            throw IllegalStateException("`choiceOption` cannot be dispatched because it never suspends")
        }
    }

    private class ReadLineFunction(private val host: NarrativeHost) : NarrativeCallable {
        override val id: String = "readLine"
        override val receiverType: String? = null
        override val returnType: String = "String"
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("question", "String"),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            require(arguments.size == 1) { "`readLine` expects a single text question argument" }
            return NarrativeCallResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult {
            require(arguments.size == 1) { "`readLine` expects a single text question argument" }
            val line = response as? NarrativeTextResponse
                ?: throw IllegalArgumentException("`readLine` expects a text response")
            return NarrativeCallResult.Returned(StringValue(line.text, runtimeSymbolTable()))
        }

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.readLine(arguments.single().asStringCompatible()) { line ->
                resume(NarrativeTextResponse(line))
            }
        }
    }

    private data object EnumEntriesIteratorFunction : NarrativeCallable {
        override val id: String = "iterator"
        override val receiverType: String? = null
        override val returnType: String = KATARI_ENUM_ENTRIES_ITERATOR_TYPE_ID
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("entries", KATARI_ENUM_ENTRIES_TYPE_ID),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            val entries = arguments.single() as? NarrativeEnumEntriesValue
                ?: throw IllegalArgumentException("Enum entries iterator expects enum entries")
            return NarrativeCallResult.Returned(
                NarrativeHostValue(
                    typeId = KATARI_ENUM_ENTRIES_ITERATOR_TYPE_ID,
                    value = EnumEntriesIteratorValue(entries.entries),
                    symbolTable = runtimeSymbolTable(),
                )
            )
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult = throw IllegalStateException("Enum entries iterator cannot be resumed")

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) = throw IllegalStateException("Enum entries iterator cannot be dispatched")
    }

    private data object EnumEntriesHasNextFunction : NarrativeCallable {
        override val id: String = "hasNext"
        override val receiverType: String? = null
        override val returnType: String = "Boolean"
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("iterator", KATARI_ENUM_ENTRIES_ITERATOR_TYPE_ID),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            val iterator = arguments.single().enumEntriesIterator("hasNext")
            return NarrativeCallResult.Returned(BooleanValue(iterator.index < iterator.entries.size, runtimeSymbolTable()))
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult = throw IllegalStateException("Enum entries hasNext cannot be resumed")

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) = throw IllegalStateException("Enum entries hasNext cannot be dispatched")
    }

    private data object EnumEntriesNextFunction : NarrativeCallable {
        override val id: String = "next"
        override val receiverType: String? = null
        override val returnType: String = "Any"
        override val typeParameters: List<TypeParameter> = emptyList()
        override val valueParameters: List<CustomFunctionParameter> = listOf(
            CustomFunctionParameter("iterator", KATARI_ENUM_ENTRIES_ITERATOR_TYPE_ID),
        )

        override suspend fun startCall(arguments: List<RuntimeValue>, context: NarrativeCallContext): NarrativeCallResult {
            val iterator = arguments.single().enumEntriesIterator("next")
            require(iterator.index < iterator.entries.size) { "Enum entries iterator has no next value" }
            return NarrativeCallResult.Returned(iterator.entries[iterator.index++])
        }

        override suspend fun resumeCall(
            arguments: List<RuntimeValue>,
            response: FunctionResponse?,
            context: NarrativeCallContext,
        ): NarrativeCallResult = throw IllegalStateException("Enum entries next cannot be resumed")

        override fun dispatch(
            arguments: List<RuntimeValue>,
            context: NarrativeCallDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) = throw IllegalStateException("Enum entries next cannot be dispatched")
    }
}

data class NarrativeTextResponse(
    val text: String,
) : FunctionResponse

internal const val CHOICE_OPTION_TYPE_ID = "__choice_option"

internal data class ChoiceOptionValue(
    val id: String,
    val text: String,
    val visible: Boolean,
    val enabled: Boolean,
    val disabledText: String?,
)

private fun RuntimeValue.enumEntriesIterator(functionName: String): EnumEntriesIteratorValue {
    val hostObject = this as? NarrativeHostValue
        ?: throw IllegalArgumentException("Enum entries `$functionName` expects iterator receiver")
    require(hostObject.typeId == KATARI_ENUM_ENTRIES_ITERATOR_TYPE_ID) {
        "Enum entries `$functionName` expects iterator receiver, got `${hostObject.typeId}`"
    }
    return hostObject.value as? EnumEntriesIteratorValue
        ?: throw IllegalArgumentException("Enum entries iterator receiver is corrupted")
}

private fun RuntimeValue.asStringCompatible(): String {
    return convertToString()
}

private fun RuntimeValue.asBoolean(): Boolean {
    return when (this) {
        is BooleanValue -> value
        else -> throw IllegalArgumentException("Expected boolean value but got $this")
    }
}

private fun RuntimeValue.asNullableText(): String? {
    return when (this) {
        NullValue -> null
        is StringValue -> value
        else -> throw IllegalArgumentException("Expected nullable text value but got $this")
    }
}

private fun List<RuntimeValue>.toChoiceOptions(
    useTextAsId: Boolean,
    includeDisabled: Boolean,
): List<ChoiceOptionSnapshot> {
    return toChoiceOptionsWithSourceIndex(
        useTextAsId = useTextAsId,
        includeDisabled = includeDisabled,
    ).map { it.option }
}

private fun List<RuntimeValue>.toChoiceOptionsWithSourceIndex(
    useTextAsId: Boolean,
    includeDisabled: Boolean,
): List<IndexedChoiceOptionSnapshot> {
    return mapIndexedNotNull { index, value ->
        val predefinedOption = value.toChoiceOptionOrNull(
            useTextAsId = useTextAsId,
            index = index,
            includeDisabled = includeDisabled,
        )
        if (predefinedOption != null) {
            return@mapIndexedNotNull IndexedChoiceOptionSnapshot(index, predefinedOption)
        }
        if (value is NarrativeHostValue && value.typeId == CHOICE_OPTION_TYPE_ID) {
            return@mapIndexedNotNull null
        }
        when (value) {
            NullValue -> if (includeDisabled) {
                IndexedChoiceOptionSnapshot(
                    sourceIndex = index,
                    option = ChoiceOptionSnapshot(
                        id = "__disabled_$index",
                        text = "[Unavailable option]",
                        target = -1,
                        enabled = false,
                    )
                )
            } else {
                null
            }
            else -> {
                val text = value.asStringCompatible()
                IndexedChoiceOptionSnapshot(
                    sourceIndex = index,
                    option = ChoiceOptionSnapshot(
                        id = if (useTextAsId) text else index.toString(),
                        text = text,
                        target = -1,
                        enabled = true,
                    )
                )
            }
        }
    }
}

private fun RuntimeValue.toChoiceOptionOrNull(
    useTextAsId: Boolean,
    index: Int,
    includeDisabled: Boolean,
): ChoiceOptionSnapshot? {
    val hostValue = this as? NarrativeHostValue ?: return null
    if (hostValue.typeId != CHOICE_OPTION_TYPE_ID) return null
    val option = hostValue.value as? ChoiceOptionValue
        ?: throw IllegalArgumentException("Unexpected host value type for `$CHOICE_OPTION_TYPE_ID`")
    if (!option.visible) return null
    if (!option.enabled && !includeDisabled) return null
    return ChoiceOptionSnapshot(
        id = if (useTextAsId) option.id else index.toString(),
        text = if (option.enabled) option.text else (option.disabledText ?: option.text),
        target = -1,
        enabled = option.enabled,
    )
}

private data class IndexedChoiceOptionSnapshot(
    val sourceIndex: Int,
    val option: ChoiceOptionSnapshot,
)

private fun runtimeSymbolTable() = com.sunnychung.lib.multiplatform.kotlite.KotliteInterpreter(
    filename = "<NarrativeBuiltin>",
    code = "",
    executionEnvironment = com.sunnychung.lib.multiplatform.kotlite.model.ExecutionEnvironment(),
).symbolTable()
