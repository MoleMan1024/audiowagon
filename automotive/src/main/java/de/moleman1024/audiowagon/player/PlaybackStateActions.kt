package de.moleman1024.audiowagon.player

import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import de.moleman1024.audiowagon.R

class PlaybackStateActions(private val context: Context) {

    fun createPlaybackActions(): Long {
        return PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                // we do not support PLAY_FROM_URI, but it seems to be needed for Google Assistant?
                PlaybackStateCompat.ACTION_PLAY_FROM_URI or
                PlaybackStateCompat.ACTION_PREPARE or
                PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                PlaybackStateCompat.ACTION_PREPARE_FROM_URI
        // we don't use ACTION_SET_SHUFFLE_MODE and ACTION_SET_REPEAT_MODE here because AAOS does not display them
    }

    /**
     * Create custom actions for shuffle and repeat because AAOS will not display the default Android actions
     */
    fun createCustomActionShuffleIsOff(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_SHUFFLE_ON, context.getString(R.string.action_shuffle_turn_on), R.drawable.shuffle
        ).build()
    }

    fun createCustomActionShuffleIsOn(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_SHUFFLE_OFF, context.getString(R.string.action_shuffle_turn_off), R.drawable.shuffle_on
        ).build()
    }

    fun createCustomActionRepeatIsOff(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_REPEAT_ON, context.getString(R.string.action_repeat_turn_on), R.drawable.repeat
        ).build()
    }

    fun createCustomActionRepeatIsOn(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_REPEAT_OFF, context.getString(R.string.action_repeat_turn_off), R.drawable.repeat_on
        ).build()
    }

    fun createCustomActionEject(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_EJECT, context.getString(R.string.action_eject), R.drawable.eject
        ).build()
    }

    fun createCustomActionRewind10(): PlaybackStateCompat.CustomAction {
        return PlaybackStateCompat.CustomAction.Builder(
            ACTION_REWIND_10, context.getString(R.string.action_rewind), R.drawable.replay_10
        ).build()
    }

}
