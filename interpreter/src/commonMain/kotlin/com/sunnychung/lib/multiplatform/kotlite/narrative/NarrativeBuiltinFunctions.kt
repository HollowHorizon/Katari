package com.sunnychung.lib.multiplatform.kotlite.narrative

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

    fun registry(host: NarrativeHost): NarrativeFunctionRegistry {
        return NarrativeFunctionRegistry(definitions(host))
    }

    fun definitions(host: NarrativeHost): List<NarrativeFunctionDefinition> {
        return listOf(
            NarrateFunction(host),
            ChooseFunction(host),
            ChooseIndexedFunction(host),
            ChooseExhaustibleFunction(host),
            ChoiceOptionFunction,
            ReadLineFunction(host)
        )
    }

    private class NarrateFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "narrate"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.size == 1) { "`narrate` expects a single text argument" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            require(arguments.size == 1) { "`narrate` expects a single text argument" }
            require(response == null || response == NarrativeFunctionResponse.Ack) {
                "`narrate` only accepts acknowledgement"
            }
            return NarrativeFunctionResult.Returned()
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.narrate(arguments.single().asStringCompatible()) {
                resume(NarrativeFunctionResponse.Ack)
            }
        }
    }

    private class ChooseFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "choose"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.isNotEmpty()) { "`choose` expects at least one option" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            val selection = response as? NarrativeFunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`choose` expects a choice selection response")
            val options = arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)
                .filter { it.enabled }
                .map { it.id }
            require(selection.optionId in options) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(selection.optionId))
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)) { optionId ->
                resume(NarrativeFunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ChooseIndexedFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "chooseIndexed"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.isNotEmpty()) { "`chooseIndexed` expects at least one option" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            val selection = response as? NarrativeFunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`chooseIndexed` expects a choice selection response")
            val options = arguments.toChoiceOptionsWithSourceIndex(useTextAsId = true, includeDisabled = true)
            val selected = options.firstOrNull { indexed ->
                indexed.option.enabled && indexed.option.id == selection.optionId
            }
            require(selected != null) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(selected.sourceIndex.toString()))
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)) { optionId ->
                resume(NarrativeFunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ChooseExhaustibleFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "chooseExhaustible"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.any { it != NarrativeValue.Null }) { "`chooseExhaustible` expects at least one non-null option" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            val selection = response as? NarrativeFunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`chooseExhaustible` expects a choice selection response")
            val options = arguments.toChoiceOptions(useTextAsId = true, includeDisabled = false)
                .map { it.id }
            require(selection.optionId in options) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(selection.optionId))
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = false)) { optionId ->
                resume(NarrativeFunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private data object ChoiceOptionFunction : NarrativeFunctionDefinition {
        override val id: String = "choiceOption"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.size == 4) { "`choiceOption` expects (text, visible, enabled, disabledTextOrNull)" }
            val text = arguments[0].asStringCompatible()
            val visible = arguments[1].asBoolean()
            val enabled = arguments[2].asBoolean()
            val disabledText = arguments[3].asNullableText()
            return NarrativeFunctionResult.Returned(
                NarrativeValue.HostObject(
                    typeId = CHOICE_OPTION_TYPE_ID,
                    value = ChoiceOptionValue(
                        id = text,
                        text = text,
                        visible = visible,
                        enabled = enabled,
                        disabledText = disabledText,
                    ),
                )
            )
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            throw IllegalStateException("`choiceOption` cannot be resumed because it never suspends")
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            throw IllegalStateException("`choiceOption` cannot be dispatched because it never suspends")
        }
    }

    private class ReadLineFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "readLine"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.size == 1) { "`readLine` expects a single text question argument" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            require(arguments.size == 1) { "`readLine` expects a single text question argument" }
            val line = response as? NarrativeTextResponse
                ?: throw IllegalArgumentException("`readLine` expects a text response")
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(line.text))
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.readLine(arguments.single().asStringCompatible()) { line ->
                resume(NarrativeTextResponse(line))
            }
        }
    }
}

data class NarrativeTextResponse(
    val text: String,
) : NarrativeFunctionResponse

internal const val CHOICE_OPTION_TYPE_ID = "__choice_option"

internal data class ChoiceOptionValue(
    val id: String,
    val text: String,
    val visible: Boolean,
    val enabled: Boolean,
    val disabledText: String?,
)

private fun NarrativeValue.asStringCompatible(): String {
    return when (this) {
        NarrativeValue.Null -> "null"
        is NarrativeValue.Bool -> value.toString()
        is NarrativeValue.Int32 -> value.toString()
        is NarrativeValue.Float64 -> value.toString()
        is NarrativeValue.Text -> value
        is NarrativeValue.Lambda -> "Lambda($id)"
        is NarrativeValue.HostObject -> value.toString()
    }
}

private fun NarrativeValue.asBoolean(): Boolean {
    return when (this) {
        is NarrativeValue.Bool -> value
        else -> throw IllegalArgumentException("Expected boolean value but got $this")
    }
}

private fun NarrativeValue.asNullableText(): String? {
    return when (this) {
        NarrativeValue.Null -> null
        is NarrativeValue.Text -> value
        else -> throw IllegalArgumentException("Expected nullable text value but got $this")
    }
}

private fun List<NarrativeValue>.toChoiceOptions(
    useTextAsId: Boolean,
    includeDisabled: Boolean,
): List<ChoiceOptionSnapshot> {
    return toChoiceOptionsWithSourceIndex(
        useTextAsId = useTextAsId,
        includeDisabled = includeDisabled,
    ).map { it.option }
}

private fun List<NarrativeValue>.toChoiceOptionsWithSourceIndex(
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
        if (value is NarrativeValue.HostObject && value.typeId == CHOICE_OPTION_TYPE_ID) {
            return@mapIndexedNotNull null
        }
        when (value) {
            NarrativeValue.Null -> if (includeDisabled) {
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

private fun NarrativeValue.toChoiceOptionOrNull(
    useTextAsId: Boolean,
    index: Int,
    includeDisabled: Boolean,
): ChoiceOptionSnapshot? {
    val hostValue = this as? NarrativeValue.HostObject ?: return null
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
