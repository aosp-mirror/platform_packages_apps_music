/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser.MediaItem
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log

/**
 * Provides "background" audio playback capabilities, allowing the
 * user to switch between activities without stopping playback.
 */
class MediaPlaybackService : MediaBrowserService() {

    private lateinit var mSession: MediaSession

    override fun onCreate() {
        super.onCreate()

        // Start a new MediaSession
        mSession = MediaSession(this, "MediaPlaybackService")
        // Enable callbacks from MediaButtons and TransportControls
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
        val stateBuilder: PlaybackState.Builder = PlaybackState.Builder().setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)
        mSession.setPlaybackState(stateBuilder.build())
        setSessionToken(mSession.getSessionToken())
        val context: Context = getApplicationContext()
        val intent = Intent(context, MusicBrowserActivity::class.java)
        val pi: PendingIntent = PendingIntent.getActivity(
                context, 99 /*request code*/, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        mSession.setSessionActivity(pi)
    }

    override fun onStartCommand(startIntent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {}

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return null
    }

    override fun onLoadChildren(parentMediaId: String, result: Result<List<MediaItem>>) {
        result.sendResult(null)
    }

    private inner class MediaSessionCallback : MediaSession.Callback() {
        override fun onPlay() {}

        override fun onSkipToQueueItem(queueId: Long) {}

        override fun onSeekTo(position: Long) {}

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {}

        override fun onPause() {}

        override fun onStop() {}

        override fun onSkipToNext() {}

        override fun onSkipToPrevious() {}

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {}

        override fun onCustomAction(action: String, extras: Bundle?) {}
    }
}