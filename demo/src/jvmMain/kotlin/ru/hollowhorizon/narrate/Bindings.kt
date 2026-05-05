package ru.hollowhorizon.narrate

import com.sunnychung.lib.multiplatform.kotlite.katari.*
import com.sunnychung.lib.multiplatform.kotlite.stdlib.AllStdLibModules


fun defaultBindings(host: NarrativeHost) = NarrativeBindings {

    install(AllStdLibModules())
    register(NarrativeBuiltinFunctions.definitions(host))
}