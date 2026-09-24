package com.aarogyamandir.reels.model

import android.os.Parcel
import android.os.Parcelable

/**
 * Records that at [timeMs] (milliseconds since recording started),
 * the on-screen image switched to [imagePath].
 */
data class TimelineEvent(
    val timeMs: Long,
    val imagePath: String
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(timeMs)
        parcel.writeString(imagePath)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<TimelineEvent> {
        override fun createFromParcel(parcel: Parcel): TimelineEvent = TimelineEvent(parcel)
        override fun newArray(size: Int): Array<TimelineEvent?> = arrayOfNulls(size)
    }
}
