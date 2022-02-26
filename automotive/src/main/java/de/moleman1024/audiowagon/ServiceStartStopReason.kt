package de.moleman1024.audiowagon

@Suppress("unused")
enum class ServiceStartStopReason(val level: Int) {
    UNKNOWN(0),
    INDEXING(1),
    MEDIA_BUTTON(2),
    MEDIA_SESSION_CALLBACK(3),
    SUSPEND_OR_SHUTDOWN(4)
}
