// based on https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:preference/preference/src/main/java/androidx/preference/SeekBarPreference.java
/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
SPDX-FileCopyrightText: 2021-2024 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.activities

import android.content.Context
import android.content.res.TypedArray
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import de.moleman1024.audiowagon.R
import kotlin.math.abs
import kotlin.math.min

private const val TAG = "EQSeekBarPreference"

class EqualizerSeekbarPreference(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
    Preference(context, attrs, defStyleAttr, defStyleRes) {
    var mSeekBarValue: Int = 0
    var mMin: Int = 0
    private var mMax = 0
    private var mSeekBarIncrement = 0
    var mTrackingTouch: Boolean = false
    private var mSeekBar: SeekBar? = null
    private var mSeekBarValueTextView: TextView? = null

    // Whether to show the SeekBar value TextView next to the bar
    private var mShowSeekBarValue = false

    // Whether the SeekBarPreference should continuously save the Seekbar value while it is being
    // dragged.
    var mUpdatesContinuously: Boolean = false

    init {
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.EQSeekBarPreference, defStyleAttr, defStyleRes
        )
        // The ordering of these two statements are important. If we want to set max first, we need
        // to perform the same steps by changing min/max to max/min as following:
        // mMax = a.getInt(...) and setMin(...).
        mMin = a.getInt(R.styleable.EQSeekBarPreference_min, 0)
        setMax(a.getInt(R.styleable.EQSeekBarPreference_android_max, 100))
        setSeekBarIncrement(a.getInt(R.styleable.EQSeekBarPreference_seekBarIncrement, 0))
        mShowSeekBarValue = a.getBoolean(R.styleable.EQSeekBarPreference_showSeekBarValue, false)
        mUpdatesContinuously = a.getBoolean(R.styleable.EQSeekBarPreference_updatesContinuously, false)
        a.recycle()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context, attrs, defStyleAttr, 0)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.seekBarPreferenceStyle)

    @Suppress("unused")
    constructor(context: Context) : this(context, null)

    /**
     * Listener reacting to the [SeekBar] changing value by the user
     */
    private val mSeekBarChangeListener: OnSeekBarChangeListener = object : OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser && (mUpdatesContinuously || !mTrackingTouch)) {
                syncValueInternal(seekBar)
            } else {
                // We always want to update the text while the seekbar is being dragged
                updateLabelValue(progress + mMin)
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            mTrackingTouch = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            mTrackingTouch = false
            if (seekBar.progress + mMin != mSeekBarValue) {
                syncValueInternal(seekBar)
            }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        mSeekBar = holder.findViewById(R.id.seekbar) as SeekBar
        mSeekBarValueTextView = holder.findViewById(R.id.seekbar_value) as TextView
        if (mShowSeekBarValue) {
            mSeekBarValueTextView?.visibility = View.VISIBLE
        } else {
            mSeekBarValueTextView?.visibility = View.GONE
            mSeekBarValueTextView = null
        }

        if (mSeekBar == null) {
            Log.e(TAG, "SeekBar view is null in onBindViewHolder.")
            return
        }
        mSeekBar?.setOnSeekBarChangeListener(mSeekBarChangeListener)
        mSeekBar?.max = mMax - mMin
        // If the increment is not zero, use that. Otherwise, use the default mKeyProgressIncrement
        // in AbsSeekBar when it's zero. This default increment value is set by AbsSeekBar
        // after calling setMax. That's why it's important to call setKeyProgressIncrement after
        // calling setMax() since setMax() can change the increment value.
        if (mSeekBarIncrement != 0) {
            mSeekBar?.keyProgressIncrement = mSeekBarIncrement
        } else {
            mSeekBarIncrement = mSeekBar!!.keyProgressIncrement
        }

        mSeekBar?.progress = mSeekBarValue - mMin
        updateLabelValue(mSeekBarValue)
        mSeekBar?.isEnabled = isEnabled
    }

    override fun onSetInitialValue(defaultVal: Any?) {
        var defaultValue = defaultVal
        if (defaultValue == null) {
            defaultValue = 0
        }
        setValue(getPersistedInt((defaultValue as Int?)!!))
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getInt(index, 0)
    }

    @Suppress("unused")
    fun getMin(): Int {
        return mMin
    }

    @Suppress("unused")
    fun setMin(minVal: Int) {
        var min = minVal
        if (min > mMax) {
            min = mMax
        }
        if (min != mMin) {
            mMin = min
            notifyChanged()
        }
    }

    /**
     * Returns the amount of increment change via each arrow key click. This value is derived from
     * user's specified increment value if it's not zero. Otherwise, the default value is picked
     * from the default mKeyProgressIncrement value in [android.widget.AbsSeekBar].
     *
     * @return The amount of increment on the [SeekBar] performed after each user's arrow
     * key press
     */
    fun getSeekBarIncrement(): Int {
        return mSeekBarIncrement
    }

    /**
     * Sets the increment amount on the [SeekBar] for each arrow key press.
     *
     * @param seekBarIncrement The amount to increment or decrement when the user presses an
     * arrow key.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun setSeekBarIncrement(seekBarIncrement: Int) {
        if (seekBarIncrement != mSeekBarIncrement) {
            mSeekBarIncrement = min((mMax - mMin).toDouble(), abs(seekBarIncrement.toDouble())).toInt()
            notifyChanged()
        }
    }

    @Suppress("unused")
    fun getMax(): Int {
        return mMax
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun setMax(maxVal: Int) {
        var max = maxVal
        if (max < mMin) {
            max = mMin
        }
        if (max != mMax) {
            mMax = max
            notifyChanged()
        }
    }

    /**
     * Gets whether the current [SeekBar] value is displayed to the user.
     *
     * @return Whether the current [SeekBar] value is displayed to the user
     * @see .setShowSeekBarValue
     */
    fun getShowSeekBarValue(): Boolean {
        return mShowSeekBarValue
    }

    /**
     * Sets whether the current [SeekBar] value is displayed to the user.
     *
     * @param showSeekBarValue Whether the current [SeekBar] value is displayed to the user
     * @see .getShowSeekBarValue
     */
    fun setShowSeekBarValue(showSeekBarValue: Boolean) {
        mShowSeekBarValue = showSeekBarValue
        notifyChanged()
    }

    private fun setValueInternal(seekBarVal: Int, notifyChanged: Boolean) {
        var seekBarValue = seekBarVal
        if (seekBarValue < mMin) {
            seekBarValue = mMin
        }
        if (seekBarValue > mMax) {
            seekBarValue = mMax
        }

        if (seekBarValue != mSeekBarValue) {
            mSeekBarValue = seekBarValue
            updateLabelValue(mSeekBarValue)
            persistInt(seekBarValue)
            if (notifyChanged) {
                notifyChanged()
            }
        }
    }

    fun getValue(): Int {
        return mSeekBarValue
    }

    fun setValue(seekBarValue: Int) {
        setValueInternal(seekBarValue, true)
    }

    /**
     * Persist the [SeekBar]'s SeekBar value if callChangeListener returns true, otherwise
     * set the [SeekBar]'s value to the stored value.
     */
    fun syncValueInternal(seekBar: SeekBar) {
        val seekBarValue = mMin + seekBar.progress
        if (seekBarValue != mSeekBarValue) {
            if (callChangeListener(seekBarValue)) {
                setValueInternal(seekBarValue, false)
            } else {
                seekBar.progress = mSeekBarValue - mMin
                updateLabelValue(mSeekBarValue)
            }
        }
    }

    /**
     * Attempts to update the TextView label that displays the current value.
     *
     * @param value the value to display next to the [SeekBar]
     */
    fun updateLabelValue(value: Int) {
        if (mSeekBarValueTextView != null) {
            val floatValue = value.toFloat() / 10f
            var textToShow = "0"
            if (value > 0) {
                textToShow = "+%.1f".format(floatValue)
            } else if (value < 0) {
                textToShow = "%.1f".format(floatValue)
            }
            mSeekBarValueTextView?.text = "$textToShow dB"
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        if (isPersistent) {
            // No need to save instance state since it's persistent
            return superState
        }

        // Save the instance state
        val myState = SavedState(superState)
        myState.mSeekBarValue = mSeekBarValue
        myState.mMin = mMin
        myState.mMax = mMax
        return myState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state == null || state.javaClass != SavedState::class.java) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state)
            return
        }

        // Restore the instance state
        val myState = state as SavedState
        super.onRestoreInstanceState(myState.superState)
        mSeekBarValue = myState.mSeekBarValue
        mMin = myState.mMin
        mMax = myState.mMax
        notifyChanged()
    }

    /**
     * SavedState, a subclass of [BaseSavedState], will store the state of this preference.
     *
     * It is important to always call through to super methods.
     */
    private class SavedState : BaseSavedState {
        var mSeekBarValue: Int = 0
        var mMin: Int = 0
        var mMax: Int = 0

        constructor(source: Parcel) : super(source) {
            // Restore the click counter
            mSeekBarValue = source.readInt()
            mMin = source.readInt()
            mMax = source.readInt()
        }

        constructor(superState: Parcelable?) : super(superState)

        override fun writeToParcel(dest: Parcel, flags: Int) {
            super.writeToParcel(dest, flags)

            // Save the click counter
            dest.writeInt(mSeekBarValue)
            dest.writeInt(mMin)
            dest.writeInt(mMax)
        }

        companion object {
            @Suppress("unused")
            @JvmField
            val CREATOR: Creator<SavedState?> = object : Creator<SavedState?> {
                override fun createFromParcel(`in`: Parcel): SavedState {
                    return SavedState(`in`)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

}
