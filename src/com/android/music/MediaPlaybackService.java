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
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;
import com.android.music.utils.*;

import java.lang.ref.WeakReference;
import java.util.*;

import static com.android.music.utils.MediaIDHelper.*;

/**
 * Provides "background" audio playback capabilities, allowing the
 * user to switch between activities without stopping playback.
 */
public class MediaPlaybackService extends MediaBrowserService implements Playback.Callback {
    private static final String TAG = LogHelper.makeLogTag(MediaPlaybackService.class);

    // Delay stopSelf by using a handler.
    private static final int STOP_DELAY = 30000;

    public static final String ACTION_CMD = "com.android.music.ACTION_CMD";
    public static final String CMD_NAME = "CMD_NAME";
    public static final String CMD_PAUSE = "CMD_PAUSE";
    public static final String CMD_REPEAT = "CMD_PAUSE";
    public static final String REPEAT_MODE = "REPEAT_MODE";
    public static final String CMD_SHUFFLE = "CMD_SHUFFLE";
    public static final String SHUFFLE_MODE = "SHUFFLE_MODE";

    public enum RepeatMode { REPEAT_NONE, REPEAT_ALL, REPEAT_CURRENT }
    public enum ShuffleMode { SHUFFLE_NONE, SHUFFLE_RANDOM }

    // Music catalog manager
    private MusicProvider mMusicProvider;
    private MediaSession mSession;
    // "Now playing" queue:
    private List<MediaSession.QueueItem> mPlayingQueue = null;
    private Sequence mQueueSeqence = createSequence(0);
    private MediaNotificationManager mMediaNotificationManager;
    // Indicates whether the service was started.
    private boolean mServiceStarted;
    private DelayedStopHandler mDelayedStopHandler = new DelayedStopHandler(this);
    private Playback mPlayback;
    // Default mode is repeat none
    private RepeatMode mRepeatMode = RepeatMode.REPEAT_NONE;
    // Default mode is shuffle none
    private ShuffleMode mShuffleMode = ShuffleMode.SHUFFLE_NONE;
    // Extra information for this session
    private Bundle mExtras;

    public MediaPlaybackService() {}

    @Override
    public void onCreate() {
        LogHelper.d(TAG, "onCreate()");
        super.onCreate();
        LogHelper.d(TAG, "Create MusicProvider");
        mPlayingQueue = new ArrayList<>();
        mMusicProvider = new MusicProvider(this);

        LogHelper.d(TAG, "Create MediaSession");
        // Start a new MediaSession
        mSession = new MediaSession(this, "MediaPlaybackService");
        // Set extra information
        mExtras = new Bundle();
        mExtras.putInt(REPEAT_MODE, mRepeatMode.ordinal());
        mExtras.putInt(SHUFFLE_MODE, mShuffleMode.ordinal());
        mSession.setExtras(mExtras);
        // Enable callbacks from MediaButtons and TransportControls
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder().setActions(
                PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE);
        mSession.setPlaybackState(stateBuilder.build());
        // MediaSessionCallback() has methods that handle callbacks from a media controller
        mSession.setCallback(new MediaSessionCallback());
        // Set the session's token so that client activities can communicate with it.
        setSessionToken(mSession.getSessionToken());

        mPlayback = new Playback(this, mMusicProvider);
        mPlayback.setState(PlaybackState.STATE_NONE);
        mPlayback.setCallback(this);
        mPlayback.start();

        Context context = getApplicationContext();
        Intent intent = new Intent(context, MusicBrowserActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                context, 99 /*request code*/, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mSession.setSessionActivity(pi);

        updatePlaybackState(null);

        mMediaNotificationManager = new MediaNotificationManager(this);
    }

    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        if (startIntent != null) {
            String action = startIntent.getAction();
            String command = startIntent.getStringExtra(CMD_NAME);
            if (ACTION_CMD.equals(action)) {
                if (CMD_PAUSE.equals(command)) {
                    if (mPlayback != null && mPlayback.isPlaying()) {
                        handlePauseRequest();
                    }
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        // Service is being killed, so make sure we release our resources
        handleStopRequest(null);

        mDelayedStopHandler.removeCallbacksAndMessages(null);
        // Always release the MediaSession to clean up resources
        // and notify associated MediaController(s).
        mSession.release();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.d(TAG,
                "OnGetRoot: clientPackageName=" + clientPackageName + "; clientUid=" + clientUid
                        + " ; rootHints=" + rootHints);
        // Allow everyone to browse
        return new BrowserRoot(MEDIA_ID_ROOT, null);
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaItem>> result) {
        Log.d(TAG, "OnLoadChildren: parentMediaId=" + parentMediaId);
        //  Browsing not allowed
        if (parentMediaId == null) {
            result.sendResult(null);
            return;
        }
        if (!mMusicProvider.isInitialized()) {
            // Use result.detach to allow calling result.sendResult from another thread:
            result.detach();

            mMusicProvider.retrieveMediaAsync(new MusicProvider.MusicProviderCallback() {
                @Override
                public void onMusicCatalogReady(boolean success) {
                    Log.d(TAG, "Received catalog result, success:  " + String.valueOf(success));
                    if (success) {
                        onLoadChildren(parentMediaId, result);
                    } else {
                        result.sendResult(Collections.emptyList());
                    }
                }
            });

        } else {
            // If our music catalog is already loaded/cached, load them into result immediately
            List<MediaItem> mediaItems = new ArrayList<>();

            switch (parentMediaId) {
                case MEDIA_ID_ROOT:
                    Log.d(TAG, "OnLoadChildren.ROOT");
                    mediaItems.add(new MediaItem(new MediaDescription.Builder()
                                                         .setMediaId(MEDIA_ID_MUSICS_BY_ARTIST)
                                                         .setTitle("Artists")
                                                         .build(),
                            MediaItem.FLAG_BROWSABLE));
                    mediaItems.add(new MediaItem(new MediaDescription.Builder()
                                                         .setMediaId(MEDIA_ID_MUSICS_BY_ALBUM)
                                                         .setTitle("Albums")
                                                         .build(),
                            MediaItem.FLAG_BROWSABLE));
                    mediaItems.add(new MediaItem(new MediaDescription.Builder()
                                                         .setMediaId(MEDIA_ID_MUSICS_BY_SONG)
                                                         .setTitle("Songs")
                                                         .build(),
                            MediaItem.FLAG_BROWSABLE));
                    mediaItems.add(new MediaItem(new MediaDescription.Builder()
                                                         .setMediaId(MEDIA_ID_MUSICS_BY_PLAYLIST)
                                                         .setTitle("Playlists")
                                                         .build(),
                            MediaItem.FLAG_BROWSABLE));
                    break;
                case MEDIA_ID_MUSICS_BY_ARTIST:
                    Log.d(TAG, "OnLoadChildren.ARTIST");
                    for (String artist : mMusicProvider.getArtists()) {
                        MediaItem item = new MediaItem(
                                new MediaDescription.Builder()
                                        .setMediaId(MediaIDHelper.createBrowseCategoryMediaID(
                                                MEDIA_ID_MUSICS_BY_ARTIST, artist))
                                        .setTitle(artist)
                                        .build(),
                                MediaItem.FLAG_BROWSABLE);
                        mediaItems.add(item);
                    }
                    break;
                case MEDIA_ID_MUSICS_BY_PLAYLIST:
                    LogHelper.d(TAG, "OnLoadChildren.PLAYLIST");
                    for (String playlist : mMusicProvider.getPlaylists()) {
                        MediaItem item = new MediaItem(
                                new MediaDescription.Builder()
                                        .setMediaId(MediaIDHelper.createBrowseCategoryMediaID(
                                                MEDIA_ID_MUSICS_BY_PLAYLIST, playlist))
                                        .setTitle(playlist)
                                        .build(),
                                MediaItem.FLAG_BROWSABLE);
                        mediaItems.add(item);
                    }
                    break;
                case MEDIA_ID_MUSICS_BY_ALBUM:
                    Log.d(TAG, "OnLoadChildren.ALBUM");
                    loadAlbum(mMusicProvider.getAlbums(), mediaItems);
                    break;
                case MEDIA_ID_MUSICS_BY_SONG:
                    Log.d(TAG, "OnLoadChildren.SONG");
                    String hierarchyAwareMediaID = MediaIDHelper.createBrowseCategoryMediaID(
                            parentMediaId, MEDIA_ID_MUSICS_BY_SONG);
                    loadSong(mMusicProvider.getMusicList(), mediaItems, hierarchyAwareMediaID);
                    break;
                default:
                    if (parentMediaId.startsWith(MEDIA_ID_MUSICS_BY_ARTIST)) {
                        String artist = MediaIDHelper.getHierarchy(parentMediaId)[1];
                        Log.d(TAG, "OnLoadChildren.SONGS_BY_ARTIST  artist=" + artist);
                        loadAlbum(mMusicProvider.getAlbumByArtist(artist), mediaItems);
                    } else if (parentMediaId.startsWith(MEDIA_ID_MUSICS_BY_ALBUM)) {
                        String album = MediaIDHelper.getHierarchy(parentMediaId)[1];
                        Log.d(TAG, "OnLoadChildren.SONGS_BY_ALBUM  album=" + album);
                        loadSong(mMusicProvider.getMusicsByAlbum(album), mediaItems, parentMediaId);
                    } else if (parentMediaId.startsWith(MEDIA_ID_MUSICS_BY_PLAYLIST)) {
                        String playlist = MediaIDHelper.getHierarchy(parentMediaId)[1];
                        LogHelper.d(TAG, "OnLoadChildren.SONGS_BY_PLAYLIST playlist=", playlist);
                        if (playlist.equals(MEDIA_ID_NOW_PLAYING) && mPlayingQueue != null
                                && mPlayingQueue.size() > 0) {
                            loadPlayingQueue(mediaItems, parentMediaId);
                        } else {
                            loadSong(mMusicProvider.getMusicsByPlaylist(playlist), mediaItems,
                                    parentMediaId);
                        }
                    } else {
                        Log.w(TAG, "Skipping unmatched parentMediaId: " + parentMediaId);
                    }
                    break;
            }
            Log.d(TAG,
                    "OnLoadChildren sending " + mediaItems.size() + " results for "
                            + parentMediaId);
            result.sendResult(mediaItems);
        }
    }

    private void loadPlayingQueue(List<MediaItem> mediaItems, String parentId) {
        for (MediaSession.QueueItem queueItem : mPlayingQueue) {
            MediaItem mediaItem =
                    new MediaItem(queueItem.getDescription(), MediaItem.FLAG_PLAYABLE);
            mediaItems.add(mediaItem);
        }
    }

    private void loadSong(
            Iterable<MediaMetadata> songList, List<MediaItem> mediaItems, String parentId) {
        for (MediaMetadata metadata : songList) {
            String hierarchyAwareMediaID =
                    MediaIDHelper.createMediaID(metadata.getDescription().getMediaId(), parentId);
            Bundle songExtra = new Bundle();
            songExtra.putLong(MediaMetadata.METADATA_KEY_DURATION,
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artistName = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            MediaItem item = new MediaItem(new MediaDescription.Builder()
                                                   .setMediaId(hierarchyAwareMediaID)
                                                   .setTitle(title)
                                                   .setSubtitle(artistName)
                                                   .setExtras(songExtra)
                                                   .build(),
                    MediaItem.FLAG_PLAYABLE);
            mediaItems.add(item);
        }
    }

    private void loadAlbum(Iterable<MediaMetadata> albumList, List<MediaItem> mediaItems) {
        for (MediaMetadata albumMetadata : albumList) {
            String albumName = albumMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
            String artistName = albumMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            Bundle albumExtra = new Bundle();
            albumExtra.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS,
                    albumMetadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS));
            MediaItem item = new MediaItem(
                    new MediaDescription.Builder()
                            .setMediaId(MediaIDHelper.createBrowseCategoryMediaID(
                                    MEDIA_ID_MUSICS_BY_ALBUM, albumName))
                            .setTitle(albumName)
                            .setSubtitle(artistName)
                            .setIconBitmap(
                                    albumMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
                            .setExtras(albumExtra)
                            .build(),
                    MediaItem.FLAG_BROWSABLE);
            mediaItems.add(item);
        }
    }

    private final class MediaSessionCallback extends MediaSession.Callback {
        @Override
        public void onPlay() {
            Log.d(TAG, "play");

            if (mPlayingQueue == null || mPlayingQueue.isEmpty()) {
                mPlayingQueue = QueueHelper.getRandomQueue(mMusicProvider);
                mSession.setQueue(mPlayingQueue);
                mSession.setQueueTitle(getString(R.string.random_queue_title));
                // start playing from the beginning of the queue
                mQueueSeqence = createSequence(mPlayingQueue.size());
            }

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                handlePlayRequest();
            }
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
            LogHelper.d(TAG, "OnSkipToQueueItem:", queueId);

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                // set the current index on queue from the music Id:
                mQueueSeqence.setCurrent(QueueHelper.getMusicIndexOnQueue(mPlayingQueue, queueId));
                // play the music
                handlePlayRequest();
            }
        }

        @Override
        public void onSeekTo(long position) {
            Log.d(TAG, "onSeekTo:" + position);
            mPlayback.seekTo((int) position);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            LogHelper.d(TAG, "playFromMediaId mediaId:", mediaId, "  extras=", extras);

            // The mediaId used here is not the unique musicId. This one comes from the
            // MediaBrowser, and is actually a "hierarchy-aware mediaID": a concatenation of
            // the hierarchy in MediaBrowser and the actual unique musicID. This is necessary
            // so we can build the correct playing queue, based on where the track was
            // selected from.
            mPlayingQueue = QueueHelper.getPlayingQueue(mediaId, mMusicProvider);
            mSession.setQueue(mPlayingQueue);
            String queueTitle = getString(R.string.browse_musics_by_genre_subtitle,
                    MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaId));
            mSession.setQueueTitle(queueTitle);
            mQueueSeqence = createSequence(mPlayingQueue.size());

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                // get the current index on queue from the media Id:
                int index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, mediaId);
                if (index < 0) {
                    LogHelper.e(TAG, "playFromMediaId: media ID ", mediaId,
                            " could not be found on queue. Ignoring.");
                } else {
                    mQueueSeqence.setCurrent(index);
                    // play the music
                    handlePlayRequest();
                }
            }
        }

        @Override
        public void onPause() {
            LogHelper.d(TAG, "pause. current state=" + mPlayback.getState());
            handlePauseRequest();
        }

        @Override
        public void onStop() {
            LogHelper.d(TAG, "stop. current state=" + mPlayback.getState());
            handleStopRequest(null);
        }

        @Override
        public void onSkipToNext() {
            LogHelper.d(TAG, "skipToNext");
            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                if (!mQueueSeqence.hasNext()) {
                    // This sample's behavior: skipping to next when in last song returns to the
                    // first song.
                    mQueueSeqence.reset();
                } else {
                    mQueueSeqence.next();
                }
            }
            if (QueueHelper.isIndexPlayable(mQueueSeqence.getCurrent(), mPlayingQueue)) {
                handlePlayRequest();
            } else {
                LogHelper.e(TAG,
                        "skipToNext: cannot skip to next. next Index=" + mQueueSeqence.getCurrent()
                                + " queue length="
                                + (mPlayingQueue == null ? "null" : mPlayingQueue.size()));
                handleStopRequest("Cannot skip");
            }
        }

        @Override
        public void onSkipToPrevious() {
            LogHelper.d(TAG, "skipToPrevious");
            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                if (!mQueueSeqence.hasPrev()) {
                    // This sample's behavior: skipping to previous when in first song restarts the
                    // first song.
                    mQueueSeqence.reset();
                } else {
                    mQueueSeqence.prev();
                }
            }
            if (QueueHelper.isIndexPlayable(mQueueSeqence.getCurrent(), mPlayingQueue)) {
                handlePlayRequest();
            } else {
                LogHelper.e(TAG,
                        "skipToPrevious: cannot skip to previous. previous Index="
                                + mQueueSeqence.getCurrent() + " queue length="
                                + (mPlayingQueue == null ? "null" : mPlayingQueue.size()));
                handleStopRequest("Cannot skip");
            }
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            LogHelper.d(TAG, "playFromSearch  query=", query);

            if (TextUtils.isEmpty(query)) {
                // A generic search like "Play music" sends an empty query
                // and it's expected that we start playing something. What will be played depends
                // on the app: favorite playlist, "I'm feeling lucky", most recent, etc.
                mPlayingQueue = QueueHelper.getRandomQueue(mMusicProvider);
            } else {
                mPlayingQueue = QueueHelper.getPlayingQueueFromSearch(query, mMusicProvider);
            }

            LogHelper.d(TAG, "playFromSearch  playqueue.length=" + mPlayingQueue.size());
            mSession.setQueue(mPlayingQueue);
            mQueueSeqence = createSequence(mPlayingQueue.size());

            if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
                // immediately start playing from the beginning of the search results
                handlePlayRequest();
            } else {
                // if nothing was found, we need to warn the user and stop playing
                handleStopRequest(getString(R.string.no_search_results));
            }
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            LogHelper.d(TAG, "onCustomAction action=", action, ", extras=", extras);
            switch (action) {
                case CMD_REPEAT:
                    mRepeatMode = RepeatMode.values()[extras.getInt(REPEAT_MODE)];
                    mExtras.putInt(REPEAT_MODE, mRepeatMode.ordinal());
                    mSession.setExtras(mExtras);
                    LogHelper.d(TAG, "modified repeatMode=", mRepeatMode);
                    break;
                case CMD_SHUFFLE:
                    mShuffleMode = ShuffleMode.values()[extras.getInt(SHUFFLE_MODE)];
                    mExtras.putInt(SHUFFLE_MODE, mShuffleMode.ordinal());
                    mSession.setExtras(mExtras);
                    LogHelper.d(TAG, "modified shuffleMode=", mShuffleMode);
                    // Shuffle mode was updated, need to update current sequence to reflect this
                    updateSequence();
                    break;
                default:
                    LogHelper.d(TAG, "Unkown action=", action);
                    break;
            }
        }
    }

    /**
     * Handle a request to play music
     */
    private void handlePlayRequest() {
        LogHelper.d(TAG, "handlePlayRequest: mState=" + mPlayback.getState());

        mDelayedStopHandler.removeCallbacksAndMessages(null);
        if (!mServiceStarted) {
            LogHelper.v(TAG, "Starting service");
            // The MusicService needs to keep running even after the calling MediaBrowser
            // is disconnected. Call startService(Intent) and then stopSelf(..) when we no longer
            // need to play media.
            startService(new Intent(getApplicationContext(), MediaPlaybackService.class));
            mServiceStarted = true;
        }

        if (!mSession.isActive()) {
            mSession.setActive(true);
        }

        if (QueueHelper.isIndexPlayable(mQueueSeqence.getCurrent(), mPlayingQueue)) {
            updateMetadata();
            mPlayback.play(mPlayingQueue.get(mQueueSeqence.getCurrent()));
        }
    }

    /**
     * Handle a request to pause music
     */
    private void handlePauseRequest() {
        LogHelper.d(TAG, "handlePauseRequest: mState=" + mPlayback.getState());
        mPlayback.pause();
        // reset the delayed stop handler.
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);
    }

    /**
     * Handle a request to stop music
     */
    private void handleStopRequest(String withError) {
        LogHelper.d(
                TAG, "handleStopRequest: mState=" + mPlayback.getState() + " error=", withError);
        mPlayback.stop(true);
        // reset the delayed stop handler.
        mDelayedStopHandler.removeCallbacksAndMessages(null);
        mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);

        updatePlaybackState(withError);

        // service is no longer necessary. Will be started again if needed.
        stopSelf();
        mServiceStarted = false;
    }

    private void updateMetadata() {
        if (!QueueHelper.isIndexPlayable(mQueueSeqence.getCurrent(), mPlayingQueue)) {
            LogHelper.e(TAG, "Can't retrieve current metadata.");
            updatePlaybackState(getResources().getString(R.string.error_no_metadata));
            return;
        }
        MediaSession.QueueItem queueItem = mPlayingQueue.get(mQueueSeqence.getCurrent());
        String musicId =
                MediaIDHelper.extractMusicIDFromMediaID(queueItem.getDescription().getMediaId());
        MediaMetadata track = mMusicProvider.getMusicByMediaId(musicId).getMetadata();
        final String trackId = track.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (!musicId.equals(trackId)) {
            IllegalStateException e = new IllegalStateException("track ID should match musicId.");
            LogHelper.e(TAG, "track ID should match musicId.", " musicId=", musicId,
                    " trackId=", trackId,
                    " mediaId from queueItem=", queueItem.getDescription().getMediaId(),
                    " title from queueItem=", queueItem.getDescription().getTitle(),
                    " mediaId from track=", track.getDescription().getMediaId(),
                    " title from track=", track.getDescription().getTitle(),
                    " source.hashcode from track=",
                    track.getString(MusicProvider.CUSTOM_METADATA_TRACK_SOURCE).hashCode(), e);
            throw e;
        }
        LogHelper.d(TAG, "Updating metadata for MusicID= " + musicId);
        mSession.setMetadata(track);

        // Set the proper album artwork on the media session, so it can be shown in the
        // locked screen and in other places.
        if (track.getDescription().getIconBitmap() == null
                && track.getDescription().getIconUri() != null) {
            String albumUri = track.getDescription().getIconUri().toString();
            AlbumArtCache.getInstance().fetch(albumUri, new AlbumArtCache.FetchListener() {
                @Override
                public void onFetched(String artUrl, Bitmap bitmap, Bitmap icon) {
                    MediaSession.QueueItem queueItem =
                            mPlayingQueue.get(mQueueSeqence.getCurrent());
                    MediaMetadata track = mMusicProvider.getMusicByMediaId(trackId).getMetadata();
                    track = new MediaMetadata
                                    .Builder(track)

                                    // set high resolution bitmap in METADATA_KEY_ALBUM_ART. This is
                                    // used, for
                                    // example, on the lockscreen background when the media session
                                    // is active.
                                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)

                                    // set small version of the album art in the DISPLAY_ICON. This
                                    // is used on
                                    // the MediaDescription and thus it should be small to be
                                    // serialized if
                                    // necessary..
                                    .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, icon)

                                    .build();

                    mMusicProvider.updateMusic(trackId, track);

                    // If we are still playing the same music
                    String currentPlayingId = MediaIDHelper.extractMusicIDFromMediaID(
                            queueItem.getDescription().getMediaId());
                    if (trackId.equals(currentPlayingId)) {
                        mSession.setMetadata(track);
                    }
                }
            });
        }
    }

    /**
     * Update the current media player state, optionally showing an error message.
     *
     * @param error if not null, error message to present to the user.
     */
    private void updatePlaybackState(String error) {
        LogHelper.d(TAG, "updatePlaybackState, playback state=" + mPlayback.getState());
        long position = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        if (mPlayback != null && mPlayback.isConnected()) {
            position = mPlayback.getCurrentStreamPosition();
        }

        PlaybackState.Builder stateBuilder =
                new PlaybackState.Builder().setActions(getAvailableActions());

        int state = mPlayback.getState();

        // If there is an error message, send it to the playback state:
        if (error != null) {
            // Error states are really only supposed to be used for errors that cause playback to
            // stop unexpectedly and persist until the user takes action to fix it.
            stateBuilder.setErrorMessage(error);
            state = PlaybackState.STATE_ERROR;
        }
        stateBuilder.setState(state, position, 1.0f, SystemClock.elapsedRealtime());

        // Set the activeQueueItemId if the current index is valid.
        if (QueueHelper.isIndexPlayable(mQueueSeqence.getCurrent(), mPlayingQueue)) {
            MediaSession.QueueItem item = mPlayingQueue.get(mQueueSeqence.getCurrent());
            stateBuilder.setActiveQueueItemId(item.getQueueId());
        }

        mSession.setPlaybackState(stateBuilder.build());

        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED) {
            mMediaNotificationManager.startNotification();
        }
    }

    private long getAvailableActions() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackState.ACTION_PLAY_FROM_SEARCH;
        if (mPlayingQueue == null || mPlayingQueue.isEmpty()) {
            return actions;
        }
        if (mPlayback.isPlaying()) {
            actions |= PlaybackState.ACTION_PAUSE;
        }
        if (mQueueSeqence.hasPrev()) {
            actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        if (mQueueSeqence.hasNext()) {
            actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        return actions;
    }

    private MediaMetadata getCurrentPlayingMusic() {
        if (QueueHelper.isIndexPlayable(mQueueSeqence.getCurrent(), mPlayingQueue)) {
            MediaSession.QueueItem item = mPlayingQueue.get(mQueueSeqence.getCurrent());
            if (item != null) {
                LogHelper.d(TAG,
                        "getCurrentPlayingMusic for musicId=", item.getDescription().getMediaId());
                return mMusicProvider
                        .getMusicByMediaId(MediaIDHelper.extractMusicIDFromMediaID(
                                item.getDescription().getMediaId()))
                        .getMetadata();
            }
        }
        return null;
    }

    /**
     * Implementation of the Playback.Callback interface
     */
    @Override
    public void onCompletion() {
        // The media player finished playing the current song, so we go ahead
        // and start the next.
        if (mPlayingQueue != null && !mPlayingQueue.isEmpty()) {
            switch (mRepeatMode) {
                case REPEAT_ALL:
                    if (mQueueSeqence.hasNext()) {
                        // Increase the index
                        mQueueSeqence.next();
                    } else {
                        // Restart queue when reaching the end
                        mQueueSeqence.reset();
                    }
                    break;
                case REPEAT_CURRENT:
                    // Do not change the index
                    break;
                case REPEAT_NONE:
                default:
                    if (mQueueSeqence.hasNext()) {
                        // Increase the index
                        mQueueSeqence.next();
                    } else {
                        // Stop the queue when reaching the end
                        handleStopRequest(null);
                    }
                    break;
            }
            handlePlayRequest();
        } else {
            // If there is nothing to play, we stop and release the resources:
            handleStopRequest(null);
        }
    }

    @Override
    public void onPlaybackStatusChanged(int state) {
        updatePlaybackState(null);
    }

    @Override
    public void onError(String error) {
        updatePlaybackState(error);
    }

    /**
     * A simple handler that stops the service if playback is not active (playing)
     */
    private static class DelayedStopHandler extends Handler {
        private final WeakReference<MediaPlaybackService> mWeakReference;

        private DelayedStopHandler(MediaPlaybackService service) {
            mWeakReference = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            MediaPlaybackService service = mWeakReference.get();
            if (service != null && service.mPlayback != null) {
                if (service.mPlayback.isPlaying()) {
                    Log.d(TAG, "Ignoring delayed stop since the media player is in use.");
                    return;
                }
                Log.d(TAG, "Stopping service with delay handler.");
                service.stopSelf();
                service.mServiceStarted = false;
            }
        }
    }

    private Sequence createSequence(int length) {
        // Create new sequence based on current shuffle mode
        if (mShuffleMode == ShuffleMode.SHUFFLE_RANDOM) {
            return new RandomSequence(length);
        }
        return new Sequence(length);
    }

    private void updateSequence() {
        // Get current playing index
        int current = mQueueSeqence.getCurrent();
        // Create new sequence with current shuffle mode
        mQueueSeqence = createSequence(mQueueSeqence.getLength());
        // Restore current playing index
        mQueueSeqence.setCurrent(current);
    }

    /*
     * Sequence of integers 0..n-1 in order
     */
    private static class Sequence {
        private final int mLength;
        private int mCurrent;

        private Sequence(int length) {
            mLength = length;
            mCurrent = 0;
        }

        void reset() {
            mCurrent = 0;
        }

        int getLength() {
            return mLength;
        }

        void setCurrent(int current) {
            if (current < 0 || current >= mLength) {
                throw new IllegalArgumentException();
            }
            mCurrent = current;
        }

        int getCurrent() {
            return mCurrent;
        }

        boolean hasNext() {
            return mCurrent < mLength - 1;
        }

        boolean hasPrev() {
            return mCurrent > 0;
        }

        void next() {
            if (!hasNext()) {
                throw new IllegalStateException();
            }
            ++mCurrent;
        }

        void prev() {
            if (!hasPrev()) {
                throw new IllegalStateException();
            }
            --mCurrent;
        }
    }

    /*
     * Random sequence of integers 0..n-1
     */
    private static class RandomSequence extends Sequence {
        private final ArrayList<Integer> mShuffledSequence;

        private RandomSequence(int length) {
            super(length);
            mShuffledSequence = new ArrayList<>(length);
            for (int i = 0; i < length; ++i) {
                mShuffledSequence.add(i);
            }
            Collections.shuffle(mShuffledSequence);
        }

        @Override
        void reset() {
            super.reset();
            Collections.shuffle(mShuffledSequence);
        }

        @Override
        void setCurrent(int current) {
            super.setCurrent(mShuffledSequence.indexOf(current));
        }

        @Override
        int getCurrent() {
            return mShuffledSequence.get(super.getCurrent());
        }
    }
}
