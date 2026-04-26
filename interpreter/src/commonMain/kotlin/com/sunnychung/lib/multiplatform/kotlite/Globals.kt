package com.sunnychung.lib.multiplatform.kotlite

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.MutableLoggerConfig
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter

internal val log = Logger(
    config = object : MutableLoggerConfig {
        override var logWriterList: List<LogWriter> = listOf(object : LogWriter() {
            override fun log(
                severity: Severity,
                message: String,
                tag: String,
                throwable: Throwable?,
            ) {
                println("[$severity]: $message ($tag) #$throwable")
            }
        })
        override var minSeverity: Severity = Severity.Info
    },
    tag = "kotlite",
)

fun setKotliteLogMinLevel(severity: Severity) {
    (log.config as MutableLoggerConfig).minSeverity = severity
}
