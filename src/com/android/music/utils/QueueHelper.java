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

import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static com.android.music.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST;
import static com.android.music.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_SEARCH;
import static com.android.music.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_SONG;
import static com.android.music.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_PLAYLIST;
import static com.android.music.utils.MediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM;

/**
 * Utility class to help on queue related tasks.
 */
public class QueueHelper {
    private static final String TAG = LogHelper.makeLogTag(QueueHelper.class);

    public static List<MediaSession.QueueItem> getPlayingQueue(
            String mediaId, MusicProvider musicProvider) {
        // extract the browsing hierarchy from the media ID:
        String[] hierarchy = MediaIDHelper.getHierarchy(mediaId);

        if (hierarchy.length != 2) {
            LogHelper.e(TAG, "Could not build a playing queue for this mediaId: ", mediaId);
            return null;
        }

        String categoryType = hierarchy[0];
        String categoryValue = hierarchy[1];
        LogHelper.d(TAG, "Creating playing queue for ", categoryType, ",  ", categoryValue);

        Iterable<MediaMetadata> tracks = null;
        // This sample only supports genre and by_search category types.
        switch (categoryType) {
            case MEDIA_ID_MUSICS_BY_SONG:
                tracks = musicProvider.getMusicList();
                break;
            case MEDIA_ID_MUSICS_BY_ALBUM:
                tracks = musicProvider.getMusicsByAlbum(categoryValue);
                break;
            case MEDIA_ID_MUSICS_BY_ARTIST:
                LogHelper.d(TAG, "Not supported");
                break;
            default:
                break;
        }

        if (tracks == null) {
            LogHelper.e(
                    TAG, "Unrecognized category type: ", categoryType, " for mediaId ", mediaId);
            return null;
        }

        return convertToQueue(tracks, hierarchy[0], hierarchy[1]);
    }

    public static List<MediaSession.QueueItem> getPlayingQueueFromSearch(
            String query, MusicProvider musicProvider) {
        LogHelper.d(TAG, "Creating playing queue for musics from search ", query);

        return convertToQueue(musicProvider.searchMusic(query), MEDIA_ID_MUSICS_BY_SEARCH, query);
    }

    public static int getMusicIndexOnQueue(Iterable<MediaSession.QueueItem> queue, String mediaId) {
        int index = 0;
        for (MediaSession.QueueItem item : queue) {
            if (mediaId.equals(item.getDescription().getMediaId())) {
                return index;
            }
            index++;
        }
        return -1;
    }

    public static int getMusicIndexOnQueue(Iterable<MediaSession.QueueItem> queue, long queueId) {
        int index = 0;
        for (MediaSession.QueueItem item : queue) {
            if (queueId == item.getQueueId()) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static List<MediaSession.QueueItem> convertToQueue(
            Iterable<MediaMetadata> tracks, String... categories) {
        List<MediaSession.QueueItem> queue = new ArrayList<>();
        int count = 0;
        for (MediaMetadata track : tracks) {
            // We create a hierarchy-aware mediaID, so we know what the queue is about by looking
            // at the QueueItem media IDs.
            String hierarchyAwareMediaID =
                    MediaIDHelper.createMediaID(track.getDescription().getMediaId(), categories);
            long duration = track.getLong(MediaMetadata.METADATA_KEY_DURATION);
            MediaDescription.Builder descriptionBuilder = new MediaDescription.Builder();
            MediaDescription description = track.getDescription();
            Bundle extras = description.getExtras();
            if (extras == null) {
                extras = new Bundle();
            }
            extras.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            descriptionBuilder.setExtras(extras)
                    .setMediaId(hierarchyAwareMediaID)
                    .setTitle(description.getTitle())
                    .setSubtitle(track.getString(MediaMetadata.METADATA_KEY_ARTIST))
                    .setIconBitmap(description.getIconBitmap())
                    .setIconUri(description.getIconUri())
                    .setMediaUri(description.getMediaUri())
                    .setDescription(description.getDescription());

            // We don't expect queues to change after created, so we use the item index as the
            // queueId. Any other number unique in the queue would work.
            MediaSession.QueueItem item =
                    new MediaSession.QueueItem(descriptionBuilder.build(), count++);
            queue.add(item);
        }
        return queue;
    }

    /**
     * Create a random queue. For simplicity sake, instead of a random queue, we create a
     * queue using the first genre.
     *
     * @param musicProvider the provider used for fetching music.
     * @return list containing {@link android.media.session.MediaSession.QueueItem}'s
     */
    public static List<MediaSession.QueueItem> getRandomQueue(MusicProvider musicProvider) {
        Iterator<String> genres = musicProvider.getArtists().iterator();
        if (!genres.hasNext()) {
            return Collections.emptyList();
        }
        String genre = genres.next();
        Iterable<MediaMetadata> tracks = musicProvider.getMusicsByAlbum(genre);

        return convertToQueue(tracks, MEDIA_ID_MUSICS_BY_ARTIST, genre);
    }

    public static boolean isIndexPlayable(int index, List<MediaSession.QueueItem> queue) {
        return (queue != null && index >= 0 && index < queue.size());
    }
}
