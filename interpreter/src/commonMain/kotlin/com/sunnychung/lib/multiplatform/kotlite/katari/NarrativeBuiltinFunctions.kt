package com.sunnychung.lib.multiplatform.kotlite.katari

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

    fun registry(host: NarrativeHost): KatariFunctionRegistry {
        return KatariFunctionRegistry(definitions(host))
    }

    fun definitions(host: NarrativeHost): List<KatariFunctionDefinition> {
        return listOf(
            NarrateFunction(host),
            ChooseFunction(host),
            ChooseIndexedFunction(host),
            ChooseExhaustibleFunction(host),
            ChoiceOptionFunction,
            ReadLineFunction(host)
        )
    }

    private class NarrateFunction(private val host: NarrativeHost) : KatariFunctionDefinition {
        override val id: String = "narrate"

        override suspend fun startCall(arguments: List<KatariValue>, context: KatariFunctionContext): FunctionResult {
            require(arguments.size == 1) { "`narrate` expects a single text argument" }
            return FunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<KatariValue>,
            response: FunctionResponse?,
            context: KatariFunctionContext,
        ): FunctionResult {
            require(arguments.size == 1) { "`narrate` expects a single text argument" }
            require(response == null || response == FunctionResponse.Ack) {
                "`narrate` only accepts acknowledgement"
            }
            return FunctionResult.Returned()
        }

        override fun dispatch(
            arguments: List<KatariValue>,
            context: KatariFunctionDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.narrate(arguments.single().asStringCompatible()) {
                resume(FunctionResponse.Ack)
            }
        }
    }

    private class ChooseFunction(private val host: NarrativeHost) : KatariFunctionDefinition {
        override val id: String = "choose"

        override suspend fun startCall(arguments: List<KatariValue>, context: KatariFunctionContext): FunctionResult {
            require(arguments.isNotEmpty()) { "`choose` expects at least one option" }
            return FunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<KatariValue>,
            response: FunctionResponse?,
            context: KatariFunctionContext,
        ): FunctionResult {
            val selection = response as? FunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`choose` expects a choice selection response")
            val options = arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)
                .filter { it.enabled }
                .map { it.id }
            require(selection.optionId in options) {
                "Unknown choice `${selection.optionId}`"
            }
            return FunctionResult.Returned(KatariValue.Text(selection.optionId))
        }

        override fun dispatch(
            arguments: List<KatariValue>,
            context: KatariFunctionDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)) { optionId ->
                resume(FunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ChooseIndexedFunction(private val host: NarrativeHost) : KatariFunctionDefinition {
        override val id: String = "chooseIndexed"

        override suspend fun startCall(arguments: List<KatariValue>, context: KatariFunctionContext): FunctionResult {
            require(arguments.isNotEmpty()) { "`chooseIndexed` expects at least one option" }
            return FunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<KatariValue>,
            response: FunctionResponse?,
            context: KatariFunctionContext,
        ): FunctionResult {
            val selection = response as? FunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`chooseIndexed` expects a choice selection response")
            val options = arguments.toChoiceOptionsWithSourceIndex(useTextAsId = true, includeDisabled = true)
            val selected = options.firstOrNull { indexed ->
                indexed.option.enabled && indexed.option.id == selection.optionId
            }
            require(selected != null) {
                "Unknown choice `${selection.optionId}`"
            }
            return FunctionResult.Returned(KatariValue.Text(selected.sourceIndex.toString()))
        }

        override fun dispatch(
            arguments: List<KatariValue>,
            context: KatariFunctionDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = true)) { optionId ->
                resume(FunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ChooseExhaustibleFunction(private val host: NarrativeHost) : KatariFunctionDefinition {
        override val id: String = "chooseExhaustible"

        override suspend fun startCall(arguments: List<KatariValue>, context: KatariFunctionContext): FunctionResult {
            require(arguments.any { it != KatariValue.Null }) { "`chooseExhaustible` expects at least one non-null option" }
            return FunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<KatariValue>,
            response: FunctionResponse?,
            context: KatariFunctionContext,
        ): FunctionResult {
            val selection = response as? FunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`chooseExhaustible` expects a choice selection response")
            val options = arguments.toChoiceOptions(useTextAsId = true, includeDisabled = false)
                .map { it.id }
            require(selection.optionId in options) {
                "Unknown choice `${selection.optionId}`"
            }
            return FunctionResult.Returned(KatariValue.Text(selection.optionId))
        }

        override fun dispatch(
            arguments: List<KatariValue>,
            context: KatariFunctionDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.choose(arguments.toChoiceOptions(useTextAsId = true, includeDisabled = false)) { optionId ->
                resume(FunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private data object ChoiceOptionFunction : KatariFunctionDefinition {
        override val id: String = "choiceOption"

        override suspend fun startCall(arguments: List<KatariValue>, context: KatariFunctionContext): FunctionResult {
            require(arguments.size == 4) { "`choiceOption` expects (text, visible, enabled, disabledTextOrNull)" }
            val text = arguments[0].asStringCompatible()
            val visible = arguments[1].asBoolean()
            val enabled = arguments[2].asBoolean()
            val disabledText = arguments[3].asNullableText()
            return FunctionResult.Returned(
                KatariValue.HostObject(
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
            arguments: List<KatariValue>,
            response: FunctionResponse?,
            context: KatariFunctionContext,
        ): FunctionResult {
            throw IllegalStateException("`choiceOption` cannot be resumed because it never suspends")
        }

        override fun dispatch(
            arguments: List<KatariValue>,
            context: KatariFunctionDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            throw IllegalStateException("`choiceOption` cannot be dispatched because it never suspends")
        }
    }

    private class ReadLineFunction(private val host: NarrativeHost) : KatariFunctionDefinition {
        override val id: String = "readLine"

        override suspend fun startCall(arguments: List<KatariValue>, context: KatariFunctionContext): FunctionResult {
            require(arguments.size == 1) { "`readLine` expects a single text question argument" }
            return FunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<KatariValue>,
            response: FunctionResponse?,
            context: KatariFunctionContext,
        ): FunctionResult {
            require(arguments.size == 1) { "`readLine` expects a single text question argument" }
            val line = response as? NarrativeTextResponse
                ?: throw IllegalArgumentException("`readLine` expects a text response")
            return FunctionResult.Returned(KatariValue.Text(line.text))
        }

        override fun dispatch(
            arguments: List<KatariValue>,
            context: KatariFunctionDispatchContext,
            resume: (FunctionResponse?) -> Unit,
        ) {
            host.readLine(arguments.single().asStringCompatible()) { line ->
                resume(NarrativeTextResponse(line))
            }
        }
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

private fun KatariValue.asStringCompatible(): String {
    return when (this) {
        KatariValue.Null -> "null"
        is KatariValue.Bool -> value.toString()
        is KatariValue.Int32 -> value.toString()
        is KatariValue.Float64 -> value.toString()
        is KatariValue.Text -> value
        is KatariValue.Lambda -> "Lambda($id)"
        is KatariValue.HostObject -> value.toString()
    }
}

private fun KatariValue.asBoolean(): Boolean {
    return when (this) {
        is KatariValue.Bool -> value
        else -> throw IllegalArgumentException("Expected boolean value but got $this")
    }
}

private fun KatariValue.asNullableText(): String? {
    return when (this) {
        KatariValue.Null -> null
        is KatariValue.Text -> value
        else -> throw IllegalArgumentException("Expected nullable text value but got $this")
    }
}

private fun List<KatariValue>.toChoiceOptions(
    useTextAsId: Boolean,
    includeDisabled: Boolean,
): List<ChoiceOptionSnapshot> {
    return toChoiceOptionsWithSourceIndex(
        useTextAsId = useTextAsId,
        includeDisabled = includeDisabled,
    ).map { it.option }
}

private fun List<KatariValue>.toChoiceOptionsWithSourceIndex(
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
        if (value is KatariValue.HostObject && value.typeId == CHOICE_OPTION_TYPE_ID) {
            return@mapIndexedNotNull null
        }
        when (value) {
            KatariValue.Null -> if (includeDisabled) {
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

private fun KatariValue.toChoiceOptionOrNull(
    useTextAsId: Boolean,
    index: Int,
    includeDisabled: Boolean,
): ChoiceOptionSnapshot? {
    val hostValue = this as? KatariValue.HostObject ?: return null
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
