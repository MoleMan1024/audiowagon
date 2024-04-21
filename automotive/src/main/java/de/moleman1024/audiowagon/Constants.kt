package de.moleman1024.audiowagon

const val ALBUM_ART_MIN_NUM_PIXELS: Int = 128
const val IDLE_TIMEOUT_MS = 30000L
// This PLAY_USB action seems to be essential for getting Google Assistant to accept voice commands such as
// "play <artist | album | track>"
const val ACTION_PLAY_USB = "android.car.intent.action.PLAY_USB"
const val ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"
const val ACTION_START_SERVICE_WITH_USB_DEVICE = "de.moleman1024.audiowagon.ACTION_START_SERVICE_WITH_USB_DEVICE"
const val CMD_DISABLE_CRASH_REPORTING = "de.moleman1024.audiowagon.CMD_DISABLE_CRASH_REPORTING"
const val CMD_DISABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_DISABLE_EQUALIZER"
const val CMD_DISABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_DISABLE_LOG_TO_USB"
const val CMD_DISABLE_REPLAYGAIN = "de.moleman1024.audiowagon.CMD_DISABLE_REPLAYGAIN"
const val CMD_EJECT = "de.moleman1024.audiowagon.CMD_EJECT"
const val CMD_ENABLE_CRASH_REPORTING = "de.moleman1024.audiowagon.CMD_ENABLE_CRASH_REPORTING"
const val CMD_ENABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_ENABLE_EQUALIZER"
const val CMD_ENABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_ENABLE_LOG_TO_USB"
const val CMD_ENABLE_REPLAYGAIN = "de.moleman1024.audiowagon.CMD_ENABLE_REPLAYGAIN"
const val CMD_READ_METADATA_NOW = "de.moleman1024.audiowagon.CMD_READ_METADATA_NOW"
const val CMD_REQUEST_USB_PERMISSION = "de.moleman1024.audiowagon.CMD_REQUEST_USB_PERMISSION"
const val CMD_SET_ALBUM_STYLE_SETTING = "de.moleman1024.audiowagon.CMD_SET_ALBUM_STYLE_SETTING"
const val CMD_SET_AUDIOFOCUS_SETTING = "de.moleman1024.audiowagon.CMD_SET_AUDIOFOCUS_SETTING"
const val CMD_SET_EQUALIZER_PRESET = "de.moleman1024.audiowagon.CMD_SET_EQUALIZER_PRESET"
const val CMD_SET_METADATAREAD_SETTING = "de.moleman1024.audiowagon.CMD_SET_METADATAREAD_SETTING"
const val CMD_SET_VIEW_TABS = "de.moleman1024.audiowagon.CMD_SET_VIEW_TABS"
const val ALBUM_STYLE_KEY = "album_style"
const val AUDIOFOCUS_SETTING_KEY = "audiofocus_setting"
const val EQUALIZER_PRESET_KEY = "preset"
const val METADATAREAD_SETTING_KEY = "metadata_read_setting"
const val VIEW_TABS_SETTING_KEY = "view_tabs"

const val NOTIFICATION_ID: Int = 25575
const val DEFAULT_JPEG_QUALITY_PERCENTAGE = 60
const val POPUP_TIMEOUT_MS: Long = 4000L
const val NUM_VIEW_TABS: Int = 4
const val UPDATE_ATTACHED_DEVICES_AFTER_UNLOCK_DELAY_MS = 2000L
