/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaActionSound;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Log;
import com.android.music.MediaPlaybackService;
import com.android.music.MusicUtils;
import com.android.music.R;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/*
A provider of music contents to the music application, it reads external storage for any music
files, parse them and
store them in this class for future use.
 */
public class MusicProvider {
    private static final String TAG = "MusicProvider";

    // Public constants
    public static final String UNKOWN = "UNKNOWN";
    // Uri source of this track
    public static final String CUSTOM_METADATA_TRACK_SOURCE = "__SOURCE__";
    // Sort key for this tack
    public static final String CUSTOM_METADATA_SORT_KEY = "__SORT_KEY__";

    // Content select criteria
    private static final String MUSIC_SELECT_FILTER = MediaStore.Audio.Media.IS_MUSIC + " != 0";
    private static final String MUSIC_SORT_ORDER = MediaStore.Audio.Media.TITLE + " ASC";

    // Categorized caches for music track data:
    private Context mContext;
    // Album Name --> list of Metadata
    private ConcurrentMap<String, List<MediaMetadata>> mMusicListByAlbum;
    // Playlist Name --> list of Metadata
    private ConcurrentMap<String, List<MediaMetadata>> mMusicListByPlaylist;
    // Artist Name --> Map of (album name --> album metadata)
    private ConcurrentMap<String, Map<String, MediaMetadata>> mArtistAlbumDb;
    private List<MediaMetadata> mMusicList;
    private final ConcurrentMap<Long, Song> mMusicListById;
    private final ConcurrentMap<String, Song> mMusicListByMediaId;

    enum State { NON_INITIALIZED, INITIALIZING, INITIALIZED }

    private volatile State mCurrentState = State.NON_INITIALIZED;

    public MusicProvider(Context context) {
        mContext = context;
        mArtistAlbumDb = new ConcurrentHashMap<>();
        mMusicListByAlbum = new ConcurrentHashMap<>();
        mMusicListByPlaylist = new ConcurrentHashMap<>();
        mMusicListById = new ConcurrentHashMap<>();
        mMusicList = new ArrayList<>();
        mMusicListByMediaId = new ConcurrentHashMap<>();
        mMusicListByPlaylist.put(MediaIDHelper.MEDIA_ID_NOW_PLAYING, new ArrayList<>());
    }

    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    /**
     * Get an iterator over the list of artists
     *
     * @return list of artists
     */
    public Iterable<String> getArtists() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        return mArtistAlbumDb.keySet();
    }

    /**
     * Get an iterator over the list of albums
     *
     * @return list of albums
     */
    public Iterable<MediaMetadata> getAlbums() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        ArrayList<MediaMetadata> albumList = new ArrayList<>();
        for (Map<String, MediaMetadata> artist_albums : mArtistAlbumDb.values()) {
            albumList.addAll(artist_albums.values());
        }
        return albumList;
    }

    /**
     * Get an iterator over the list of playlists
     *
     * @return list of playlists
     */
    public Iterable<String> getPlaylists() {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        return mMusicListByPlaylist.keySet();
    }

    public Iterable<MediaMetadata> getMusicList() {
        return mMusicList;
    }

    /**
     * Get albums of a certain artist
     *
     */
    public Iterable<MediaMetadata> getAlbumByArtist(String artist) {
        if (mCurrentState != State.INITIALIZED || !mArtistAlbumDb.containsKey(artist)) {
            return Collections.emptyList();
        }
        return mArtistAlbumDb.get(artist).values();
    }

    /**
     * Get music tracks of the given album
     *
     */
    public Iterable<MediaMetadata> getMusicsByAlbum(String album) {
        if (mCurrentState != State.INITIALIZED || !mMusicListByAlbum.containsKey(album)) {
            return Collections.emptyList();
        }
        return mMusicListByAlbum.get(album);
    }

    /**
     * Get music tracks of the given playlist
     *
     */
    public Iterable<MediaMetadata> getMusicsByPlaylist(String playlist) {
        if (mCurrentState != State.INITIALIZED || !mMusicListByPlaylist.containsKey(playlist)) {
            return Collections.emptyList();
        }
        return mMusicListByPlaylist.get(playlist);
    }

    /**
     * Return the MediaMetadata for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    public Song getMusicById(long musicId) {
        return mMusicListById.containsKey(musicId) ? mMusicListById.get(musicId) : null;
    }

    /**
     * Return the MediaMetadata for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    public Song getMusicByMediaId(String musicId) {
        return mMusicListByMediaId.containsKey(musicId) ? mMusicListByMediaId.get(musicId) : null;
    }

    /**
     * Very basic implementation of a search that filter music tracks which title containing
     * the given query.
     *
     */
    public Iterable<MediaMetadata> searchMusic(String titleQuery) {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        ArrayList<MediaMetadata> result = new ArrayList<>();
        titleQuery = titleQuery.toLowerCase();
        for (Song song : mMusicListByMediaId.values()) {
            if (song.getMetadata()
                            .getString(MediaMetadata.METADATA_KEY_TITLE)
                            .toLowerCase()
                            .contains(titleQuery)) {
                result.add(song.getMetadata());
            }
        }
        return result;
    }

    public interface MusicProviderCallback { void onMusicCatalogReady(boolean success); }

    /**
     * Get the list of music tracks from disk and caches the track information
     * for future reference, keying tracks by musicId and grouping by genre.
     */
    public void retrieveMediaAsync(final MusicProviderCallback callback) {
        Log.d(TAG, "retrieveMediaAsync called");
        if (mCurrentState == State.INITIALIZED) {
            // Nothing to do, execute callback immediately
            callback.onMusicCatalogReady(true);
            return;
        }

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Void, Void, State>() {
            @Override
            protected State doInBackground(Void... params) {
                mCurrentState = State.INITIALIZING;
                if (retrieveMedia()) {
                    mCurrentState = State.INITIALIZED;
                } else {
                    mCurrentState = State.NON_INITIALIZED;
                }
                return mCurrentState;
            }

            @Override
            protected void onPostExecute(State current) {
                if (callback != null) {
                    callback.onMusicCatalogReady(current == State.INITIALIZED);
                }
            }
        }
                .execute();
    }

    public synchronized boolean retrieveAllPlayLists() {
        Cursor cursor = mContext.getContentResolver().query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, null, null, null, null);
        if (cursor == null) {
            Log.e(TAG, "Failed to retreive playlist: cursor is null");
            return false;
        }
        if (!cursor.moveToFirst()) {
            Log.d(TAG, "Failed to move cursor to first row (no query result)");
            cursor.close();
            return true;
        }
        int idColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists._ID);
        int nameColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists.NAME);
        int pathColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists.DATA);
        do {
            long thisId = cursor.getLong(idColumn);
            String thisPath = cursor.getString(pathColumn);
            String thisName = cursor.getString(nameColumn);
            Log.i(TAG, "PlayList ID: " + thisId + " Name: " + thisName);
            List<MediaMetadata> songList = retreivePlaylistMetadata(thisId, thisPath);
            LogHelper.i(TAG, "Found ", songList.size(), " items for playlist name: ", thisName);
            mMusicListByPlaylist.put(thisName, songList);
        } while (cursor.moveToNext());
        cursor.close();
        return true;
    }

    public synchronized List<MediaMetadata> retreivePlaylistMetadata(
            long playlistId, String playlistPath) {
        Cursor cursor = mContext.getContentResolver().query(Uri.parse(playlistPath), null,
                MediaStore.Audio.Playlists.Members.PLAYLIST_ID + " == " + playlistId, null, null);
        if (cursor == null) {
            Log.e(TAG, "Failed to retreive individual playlist: cursor is null");
            return null;
        }
        if (!cursor.moveToFirst()) {
            Log.d(TAG, "Failed to move cursor to first row (no query result for playlist)");
            cursor.close();
            return null;
        }
        List<Song> songList = new ArrayList<>();
        int idColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members._ID);
        int audioIdColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID);
        int orderColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.PLAY_ORDER);
        int audioPathColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.DATA);
        int audioNameColumn = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.TITLE);
        do {
            long thisId = cursor.getLong(idColumn);
            long thisAudioId = cursor.getLong(audioIdColumn);
            long thisOrder = cursor.getLong(orderColumn);
            String thisAudioPath = cursor.getString(audioPathColumn);
            Log.i(TAG,
                    "Playlist ID: " + playlistId + " Music ID: " + thisAudioId
                            + " Name: " + audioNameColumn);
            if (!mMusicListById.containsKey(thisAudioId)) {
                LogHelper.d(TAG, "Music does not exist");
                continue;
            }
            Song song = mMusicListById.get(thisAudioId);
            song.setSortKey(thisOrder);
            songList.add(song);
        } while (cursor.moveToNext());
        cursor.close();
        songList.sort(new Comparator<Song>() {
            @Override
            public int compare(Song s1, Song s2) {
                long key1 = s1.getSortKey();
                long key2 = s2.getSortKey();
                if (key1 < key2) {
                    return -1;
                } else if (key1 == key2) {
                    return 0;
                } else {
                    return 1;
                }
            }
        });
        List<MediaMetadata> metadataList = new ArrayList<>();
        for (Song song : songList) {
            metadataList.add(song.getMetadata());
        }
        return metadataList;
    }

    private synchronized boolean retrieveMedia() {
        Cursor cursor =
                mContext.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        null, MUSIC_SELECT_FILTER, null, MUSIC_SORT_ORDER);
        if (cursor == null) {
            Log.e(TAG, "Failed to retreive music: cursor is null");
            mCurrentState = State.NON_INITIALIZED;
            return false;
        }
        if (!cursor.moveToFirst()) {
            Log.d(TAG, "Failed to move cursor to first row (no query result)");
            cursor.close();
            return true;
        }
        int idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
        int titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
        int pathColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
        do {
            Log.i(TAG,
                    "Music ID: " + cursor.getString(idColumn)
                            + " Title: " + cursor.getString(titleColumn));
            long thisId = cursor.getLong(idColumn);
            String thisPath = cursor.getString(pathColumn);
            MediaMetadata metadata = retrievMediaMetadata(thisId, thisPath);
            Log.i(TAG, "MediaMetadata: " + metadata);
            if (metadata == null) {
                continue;
            }
            Song thisSong = new Song(thisId, metadata, null);
            // Construct per feature database
            mMusicList.add(metadata);
            mMusicListById.put(thisId, thisSong);
            mMusicListByMediaId.put(String.valueOf(thisId), thisSong);
            addMusicToAlbumList(metadata);
            addMusicToArtistList(metadata);
        } while (cursor.moveToNext());
        cursor.close();
        return true;
    }

    private synchronized MediaMetadata retrievMediaMetadata(long musicId, String musicPath) {
        LogHelper.d(TAG, "getting metadata for music: ", musicPath);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Uri contentUri = ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, musicId);
        if (!(new File(musicPath).exists())) {
            LogHelper.d(TAG, "Does not exist, deleting item");
            mContext.getContentResolver().delete(contentUri, null, null);
            return null;
        }
        retriever.setDataSource(mContext, contentUri);
        String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
        String album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        String durationString =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        long duration = durationString != null ? Long.parseLong(durationString) : 0;
        MediaMetadata.Builder metadataBuilder =
                new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, String.valueOf(musicId))
                        .putString(CUSTOM_METADATA_TRACK_SOURCE, musicPath)
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title != null ? title : UNKOWN)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, album != null ? album : UNKOWN)
                        .putString(
                                MediaMetadata.METADATA_KEY_ARTIST, artist != null ? artist : UNKOWN)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        byte[] albumArtData = retriever.getEmbeddedPicture();
        Bitmap bitmap;
        if (albumArtData != null) {
            bitmap = BitmapFactory.decodeByteArray(albumArtData, 0, albumArtData.length);
            bitmap = MusicUtils.resizeBitmap(bitmap, getDefaultAlbumArt());
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap);
        }
        retriever.release();
        return metadataBuilder.build();
    }

    private Bitmap getDefaultAlbumArt() {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeStream(
                mContext.getResources().openRawResource(R.drawable.albumart_mp_unknown), null,
                opts);
    }

    private void addMusicToAlbumList(MediaMetadata metadata) {
        String thisAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        if (thisAlbum == null) {
            thisAlbum = UNKOWN;
        }
        if (!mMusicListByAlbum.containsKey(thisAlbum)) {
            mMusicListByAlbum.put(thisAlbum, new ArrayList<>());
        }
        mMusicListByAlbum.get(thisAlbum).add(metadata);
    }

    private void addMusicToArtistList(MediaMetadata metadata) {
        String thisArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (thisArtist == null) {
            thisArtist = UNKOWN;
        }
        String thisAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        if (thisAlbum == null) {
            thisAlbum = UNKOWN;
        }
        if (!mArtistAlbumDb.containsKey(thisArtist)) {
            mArtistAlbumDb.put(thisArtist, new ConcurrentHashMap<>());
        }
        Map<String, MediaMetadata> albumsMap = mArtistAlbumDb.get(thisArtist);
        MediaMetadata.Builder builder;
        long count = 0;
        Bitmap thisAlbumArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (albumsMap.containsKey(thisAlbum)) {
            MediaMetadata album_metadata = albumsMap.get(thisAlbum);
            count = album_metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS);
            Bitmap nAlbumArt = album_metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            builder = new MediaMetadata.Builder(album_metadata);
            if (nAlbumArt != null) {
                thisAlbumArt = null;
            }
        } else {
            builder = new MediaMetadata.Builder();
            builder.putString(MediaMetadata.METADATA_KEY_ALBUM, thisAlbum)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, thisArtist);
        }
        if (thisAlbumArt != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, thisAlbumArt);
        }
        builder.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, count + 1);
        albumsMap.put(thisAlbum, builder.build());
    }

    public synchronized void updateMusic(String musicId, MediaMetadata metadata) {
        Song song = mMusicListByMediaId.get(musicId);
        if (song == null) {
            return;
        }

        String oldGenre = song.getMetadata().getString(MediaMetadata.METADATA_KEY_GENRE);
        String newGenre = metadata.getString(MediaMetadata.METADATA_KEY_GENRE);

        song.setMetadata(metadata);

        // if genre has changed, we need to rebuild the list by genre
        if (!oldGenre.equals(newGenre)) {
            //            buildListsByGenre();
        }
    }
}