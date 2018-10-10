/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.music;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.*;

/**
 * Provides "background" audio playback capabilities, allowing the
 * user to switch between activities without stopping playback.
 */
public class MediaPlaybackService extends MediaBrowserService {
    private MediaSession mSession;

    public MediaPlaybackService() {}

    @Override
    public void onCreate() {
        super.onCreate();

        // Start a new MediaSession
        mSession = new MediaSession(this, "MediaPlaybackService");
        // Enable callbacks from MediaButtons and TransportControls
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder().setActions(
                PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE);
        mSession.setPlaybackState(stateBuilder.build());
        setSessionToken(mSession.getSessionToken());

        Context context = getApplicationContext();
        Intent intent = new Intent(context, MusicBrowserActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                context, 99 /*request code*/, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mSession.setSessionActivity(pi);
    }

    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {}

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return null;
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaItem>> result) {
        result.sendResult(null);
        return;
    }

    private final class MediaSessionCallback extends MediaSession.Callback {
        @Override
        public void onPlay() {}

        @Override
        public void onSkipToQueueItem(long queueId) {}

        @Override
        public void onSeekTo(long position) {}

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {}

        @Override
        public void onPause() {}

        @Override
        public void onStop() {}

        @Override
        public void onSkipToNext() {}

        @Override
        public void onSkipToPrevious() {}

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {}

        @Override
        public void onCustomAction(String action, Bundle extras) {}
    }
}
