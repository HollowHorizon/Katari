package com.sunnychung.lib.multiplatform.kotlite.model

interface NarrativeCallable {
    val id: String

    val receiverType: String?
    val returnType: String
    val typeParameters: List<TypeParameter>
    val valueParameters: List<CustomFunctionParameter>
    val parameterDefaults: List<RuntimeValue?>
        get() = emptyList()
    val semanticFunctionDefinition: CustomFunctionDefinition?
        get() = toCustomFunctionDefinition()

    suspend fun startCall(
        arguments: List<RuntimeValue>,
        context: NarrativeCallContext,
    ): NarrativeCallResult

    suspend fun resumeCall(
        arguments: List<RuntimeValue>,
        response: FunctionResponse?,
        context: NarrativeCallContext,
    ): NarrativeCallResult

    fun dispatch(
        arguments: List<RuntimeValue>,
        context: NarrativeCallDispatchContext,
        resume: (FunctionResponse?) -> Unit,
    )
}

fun NarrativeCallable.toCustomFunctionDefinition(): CustomFunctionDefinition {
    return CustomFunctionDefinition(
        position = SourcePosition.NONE,
        receiverType = receiverType,
        functionName = id,
        returnType = returnType,
        typeParameters = typeParameters,
        parameterTypes = valueParameters.map { parameter ->
            if (parameter.type == "Any") {
                CustomFunctionParameter(
                    name = parameter.name,
                    type = "Any?",
                    defaultValueExpression = parameter.defaultValueExpression,
                    modifiers = parameter.modifiers,
                )
            } else {
                parameter
            }
        },
        modifiers = emptySet(),
        executable = { _, _, _, _ ->
            throw UnsupportedOperationException(
                "Narrative callable `$id` must be executed through KatariInstance, not through direct interpretation"
            )
        },
    )
}

interface NarrativeCallContext {
    val symbolTable: SymbolTable
    val state: Any
    val task: Any
}

interface NarrativeCallDispatchContext : NarrativeCallContext

sealed interface NarrativeCallResult {
    data class Returned(val value: RuntimeValue) : NarrativeCallResult
    data object Suspended : NarrativeCallResult
}

interface FunctionResponse {
    data object Ack : FunctionResponse
    data class ChoiceSelection(val optionId: String) : FunctionResponse
}
