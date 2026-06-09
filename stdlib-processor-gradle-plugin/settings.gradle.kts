pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    plugins {
        kotlin("jvm") version "2.4.0"
        kotlin("multiplatform") version "2.4.0"
        kotlin("plugin.serialization") version "2.4.0"
    }
}

rootProject.name = "kotlite-stdlib-processor-plugin"

include(":kotlite-interpreter")
project(":kotlite-interpreter").projectDir = file("../interpreter")
