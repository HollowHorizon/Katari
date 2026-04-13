package com.sunnychung.lib.multiplatform.kotlite.narrative

interface NarrativeHost {
    fun narrate(text: String, resume: () -> Unit)
    fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit)
    fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit)
    fun readLine(resume: (String) -> Unit)
}

data object NarrativeNoOpHost : NarrativeHost {
    override fun narrate(text: String, resume: () -> Unit) = resume()
    override fun say(speaker: NarrativeValueSnapshot?, text: String, resume: () -> Unit) = resume()
    override fun choose(options: List<ChoiceOptionSnapshot>, resume: (String) -> Unit) {
        resume(options.firstOrNull()?.id ?: "")
    }
    override fun readLine(resume: (String) -> Unit) = resume("")
}

object NarrativeBuiltinFunctions {

    fun registry(host: NarrativeHost): NarrativeFunctionRegistry {
        return NarrativeFunctionRegistry(
            listOf(
                NarrateFunction(host),
                SayFunction(host),
                ChoiceFunction(host),
                ChooseFunction(host),
                ReadLineFunction(host),
            )
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
            host.narrate(arguments.single().asText()) {
                resume(NarrativeFunctionResponse.Ack)
            }
        }
    }

    private class SayFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "say"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.size == 2) { "`say` expects receiver and text" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            require(arguments.size == 2) { "`say` expects receiver and text" }
            require(response == null || response == NarrativeFunctionResponse.Ack) {
                "`say` only accepts acknowledgement"
            }
            return NarrativeFunctionResult.Returned()
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.say(arguments[0].toSpeakerSnapshot(), arguments[1].asText()) {
                resume(NarrativeFunctionResponse.Ack)
            }
        }
    }

    private class ChoiceFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "choice"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.isNotEmpty()) { "`choice` expects at least one option" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            val selection = response as? NarrativeFunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`choice` expects a choice selection response")
            val options = arguments.mapIndexed { index, _ -> index.toString() }
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
            host.choose(arguments.toChoiceOptions(useTextAsId = false)) { optionId ->
                resume(NarrativeFunctionResponse.ChoiceSelection(optionId))
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
            val options = arguments.map { it.asText() }
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
            host.choose(arguments.toChoiceOptions(useTextAsId = true)) { optionId ->
                resume(NarrativeFunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ReadLineFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition {
        override val id: String = "readLine"

        override suspend fun startCall(arguments: List<NarrativeValue>, context: NarrativeFunctionContext): NarrativeFunctionResult {
            require(arguments.isEmpty()) { "`readLine` does not accept arguments" }
            return NarrativeFunctionResult.Suspended
        }

        override suspend fun resumeCall(
            arguments: List<NarrativeValue>,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult {
            require(arguments.isEmpty()) { "`readLine` does not accept arguments" }
            val line = response as? NarrativeTextResponse
                ?: throw IllegalArgumentException("`readLine` expects a text response")
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(line.text))
        }

        override fun dispatch(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.readLine { line ->
                resume(NarrativeTextResponse(line))
            }
        }
    }
}

data class NarrativeTextResponse(
    val text: String,
) : NarrativeFunctionResponse

private fun NarrativeValue.asText(): String {
    return when (this) {
        is NarrativeValue.Text -> value
        is NarrativeValue.Entity -> id
        else -> throw IllegalArgumentException("Expected text-compatible value but got $this")
    }
}

private fun NarrativeValue.toSpeakerSnapshot(): NarrativeValueSnapshot {
    return when (this) {
        NarrativeValue.Null -> NullValueSnapshot
        is NarrativeValue.Text -> TextValueSnapshot(value)
        is NarrativeValue.Entity -> EntityValueSnapshot(id)
        else -> throw IllegalArgumentException("Built-in `say` cannot serialize speaker value `$this`")
    }
}

private fun List<NarrativeValue>.toChoiceOptions(useTextAsId: Boolean): List<ChoiceOptionSnapshot> {
    return mapIndexed { index, value ->
        val text = value.asText()
        ChoiceOptionSnapshot(
            id = if (useTextAsId) text else index.toString(),
            text = text,
            target = -1,
        )
    }
}
