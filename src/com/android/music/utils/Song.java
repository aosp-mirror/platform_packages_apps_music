/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.music.utils;

import android.media.MediaMetadata;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Holder class that encapsulates a MediaMetadata and allows the actual metadata to be modified
 * without requiring to rebuild the collections the metadata is in.
 */
public class Song implements Parcelable {
    private MediaMetadata mMetadata;
    private long mSongId;
    private long mSortKey;

    public Song(long songId, MediaMetadata metadata, Long sortKey) {
        mMetadata = metadata;
        mSongId = songId;
        if (sortKey != null) {
            mSortKey = sortKey;
        }
    }

    public long getSongId() {
        return mSongId;
    }
    public long getSortKey() {
        return mSortKey;
    }
    public void setSortKey(long sortKey) {
        mSortKey = sortKey;
    }

    public MediaMetadata getMetadata() {
        return mMetadata;
    }
    public void setMetadata(MediaMetadata metadata) {
        mMetadata = metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || o.getClass() != Song.class) {
            return false;
        }

        Song that = (Song) o;

        return mSongId == that.getSongId();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(mSongId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mSortKey);
        out.writeLong(mSongId);
        out.writeParcelable(mMetadata, flags);
    }

    public static final Parcelable.Creator<Song> CREATOR = new Parcelable.Creator<Song>() {
        public Song createFromParcel(Parcel in) {
            MediaMetadata metadata = in.readParcelable(null);
            long songId = in.readLong();
            long sortKey = in.readLong();
            return new Song(songId, metadata, sortKey);
        }

        public Song[] newArray(int size) {
            return new Song[size];
        }
    };
}
