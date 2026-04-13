plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        jvmToolchain(8)
        withJava()
    }

    js {
        browser()
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach {
        it.binaries.framework {
            baseName = "KotliteInterpreterDemoShared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":kotlite-interpreter"))
                implementation(project(":kotlite-stdlib"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.3")
            }
        }
        val jsMain by getting
        val jsTest by getting
    }
}

tasks.register<Jar>("jvmFatJar") {
    group = "distribution"
    description = "Builds a runnable JVM demo jar with all dependencies."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "ru.hollowhorizon.narrate.DemoKt"
    }

    val jvmTarget = kotlin.targets.getByName("jvm")
    val mainCompilation = jvmTarget.compilations.getByName("main")

    dependsOn(mainCompilation.compileTaskProvider)
    from(mainCompilation.output)
    from({
        mainCompilation.runtimeDependencyFiles?.map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })
}
