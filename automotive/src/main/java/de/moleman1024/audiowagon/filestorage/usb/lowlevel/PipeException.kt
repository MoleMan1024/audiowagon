package de.moleman1024.audiowagon.filestorage.usb.lowlevel

import java.io.IOException

class PipeException : IOException("EPIPE, endpoint seems to be stalled")
