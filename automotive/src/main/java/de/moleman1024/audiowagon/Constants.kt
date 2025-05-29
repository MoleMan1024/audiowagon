package de.moleman1024.audiowagon

import de.moleman1024.audiowagon.enums.EqualizerPreset

const val ALBUM_ART_MIN_NUM_PIXELS: Int = 128
const val IDLE_TIMEOUT_MS = 30000L
// This PLAY_USB action seems to be essential for getting Google Assistant to accept voice commands such as
// "play <artist | album | track>"
const val ACTION_PLAY_USB = "android.car.intent.action.PLAY_USB"
const val ACTION_MEDIA_BUTTON = "android.intent.action.MEDIA_BUTTON"
const val ACTION_START_SERVICE_WITH_USB_DEVICE = "de.moleman1024.audiowagon.ACTION_START_SERVICE_WITH_USB_DEVICE"
const val ACTION_BIND_ALBUM_ART_CONTENT_PROVIDER = "de.moleman1024.audiowagon.ACTION_BIND_ALBUM_ART_CONTENT_PROVIDER"
const val ACTION_BIND_FROM_TEST_FIXTURE = "de.moleman1024.audiowagon.ACTION_BIND_FROM_TEST_FIXTURE"
const val CMD_DISABLE_CRASH_REPORTING = "de.moleman1024.audiowagon.CMD_DISABLE_CRASH_REPORTING"
const val CMD_DISABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_DISABLE_EQUALIZER"
const val CMD_DISABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_DISABLE_LOG_TO_USB"
const val CMD_DISABLE_REPLAYGAIN = "de.moleman1024.audiowagon.CMD_DISABLE_REPLAYGAIN"
const val CMD_DISABLE_SHOW_ALBUM_ART = "de.moleman1024.audiowagon.CMD_DISABLE_SHOW_ALBUM_ART"
const val CMD_EJECT = "de.moleman1024.audiowagon.CMD_EJECT"
const val CMD_ENABLE_CRASH_REPORTING = "de.moleman1024.audiowagon.CMD_ENABLE_CRASH_REPORTING"
const val CMD_ENABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_ENABLE_EQUALIZER"
const val CMD_ENABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_ENABLE_LOG_TO_USB"
const val CMD_ENABLE_REPLAYGAIN = "de.moleman1024.audiowagon.CMD_ENABLE_REPLAYGAIN"
const val CMD_ENABLE_SHOW_ALBUM_ART = "de.moleman1024.audiowagon.CMD_ENABLE_SHOW_ALBUM_ART"
const val CMD_READ_METADATA_NOW = "de.moleman1024.audiowagon.CMD_READ_METADATA_NOW"
const val CMD_REQUEST_USB_PERMISSION = "de.moleman1024.audiowagon.CMD_REQUEST_USB_PERMISSION"
const val CMD_SET_ALBUM_STYLE_SETTING = "de.moleman1024.audiowagon.CMD_SET_ALBUM_STYLE_SETTING"
const val CMD_SET_AUDIOFOCUS_SETTING = "de.moleman1024.audiowagon.CMD_SET_AUDIOFOCUS_SETTING"
const val CMD_SET_EQUALIZER_PRESET = "de.moleman1024.audiowagon.CMD_SET_EQUALIZER_PRESET"
const val CMD_SET_EQUALIZER_BAND = "de.moleman1024.audiowagon.CMD_SET_EQUALIZER_BAND"
const val CMD_SET_METADATAREAD_SETTING = "de.moleman1024.audiowagon.CMD_SET_METADATAREAD_SETTING"
const val CMD_SET_VIEW_TABS = "de.moleman1024.audiowagon.CMD_SET_VIEW_TABS"
const val ALBUM_STYLE_KEY = "album_style"
const val AUDIOFOCUS_SETTING_KEY = "audiofocus_setting"
const val EQUALIZER_PRESET_KEY = "preset"
const val EQUALIZER_BAND_INDEX_KEY = "eqBandIndex"
const val EQUALIZER_BAND_VALUE_KEY = "eqBandValue"
const val METADATAREAD_SETTING_KEY = "metadata_read_setting"
const val VIEW_TABS_SETTING_KEY = "view_tabs"

const val NOTIFICATION_ID: Int = 25575
const val DEFAULT_JPEG_QUALITY_PERCENTAGE = 60
const val POPUP_TIMEOUT_MS: Long = 4000L
const val NUM_VIEW_TABS: Int = 4
const val UPDATE_ATTACHED_DEVICES_AFTER_UNLOCK_DELAY_MS = 2000L

val EQUALIZER_PRESET_VALUES_LESS_BASS = floatArrayOf(-3f, 0f, 0f, 0f, 0f)
val EQUALIZER_PRESET_VALUES_MORE_BASS = floatArrayOf(3f, 0f, 0f, 0f, 0f)
val EQUALIZER_PRESET_VALUES_LESS_HIGHS = floatArrayOf(0f, 0f, 0f, 0f, -4f)
val EQUALIZER_PRESET_VALUES_MORE_HIGHS = floatArrayOf(0f, 0f, 0f, 0f, 4f)
val EQUALIZER_PRESET_VALUES_P2 = floatArrayOf(-2f, -0.5f, -0.7f, 0.7f, -1f)
val EQUALIZER_PRESET_VALUES_P2_PLUS = floatArrayOf(-4f, -1f, -1.5f, 1.4f, -2f)
val EQUALIZER_PRESET_VALUES_V_SHAPE = floatArrayOf(3f, 1f, -1f, 1.5f, 5f)
// this has no effect, just for safety
val EQUALIZER_PRESET_VALUES_CUSTOM = floatArrayOf(0f, 0f, 0f, 0f, 0f)

// Polestar 2 and Pixel 3 XL have 5 band EQs, with Q approx 0.6
// center 60 from 30 til 120 Hz
// center 230 from 120 til 460 Hz
// center 910 from 460 til 1800 Hz
// center 3600 from 1800 til 7000 Hz
// center 14000 from 7000 til 20000 Hz
// TODO: It seems DynamicsProcessing allows for more bands
val EQUALIZER_PRESET_MAPPING = mapOf(
    EqualizerPreset.LESS_BASS to EQUALIZER_PRESET_VALUES_LESS_BASS,
    EqualizerPreset.MORE_BASS to EQUALIZER_PRESET_VALUES_MORE_BASS,
    EqualizerPreset.LESS_HIGHS to EQUALIZER_PRESET_VALUES_LESS_HIGHS,
    EqualizerPreset.MORE_HIGHS to EQUALIZER_PRESET_VALUES_MORE_HIGHS,
    EqualizerPreset.P2 to EQUALIZER_PRESET_VALUES_P2,
    EqualizerPreset.P2_PLUS to EQUALIZER_PRESET_VALUES_P2_PLUS,
    EqualizerPreset.V_SHAPE to EQUALIZER_PRESET_VALUES_V_SHAPE,
    EqualizerPreset.CUSTOM to EQUALIZER_PRESET_VALUES_CUSTOM,
)
