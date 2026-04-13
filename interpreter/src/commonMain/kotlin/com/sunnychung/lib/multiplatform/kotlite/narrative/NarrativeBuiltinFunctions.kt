package com.sunnychung.lib.multiplatform.kotlite.narrative

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

    private class NarrateFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition<SayContinuationSnapshot, SayEffectPayload> {
        override val id: String = "narrate"
        override val stateSnapshotClass = SayContinuationSnapshot::class
        override val stateSnapshotSerializer = SayContinuationSnapshot.serializer()
        override val payloadClass = SayEffectPayload::class
        override val payloadSerializer = SayEffectPayload.serializer()

        override suspend fun startCall(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<SayContinuationSnapshot, SayEffectPayload> {
            require(arguments.size == 1) { "`narrate` expects a single text argument" }
            val payload = SayEffectPayload(
                speaker = null,
                text = arguments[0].asText(),
            )
            return NarrativeFunctionResult.Suspended(
                payload = payload,
                continuation = SayContinuationSnapshot(payload),
            )
        }

        override suspend fun resumeCall(
            continuation: SayContinuationSnapshot,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<SayContinuationSnapshot, SayEffectPayload> {
            require(response == null || response == NarrativeFunctionResponse.Ack) {
                "`narrate` only accepts acknowledgement"
            }
            return NarrativeFunctionResult.Returned()
        }

        override fun dispatch(
            payload: SayEffectPayload,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.narrate(payload.text) {
                resume(NarrativeFunctionResponse.Ack)
            }
        }
    }

    private class SayFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition<SayContinuationSnapshot, SayEffectPayload> {
        override val id: String = "say"
        override val stateSnapshotClass = SayContinuationSnapshot::class
        override val stateSnapshotSerializer = SayContinuationSnapshot.serializer()
        override val payloadClass = SayEffectPayload::class
        override val payloadSerializer = SayEffectPayload.serializer()

        override suspend fun startCall(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<SayContinuationSnapshot, SayEffectPayload> {
            require(arguments.size == 2) { "`say` expects receiver and text" }
            val payload = SayEffectPayload(
                speaker = arguments[0].toSpeakerSnapshot(),
                text = arguments[1].asText(),
            )
            return NarrativeFunctionResult.Suspended(
                payload = payload,
                continuation = SayContinuationSnapshot(payload),
            )
        }

        override suspend fun resumeCall(
            continuation: SayContinuationSnapshot,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<SayContinuationSnapshot, SayEffectPayload> {
            require(response == null || response == NarrativeFunctionResponse.Ack) {
                "`say` only accepts acknowledgement"
            }
            return NarrativeFunctionResult.Returned()
        }

        override fun dispatch(
            payload: SayEffectPayload,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.say(payload.speaker, payload.text) {
                resume(NarrativeFunctionResponse.Ack)
            }
        }
    }

    private class ChoiceFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition<ChoiceContinuationSnapshot, ChoiceEffectPayload> {
        override val id: String = "choice"
        override val stateSnapshotClass = ChoiceContinuationSnapshot::class
        override val stateSnapshotSerializer = ChoiceContinuationSnapshot.serializer()
        override val payloadClass = ChoiceEffectPayload::class
        override val payloadSerializer = ChoiceEffectPayload.serializer()

        override suspend fun startCall(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<ChoiceContinuationSnapshot, ChoiceEffectPayload> {
            require(arguments.isNotEmpty()) { "`choice` expects at least one option" }
            val options = arguments.mapIndexed { index, value ->
                ChoiceOptionSnapshot(
                    id = index.toString(),
                    text = value.asText(),
                    target = -1,
                )
            }
            return NarrativeFunctionResult.Suspended(
                payload = ChoiceEffectPayload(options),
                continuation = ChoiceContinuationSnapshot(options.map { it.id }),
            )
        }

        override suspend fun resumeCall(
            continuation: ChoiceContinuationSnapshot,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<ChoiceContinuationSnapshot, ChoiceEffectPayload> {
            val selection = response as? NarrativeFunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`choice` expects a choice selection response")
            require(selection.optionId in continuation.optionIds) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(selection.optionId))
        }

        override fun dispatch(
            payload: ChoiceEffectPayload,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.choose(payload.options) { optionId ->
                resume(NarrativeFunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ChooseFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition<ChoiceContinuationSnapshot, ChoiceEffectPayload> {
        override val id: String = "choose"
        override val stateSnapshotClass = ChoiceContinuationSnapshot::class
        override val stateSnapshotSerializer = ChoiceContinuationSnapshot.serializer()
        override val payloadClass = ChoiceEffectPayload::class
        override val payloadSerializer = ChoiceEffectPayload.serializer()

        override suspend fun startCall(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<ChoiceContinuationSnapshot, ChoiceEffectPayload> {
            require(arguments.isNotEmpty()) { "`choose` expects at least one option" }
            val options = arguments.map { value ->
                value.asText()
            }
            return createChoiceSuspension(options)
        }

        override suspend fun resumeCall(
            continuation: ChoiceContinuationSnapshot,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<ChoiceContinuationSnapshot, ChoiceEffectPayload> {
            val selection = response as? NarrativeFunctionResponse.ChoiceSelection
                ?: throw IllegalArgumentException("`choose` expects a choice selection response")
            require(selection.optionId in continuation.optionIds) {
                "Unknown choice `${selection.optionId}`"
            }
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(selection.optionId))
        }

        override fun dispatch(
            payload: ChoiceEffectPayload,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.choose(payload.options) { optionId ->
                resume(NarrativeFunctionResponse.ChoiceSelection(optionId))
            }
        }
    }

    private class ReadLineFunction(private val host: NarrativeHost) : NarrativeFunctionDefinition<ReadLineContinuationSnapshot, ReadLineEffectPayload> {
        override val id: String = "readLine"
        override val stateSnapshotClass = ReadLineContinuationSnapshot::class
        override val stateSnapshotSerializer = ReadLineContinuationSnapshot.serializer()
        override val payloadClass = ReadLineEffectPayload::class
        override val payloadSerializer = ReadLineEffectPayload.serializer()

        override suspend fun startCall(
            arguments: List<NarrativeValue>,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<ReadLineContinuationSnapshot, ReadLineEffectPayload> {
            require(arguments.isEmpty()) { "`readLine` does not accept arguments" }
            return NarrativeFunctionResult.Suspended(
                payload = ReadLineEffectPayload,
                continuation = ReadLineContinuationSnapshot,
            )
        }

        override suspend fun resumeCall(
            continuation: ReadLineContinuationSnapshot,
            response: NarrativeFunctionResponse?,
            context: NarrativeFunctionContext,
        ): NarrativeFunctionResult<ReadLineContinuationSnapshot, ReadLineEffectPayload> {
            val line = response as? NarrativeTextResponse
                ?: throw IllegalArgumentException("`readLine` expects a text response")
            return NarrativeFunctionResult.Returned(NarrativeValue.Text(line.text))
        }

        override fun dispatch(
            payload: ReadLineEffectPayload,
            context: NarrativeFunctionDispatchContext,
            resume: (NarrativeFunctionResponse?) -> Unit,
        ) {
            host.readLine { line ->
                resume(NarrativeTextResponse(line))
            }
        }
    }

    private fun createChoiceSuspension(options: List<String>): NarrativeFunctionResult.Suspended<ChoiceContinuationSnapshot, ChoiceEffectPayload> {
        val snapshots = options.map { text ->
            ChoiceOptionSnapshot(
                id = text,
                text = text,
                target = -1,
            )
        }
        return NarrativeFunctionResult.Suspended(
            payload = ChoiceEffectPayload(snapshots),
            continuation = ChoiceContinuationSnapshot(snapshots.map { it.id }),
        )
    }
}

@Serializable
@SerialName("say_continuation")
data class SayContinuationSnapshot(
    val payload: SayEffectPayload,
) : NarrativeFunctionStateSnapshot()

@Serializable
@SerialName("choice_continuation")
data class ChoiceContinuationSnapshot(
    val optionIds: List<String>,
) : NarrativeFunctionStateSnapshot()

@Serializable
@SerialName("read_line_continuation")
data object ReadLineContinuationSnapshot : NarrativeFunctionStateSnapshot()

@Serializable
@SerialName("read_line_effect")
data object ReadLineEffectPayload : NarrativeFunctionEffectPayload()

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
