pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    plugins {
        kotlin("jvm") version "1.9.23"
        kotlin("multiplatform") version "1.9.23"
        kotlin("plugin.serialization") version "1.9.23"
    }
}

rootProject.name = "kotlite-stdlib-processor-plugin"

include(":kotlite-interpreter")
project(":kotlite-interpreter").projectDir = file("../interpreter")
