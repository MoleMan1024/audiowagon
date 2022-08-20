package de.moleman1024.audiowagon.log

data class LogData(
    val level: LoggerInterface.LogLevel,
    val tag: String? = null,
    var msg: String,
    val stackTrace: String? = null,
    var timestamp: String? = null,
    var threadID: Int? = null,
    var processID: Int? = null
)
