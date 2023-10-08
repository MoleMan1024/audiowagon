package de.moleman1024.audiowagon.log

import de.moleman1024.audiowagon.enums.LogLevel

data class LogData(
    val level: LogLevel,
    val tag: String? = null,
    var msg: String,
    val stackTrace: String? = null,
    var timestamp: String? = null,
    var threadID: Int? = null,
    var processID: Int? = null
)
