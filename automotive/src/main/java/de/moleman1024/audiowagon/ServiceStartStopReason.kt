package de.moleman1024.audiowagon

enum class ServiceStartStopReason() {
    UNKNOWN,
    INDEXING,
    MEDIA_BUTTON,
    MEDIA_SESSION_CALLBACK,
    SUSPEND_OR_SHUTDOWN
}
