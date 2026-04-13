package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.narrative.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val host = SwingNarrativeHost()

    val instance = NarrativeInstance(
        program = KotliteNarrativeProgram(
            filename = "<Narrative>",
            code = """
                "Тёмная комната. Слышны шаги."
                npc.say("Наконец-то ты пришёл.")
                
                var name = readLine()
                var mood = "neutral"
                
                if (name == "Игорь") {
                    npc.say("Привет, Игорь!")
                    mood = "friendly"
                } else {
                    npc.say("Привет, незнакомец.")
                    mood = "suspicious"
                }
                
                "НПС внимательно смотрит на тебя."
                
                val action = choose(
                    "Спросить кто он такой",
                    "Промолчать",
                    "Уйти"
                )
                
                if (action == "Спросить кто он такой") {
                    npc.say("Я тот, кто наблюдал за тобой.")
                    
                    if (mood == "friendly") {
                        npc.say("И, возможно, даже помогал тебе.")
                    } else {
                        npc.say("Но пока не уверен, можно ли тебе доверять.")
                    }
                } else if (action == "Промолчать") {
                    "Ты ничего не отвечаешь."
                    npc.say("Молчание тоже бывает ответом.")
                } else {
                    "Ты разворачиваешься к выходу."
                    npc.say("Мы ещё встретимся.")
                }
                
                "Сцена завершена."
            """.trimIndent(),
        ),
        initialState = NarrativeState(
            programVersion = 1,
            tasks = listOf(
                NarrativeTaskState(id = "main")
            ),
            globals = mapOf(
                "npc" to NarrativeValue.Entity("npc_1")
            ),
        ),
        functionRegistry = NarrativeBuiltinFunctions.registry(host),
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    instance.start()
}