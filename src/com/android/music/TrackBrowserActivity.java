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

import android.app.ListActivity;
import android.content.*;
import android.database.AbstractCursor;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaFile;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.Playlists;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.Arrays;
import java.util.Map;

public class TrackBrowserActivity extends ListActivity
        implements View.OnCreateContextMenuListener, MusicUtils.Defs
{
    private final int Q_SELECTED = CHILD_MENU_BASE;
    private final int Q_ALL = CHILD_MENU_BASE + 1;
    private final int SAVE_AS_PLAYLIST = CHILD_MENU_BASE + 2;
    private final int PLAY_ALL = CHILD_MENU_BASE + 3;
    private final int CLEAR_PLAYLIST = CHILD_MENU_BASE + 4;
    private final int REMOVE = CHILD_MENU_BASE + 5;

    private final int PLAY_NOW = 0;
    private final int ADD_TO_QUEUE = 1;
    private final int PLAY_NEXT = 2;
    private final int JUMP_TO = 3;
    private final int CLEAR_HISTORY = 4;
    private final int CLEAR_ALL = 5;

    private static final String LOGTAG = "TrackBrowser";

    private String[] mCursorCols;
    private String[] mPlaylistMemberCols;
    private boolean mDeletedOneRow = false;
    private boolean mEditMode = false;
    private String mCurrentTrackName;

    public TrackBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (icicle != null) {
            mSelectedId = icicle.getLong("selectedtrack");
            mAlbumId = icicle.getString("album");
            mArtistId = icicle.getString("artist");
            mPlaylist = icicle.getString("playlist");
            mGenre = icicle.getString("genre");
            mEditMode = icicle.getBoolean("editmode", false);
        } else {
            mAlbumId = getIntent().getStringExtra("album");
            // If we have an album, show everything on the album, not just stuff
            // by a particular artist.
            Intent intent = getIntent();
            mArtistId = intent.getStringExtra("artist");
            mPlaylist = intent.getStringExtra("playlist");
            mGenre = intent.getStringExtra("genre");
            mEditMode = intent.getAction().equals(Intent.ACTION_EDIT);
        }

        mCursorCols = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.TITLE_KEY,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.DURATION
        };
        mPlaylistMemberCols = new String[] {
                MediaStore.Audio.Playlists.Members._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.TITLE_KEY,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                MediaStore.Audio.Playlists.Members.AUDIO_ID
        };

        MusicUtils.bindToService(this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        registerReceiver(mScanListener, f);

        init();
        //Debug.startMethodTracing();
    }

    @Override
    public void onDestroy() {
        //Debug.stopMethodTracing();
        MusicUtils.unbindFromService(this);
        try {
            if ("nowplaying".equals(mPlaylist)) {
                unregisterReceiver(mNowPlayingListener);
            } else {
                unregisterReceiver(mTrackListListener);
            }
        } catch (IllegalArgumentException ex) {
            // we end up here in case we never registered the listeners
        }
        if (mTrackCursor != null) {
            mTrackCursor.close();
        }
        unregisterReceiver(mScanListener);
        super.onDestroy();
   }
    
    @Override
    public void onResume() {
        super.onResume();
        if (mTrackCursor != null) {
            getListView().invalidateViews();
        }
        MusicUtils.setSpinnerState(this);
    }
    @Override
    public void onPause() {
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.setSpinnerState(TrackBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        public void handleMessage(Message msg) {
            init();
            if (mTrackCursor == null) {
                sendEmptyMessageDelayed(0, 1000);
            }
        }
    };
    
    public void onSaveInstanceState(Bundle outcicle) {
        // need to store the selected item so we don't lose it in case
        // of an orientation switch. Otherwise we could lose it while
        // in the middle of specifying a playlist to add the item to.
        outcicle.putLong("selectedtrack", mSelectedId);
        outcicle.putString("artist", mArtistId);
        outcicle.putString("album", mAlbumId);
        outcicle.putString("playlist", mPlaylist);
        outcicle.putString("genre", mGenre);
        outcicle.putBoolean("editmode", mEditMode);
        super.onSaveInstanceState(outcicle);
    }
    
    public void init() {

        mTrackCursor = getTrackCursor(null);

        setContentView(R.layout.media_picker_activity);
        mTrackList = (ListView) findViewById(android.R.id.list);
        if (mEditMode) {
            //((TouchInterceptor) mTrackList).setDragListener(mDragListener);
            ((TouchInterceptor) mTrackList).setDropListener(mDropListener);
            ((TouchInterceptor) mTrackList).setRemoveListener(mRemoveListener);
        }

        if (mTrackCursor == null) {
            MusicUtils.displayDatabaseError(this);
            return;
        }

        int numresults = mTrackCursor.getCount();
        if (numresults > 0) {
            mTrackCursor.moveToFirst();

            CharSequence fancyName = null;
            if (mAlbumId != null) {
                fancyName = mTrackCursor.getString(mTrackCursor.getColumnIndex(MediaStore.Audio.Media.ALBUM));
                // For compilation albums show only the album title,
                // but for regular albums show "artist - album".
                // To determine whether something is a compilation
                // album, do a query for the artist + album of the
                // first item, and see if it returns the same number
                // of results as the album query.
                String where = MediaStore.Audio.Media.ALBUM_ID + "='" + mAlbumId +
                        "' AND " + MediaStore.Audio.Media.ARTIST_ID + "='" + 
                        mTrackCursor.getString(mTrackCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID)) + "'";
                Cursor cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[] {MediaStore.Audio.Media.ALBUM}, where, null, null);
                if (cursor != null) {
                    if (cursor.getCount() != numresults) {
                        // compilation album
                        fancyName = mTrackCursor.getString(mTrackCursor.getColumnIndex(MediaStore.Audio.Media.ALBUM));
                    }    
                    cursor.deactivate();
                }
            } else if (mPlaylist != null) {
                if (mPlaylist.equals("nowplaying")) {
                    if (MusicUtils.getCurrentShuffleMode() == MediaPlaybackService.SHUFFLE_AUTO) {
                        fancyName = getText(R.string.partyshuffle_title);
                    } else {
                        fancyName = getText(R.string.nowplaying_title);
                    }
                } else {
                    String [] cols = new String [] {
                    MediaStore.Audio.Playlists.NAME
                    };
                    Cursor cursor = MusicUtils.query(this,
                            ContentUris.withAppendedId(Playlists.EXTERNAL_CONTENT_URI, Long.valueOf(mPlaylist)),
                            cols, null, null, null);
                    if (cursor != null) {
                        if (cursor.getCount() != 0) {
                            cursor.moveToFirst();
                            fancyName = cursor.getString(0);
                        }
                        cursor.deactivate();
                    }
                }
            } else if (mGenre != null) {
                String [] cols = new String [] {
                MediaStore.Audio.Genres.NAME
                };
                Cursor cursor = MusicUtils.query(this,
                        ContentUris.withAppendedId(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI, Long.valueOf(mGenre)),
                        cols, null, null, null);
                if (cursor != null) {
                    if (cursor.getCount() != 0) {
                        cursor.moveToFirst();
                        fancyName = cursor.getString(0);
                    }
                    cursor.deactivate();
                }
            }

            if (fancyName != null) {
                setTitle(fancyName);
            } else {
                setTitle(R.string.tracks_title);
            }
        } else {
            setTitle(R.string.no_tracks_title);
        }

        TrackListAdapter adapter = new TrackListAdapter(
                this,
                mEditMode ? R.layout.edit_track_list_item : R.layout.track_list_item,
                mTrackCursor,
                new String[] {},
                new int[] {},
                "nowplaying".equals(mPlaylist));

        setListAdapter(adapter);
        ListView lv = getListView();
        lv.setOnCreateContextMenuListener(this);
        lv.setCacheColorHint(0);
        if (!mEditMode) {
            lv.setTextFilterEnabled(true);
        }

        // When showing the queue, position the selection on the currently playing track
        // Otherwise, position the selection on the first matching artist, if any
        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.META_CHANGED);
        f.addAction(MediaPlaybackService.QUEUE_CHANGED);
        if ("nowplaying".equals(mPlaylist)) {
            try {
                int cur = MusicUtils.sService.getQueuePosition();
                setSelection(cur);
                registerReceiver(mNowPlayingListener, new IntentFilter(f));
                mNowPlayingListener.onReceive(this, new Intent(MediaPlaybackService.META_CHANGED));
            } catch (RemoteException ex) {
            }
        } else {
            String key = getIntent().getStringExtra("artist");
            if (key != null) {
                int keyidx = mTrackCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST_ID);
                mTrackCursor.moveToFirst();
                while (! mTrackCursor.isAfterLast()) {
                    String artist = mTrackCursor.getString(keyidx);
                    if (artist.equals(key)) {
                        setSelection(mTrackCursor.getPosition());
                        break;
                    }
                    mTrackCursor.moveToNext();
                }
            }
            registerReceiver(mTrackListListener, new IntentFilter(f));
            mTrackListListener.onReceive(this, new Intent(MediaPlaybackService.META_CHANGED));
        }
    }
    
    private TouchInterceptor.DragListener mDragListener =
        new TouchInterceptor.DragListener() {
        public void drag(int from, int to) {
            if (mTrackCursor instanceof NowPlayingCursor) {
                NowPlayingCursor c = (NowPlayingCursor) mTrackCursor;
                c.moveItem(from, to);
                ((TrackListAdapter)getListAdapter()).notifyDataSetChanged();
                getListView().invalidateViews();
                mDeletedOneRow = true;
            }
        }
    };
    private TouchInterceptor.DropListener mDropListener =
        new TouchInterceptor.DropListener() {
        public void drop(int from, int to) {
            if (mTrackCursor instanceof NowPlayingCursor) {
                // update the currently playing list
                NowPlayingCursor c = (NowPlayingCursor) mTrackCursor;
                c.moveItem(from, to);
                ((TrackListAdapter)getListAdapter()).notifyDataSetChanged();
                getListView().invalidateViews();
                mDeletedOneRow = true;
            } else {
                // update a saved playlist
                int colidx = mTrackCursor.getColumnIndex(MediaStore.Audio.Playlists.Members.PLAY_ORDER);
                if (from < to) {
                    // move the item to somewhere later in the list
                    mTrackCursor.moveToPosition(to);
                    int toidx = mTrackCursor.getInt(colidx);
                    mTrackCursor.moveToPosition(from);
                    mTrackCursor.updateInt(colidx, toidx);
                    for (int i = from + 1; i <= to; i++) {
                        mTrackCursor.moveToPosition(i);
                        mTrackCursor.updateInt(colidx, i - 1);
                    }
                    mTrackCursor.commitUpdates();
                } else if (from > to) {
                    // move the item to somewhere earlier in the list
                    mTrackCursor.moveToPosition(to);
                    int toidx = mTrackCursor.getInt(colidx);
                    mTrackCursor.moveToPosition(from);
                    mTrackCursor.updateInt(colidx, toidx);
                    for (int i = from - 1; i >= to; i--) {
                        mTrackCursor.moveToPosition(i);
                        mTrackCursor.updateInt(colidx, i + 1);
                    }
                    mTrackCursor.commitUpdates();
                }
            }
        }
    };
    
    private TouchInterceptor.RemoveListener mRemoveListener =
        new TouchInterceptor.RemoveListener() {
        public void remove(int which) {
            removePlaylistItem(which);
        }
    };

    private void removePlaylistItem(int which) {
        View v = mTrackList.getChildAt(which - mTrackList.getFirstVisiblePosition());
        try {
            if (MusicUtils.sService != null
                    && which != MusicUtils.sService.getQueuePosition()) {
                mDeletedOneRow = true;
            }
        } catch (RemoteException e) {
            // Service died, so nothing playing.
            mDeletedOneRow = true;
        }
        v.setVisibility(View.GONE);
        mTrackList.invalidateViews();
        mTrackCursor.moveToPosition(which);
        mTrackCursor.deleteRow();
        mTrackCursor.commitUpdates();
        v.setVisibility(View.VISIBLE);
        mTrackList.invalidateViews();
    }
    
    private BroadcastReceiver mTrackListListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            getListView().invalidateViews();
        }
    };

    private BroadcastReceiver mNowPlayingListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(MediaPlaybackService.META_CHANGED)) {
                getListView().invalidateViews();
            } else if (intent.getAction().equals(MediaPlaybackService.QUEUE_CHANGED)) {
                if (mDeletedOneRow) {
                    // This is the notification for a single row that was
                    // deleted previously, which is already reflected in
                    // the UI.
                    mDeletedOneRow = false;
                    return;
                }
                mTrackCursor.close();
                mTrackCursor = new NowPlayingCursor(MusicUtils.sService, mCursorCols);
                if (mTrackCursor.getCount() == 0) {
                    finish();
                    return;
                }
                ((TrackListAdapter)getListAdapter()).changeCursor(mTrackCursor);
                getListView().invalidateViews();
            }
        }
    };
    
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfoIn) {
        menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);
        SubMenu sub = menu.addSubMenu(0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
        MusicUtils.makePlaylistMenu(this, sub);
        if (mEditMode) {
            menu.add(0, REMOVE, 0, R.string.remove_from_playlist);
        }
        menu.add(0, USE_AS_RINGTONE, 0, R.string.ringtone_menu);
        menu.add(0, DELETE_ITEM, 0, R.string.delete_item);
        AdapterContextMenuInfo mi = (AdapterContextMenuInfo) menuInfoIn;
        mSelectedPosition =  mi.position;
        mTrackCursor.moveToPosition(mSelectedPosition);
        int id_idx = mTrackCursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID);
        if (id_idx < 0 ) {
            mSelectedId = mi.id;
        } else {
            mSelectedId = mTrackCursor.getInt(id_idx);
        }
        mCurrentTrackName = mTrackCursor.getString(mTrackCursor.getColumnIndex(MediaStore.Audio.Media.TITLE));
        menu.setHeaderTitle(mCurrentTrackName);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PLAY_SELECTION: {
                // play the track
                int position = mSelectedPosition;
                MusicUtils.playAll(this, mTrackCursor, position);
                return true;
            }

            case QUEUE: {
                int [] list = new int[] { (int) mSelectedId };
                MusicUtils.addToCurrentPlaylist(this, list);
                return true;
            }

            case NEW_PLAYLIST: {
                Intent intent = new Intent();
                intent.setClass(this, CreatePlaylist.class);
                startActivityForResult(intent, NEW_PLAYLIST);
                return true;
            }

            case PLAYLIST_SELECTED: {
                int [] list = new int[] { (int) mSelectedId };
                int playlist = item.getIntent().getIntExtra("playlist", 0);
                MusicUtils.addToPlaylist(this, list, playlist);
                return true;
            }

            case USE_AS_RINGTONE:
                // Set the system setting to make this the current ringtone
                MusicUtils.setRingtone(this, mSelectedId);
                return true;

            case DELETE_ITEM: {
                int [] list = new int[1];
                list[0] = (int) mSelectedId;
                Bundle b = new Bundle();
                b.putString("description", mCurrentTrackName);
                b.putIntArray("items", list);
                Intent intent = new Intent();
                intent.setClass(this, DeleteItems.class);
                intent.putExtras(b);
                startActivityForResult(intent, -1);
                return true;
            }
            
            case REMOVE:
                removePlaylistItem(mSelectedPosition);
                return true;
        }
        return super.onContextItemSelected(item);
    }

    // In order to use alt-up/down as a shortcut for moving the selected item
    // in the list, we need to override dispatchKeyEvent, not onKeyDown.
    // (onKeyDown never sees these events, since they are handled by the list)
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mPlaylist != null && event.getMetaState() != 0 && event.isDown()) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveItem(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveItem(false);
                    return true;
                case KeyEvent.KEYCODE_DEL:
                    removeItem();
                    return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private void removeItem() {
        int curcount = mTrackCursor.getCount();
        int curpos = mTrackList.getSelectedItemPosition();
        if (curcount == 0 || curpos < 0) {
            return;
        }
        
        if ("nowplaying".equals(mPlaylist)) {
            // remove track from queue

            // Work around bug 902971. To get quick visual feedback
            // of the deletion of the item, hide the selected view.
            try {
                if (curpos != MusicUtils.sService.getQueuePosition()) {
                    mDeletedOneRow = true;
                }
            } catch (RemoteException ex) {
            }
            View v = mTrackList.getSelectedView();
            v.setVisibility(View.GONE);
            mTrackList.invalidateViews();
            mTrackCursor.moveToPosition(curpos);
            mTrackCursor.deleteRow();
            mTrackCursor.commitUpdates();
            v.setVisibility(View.VISIBLE);
            mTrackList.invalidateViews();
        } else {
            // remove track from playlist
            mTrackCursor.moveToPosition(curpos);
            mTrackCursor.deleteRow();
            mTrackCursor.commitUpdates();
            curcount--;
            if (curcount == 0) {
                finish();
            } else {
                mTrackList.setSelection(curpos < curcount ? curpos : curcount);
            }
        }
    }
    
    private void moveItem(boolean up) {
        int curcount = mTrackCursor.getCount(); 
        int curpos = mTrackList.getSelectedItemPosition();
        if ( (up && curpos < 1) || (!up  && curpos >= curcount - 1)) {
            return;
        }

        if (mTrackCursor instanceof NowPlayingCursor) {
            NowPlayingCursor c = (NowPlayingCursor) mTrackCursor;
            c.moveItem(curpos, up ? curpos - 1 : curpos + 1);
            ((TrackListAdapter)getListAdapter()).notifyDataSetChanged();
            getListView().invalidateViews();
            mDeletedOneRow = true;
            if (up) {
                mTrackList.setSelection(curpos - 1);
            } else {
                mTrackList.setSelection(curpos + 1);
            }
        } else {
            int colidx = mTrackCursor.getColumnIndex(MediaStore.Audio.Playlists.Members.PLAY_ORDER);
            mTrackCursor.moveToPosition(curpos);
            int currentplayidx = mTrackCursor.getInt(colidx);
            if (up) {
                    mTrackCursor.updateInt(colidx, currentplayidx - 1);
                    mTrackCursor.moveToPrevious();
            } else {
                    mTrackCursor.updateInt(colidx, currentplayidx + 1);
                    mTrackCursor.moveToNext();
            }
            mTrackCursor.updateInt(colidx, currentplayidx);
            mTrackCursor.commitUpdates();
        }
    }
    
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id)
    {
        if (mTrackCursor.getCount() == 0) {
            return;
        }
        MusicUtils.playAll(this, mTrackCursor, position);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        /* This activity is used for a number of different browsing modes, and the menu can
         * be different for each of them:
         * - all tracks, optionally restricted to an album, artist or playlist
         * - the list of currently playing songs
         */
        super.onCreateOptionsMenu(menu);
        if (mPlaylist == null) {
            menu.add(0, PLAY_ALL, 0, R.string.play_all).setIcon(R.drawable.ic_menu_play_clip);
        }
        menu.add(0, GOTO_START, 0, R.string.goto_start).setIcon(R.drawable.ic_menu_music_library);
        menu.add(0, GOTO_PLAYBACK, 0, R.string.goto_playback).setIcon(R.drawable.ic_menu_playback)
                .setVisible(MusicUtils.isMusicLoaded());
        menu.add(0, SHUFFLE_ALL, 0, R.string.shuffle_all).setIcon(R.drawable.ic_menu_shuffle);
        if (mPlaylist != null) {
            menu.add(0, SAVE_AS_PLAYLIST, 0, R.string.save_as_playlist).setIcon(R.drawable.ic_menu_save);
            if (mPlaylist.equals("nowplaying")) {
                menu.add(0, CLEAR_PLAYLIST, 0, R.string.clear_playlist).setIcon(R.drawable.ic_menu_clear_playlist);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        Cursor cursor;
        switch (item.getItemId()) {
            case PLAY_ALL: {
                MusicUtils.playAll(this, mTrackCursor);
                return true;
            }

            case GOTO_START:
                intent = new Intent();
                intent.setClass(this, MusicBrowserActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;

            case GOTO_PLAYBACK:
                intent = new Intent("com.android.music.PLAYBACK_VIEWER");
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;
                
            case SHUFFLE_ALL:
                // Should 'shuffle all' shuffle ALL, or only the tracks shown?
                cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String [] { MediaStore.Audio.Media._ID}, 
                        MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                        MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
                if (cursor != null) {
                    MusicUtils.shuffleAll(this, cursor);
                    cursor.close();
                }
                return true;
                
            case SAVE_AS_PLAYLIST:
                intent = new Intent();
                intent.setClass(this, CreatePlaylist.class);
                startActivityForResult(intent, SAVE_AS_PLAYLIST);
                return true;
                
            case CLEAR_PLAYLIST:
                // We only clear the current playlist
                MusicUtils.clearQueue();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch (requestCode) {
            case SCAN_DONE:
                if (resultCode == RESULT_CANCELED) {
                    finish();
                } else {
                    init();
                }
                break;
                
            case NEW_PLAYLIST:
                if (resultCode == RESULT_OK) {
                    Uri uri = Uri.parse(intent.getAction());
                    if (uri != null) {
                        int [] list = new int[] { (int) mSelectedId };
                        MusicUtils.addToPlaylist(this, list, Integer.valueOf(uri.getLastPathSegment()));
                    }
                }
                break;

            case SAVE_AS_PLAYLIST:
                if (resultCode == RESULT_OK) {
                    Uri uri = Uri.parse(intent.getAction());
                    if (uri != null) {
                        int [] list = MusicUtils.getSongListForCursor(mTrackCursor);
                        int plid = Integer.parseInt(uri.getLastPathSegment());
                        MusicUtils.addToPlaylist(this, list, plid);
                    }
                }
                break;
        }
    }

    private Cursor getTrackCursor(String filterstring) {
        Cursor ret = null;
        mSortOrder = MediaStore.Audio.Media.TITLE_KEY;
        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Media.TITLE + " != ''");
        
        // Add in the filtering constraints
        String [] keywords = null;
        if (filterstring != null) {
            String [] searchWords = filterstring.split(" ");
            keywords = new String[searchWords.length];
            Collator col = Collator.getInstance();
            col.setStrength(Collator.PRIMARY);
            for (int i = 0; i < searchWords.length; i++) {
                keywords[i] = '%' + MediaStore.Audio.keyFor(searchWords[i]) + '%';
            }
            for (int i = 0; i < searchWords.length; i++) {
                where.append(" AND ");
                where.append(MediaStore.Audio.Media.ARTIST_KEY + "||");
                where.append(MediaStore.Audio.Media.ALBUM_KEY + "||");
                where.append(MediaStore.Audio.Media.TITLE_KEY + " LIKE ?");
            }
        }
        
        if (mGenre != null) {
            mSortOrder = MediaStore.Audio.Genres.Members.DEFAULT_SORT_ORDER;
            ret = MusicUtils.query(this,
                    MediaStore.Audio.Genres.Members.getContentUri("external", Integer.valueOf(mGenre)),
                    mCursorCols, where.toString(), keywords, mSortOrder);
        } else if (mPlaylist != null) {
            if (mPlaylist.equals("nowplaying")) {
                if (MusicUtils.sService != null) {
                    ret = new NowPlayingCursor(MusicUtils.sService, mCursorCols);
                    if (ret.getCount() == 0) {
                        finish();
                    }
                } else {
                    // Nothing is playing.
                }
            } else {
                mSortOrder = MediaStore.Audio.Playlists.Members.DEFAULT_SORT_ORDER;
                ret = MusicUtils.query(this,
                        MediaStore.Audio.Playlists.Members.getContentUri("external", Long.valueOf(mPlaylist)),
                        mPlaylistMemberCols, where.toString(), keywords, mSortOrder);
            }
        } else {
            if (mAlbumId != null) {
                where.append(" AND " + MediaStore.Audio.Media.ALBUM_ID + "='" + mAlbumId + "'");
                mSortOrder = MediaStore.Audio.Media.TRACK + ", " + mSortOrder;
            }
            where.append(" AND " + MediaStore.Audio.Media.IS_MUSIC + "=1");
            ret = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                mCursorCols, where.toString() , keywords, mSortOrder);
        }
        return ret;
    }

    private class NowPlayingCursor extends AbstractCursor
    {
        public NowPlayingCursor(IMediaPlaybackService service, String [] cols)
        {
            mCols = cols;
            mService  = service;
            makeNowPlayingCursor();
        }
        private void makeNowPlayingCursor() {
            mCurrentPlaylistCursor = null;
            try {
                mNowPlaying = mService.getQueue();
            } catch (RemoteException ex) {
                mNowPlaying = new int[0];
            }
            mSize = mNowPlaying.length;
            if (mSize == 0) {
                return;
            }

            StringBuilder where = new StringBuilder();
            where.append(MediaStore.Audio.Media._ID + " IN (");
            for (int i = 0; i < mSize; i++) {
                where.append(mNowPlaying[i]);
                if (i < mSize - 1) {
                    where.append(",");
                }
            }
            where.append(")");

            mCurrentPlaylistCursor = MusicUtils.query(TrackBrowserActivity.this,
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    mCols, where.toString(), null, MediaStore.Audio.Media._ID);

            if (mCurrentPlaylistCursor == null) {
                mSize = 0;
                return;
            }
            
            int size = mCurrentPlaylistCursor.getCount();
            mCursorIdxs = new int[size];
            mCurrentPlaylistCursor.moveToFirst();
            int colidx = mCurrentPlaylistCursor.getColumnIndex(MediaStore.Audio.Media._ID);
            for (int i = 0; i < size; i++) {
                mCursorIdxs[i] = mCurrentPlaylistCursor.getInt(colidx);
                mCurrentPlaylistCursor.moveToNext();
            }
            mCurrentPlaylistCursor.moveToFirst();
            mCurPos = -1;
        }

        @Override
        public int getCount()
        {
            return mSize;
        }

        @Override
        public boolean onMove(int oldPosition, int newPosition)
        {
            if (oldPosition == newPosition)
                return true;
            
            if (mNowPlaying == null || mCursorIdxs == null) {
                return false;
            }

            // The cursor doesn't have any duplicates in it, and is not ordered
            // in queue-order, so we need to figure out where in the cursor we
            // should be.
           
            int newid = mNowPlaying[newPosition];
            int crsridx = Arrays.binarySearch(mCursorIdxs, newid);
            mCurrentPlaylistCursor.moveToPosition(crsridx);
            mCurPos = newPosition;
            
            return true;
        }

        @Override
        public boolean deleteRow()
        {
            try {
                if (mService.removeTracks((int)mCurPos, (int)mCurPos) == 0) {
                    return false; // delete failed
                }
                int i = (int) mCurPos;
                mSize--;
                while (i < mSize) {
                    mNowPlaying[i] = mNowPlaying[i+1];
                    i++;
                }
                onMove(-1, (int) mCurPos);
            } catch (RemoteException ex) {
            }
            return true;
        }
        
        public void moveItem(int from, int to) {
            try {
                mService.moveQueueItem(from, to);
                mNowPlaying = mService.getQueue();
                onMove(-1, mCurPos); // update the underlying cursor
            } catch (RemoteException ex) {
            }
        }

        private void dump() {
            String where = "(";
            for (int i = 0; i < mSize; i++) {
                where += mNowPlaying[i];
                if (i < mSize - 1) {
                    where += ",";
                }
            }
            where += ")";
            Log.i("NowPlayingCursor: ", where);
        }

        @Override
        public String getString(int column)
        {
            try {
                return mCurrentPlaylistCursor.getString(column);
            } catch (Exception ex) {
                onChange(true);
                return "";
            }
        }

        @Override
        public short getShort(int column)
        {
            return mCurrentPlaylistCursor.getShort(column);
        }

        @Override
        public int getInt(int column)
        {
            try {
                return mCurrentPlaylistCursor.getInt(column);
            } catch (Exception ex) {
                onChange(true);
                return 0;
            }
        }

        @Override
        public long getLong(int column)
        {
            return mCurrentPlaylistCursor.getLong(column);
        }

        @Override
        public float getFloat(int column)
        {
            return mCurrentPlaylistCursor.getFloat(column);
        }

        @Override
        public double getDouble(int column)
        {
            return mCurrentPlaylistCursor.getDouble(column);
        }

        @Override
        public boolean isNull(int column)
        {
            return mCurrentPlaylistCursor.isNull(column);
        }

        @Override
        public String[] getColumnNames()
        {
            return mCols;
        }
        
        @Override
        public void deactivate()
        {
            if (mCurrentPlaylistCursor != null)
                mCurrentPlaylistCursor.deactivate();
        }

        @Override
        public boolean requery()
        {
            makeNowPlayingCursor();
            return true;
        }

        private String [] mCols;
        private Cursor mCurrentPlaylistCursor;     // updated in onMove
        private int mSize;          // size of the queue
        private int[] mNowPlaying;
        private int[] mCursorIdxs;
        private int mCurPos;
        private IMediaPlaybackService mService;
    }
    
    class TrackListAdapter extends SimpleCursorAdapter {
        boolean mIsNowPlaying;

        final int mTitleIdx;
        final int mArtistIdx;
        final int mAlbumIdx;
        final int mDurationIdx;
        int mAudioIdIdx;

        private final StringBuilder mBuilder = new StringBuilder();
        private final String mUnknownArtist;
        private final String mUnknownAlbum;

        class ViewHolder {
            TextView line1;
            TextView line2;
            TextView duration;
            ImageView play_indicator;
            CharArrayBuffer buffer1;
            char [] buffer2;
        }
        
        TrackListAdapter(Context context, int layout, Cursor cursor, String[] from, int[] to,
                boolean isnowplaying) {
            super(context, layout, cursor, from, to);
            mIsNowPlaying = isnowplaying;
            mUnknownArtist = context.getString(R.string.unknown_artist_name);
            mUnknownAlbum = context.getString(R.string.unknown_album_name);
            
            mTitleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            mArtistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            mAlbumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            mDurationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
            mAudioIdIdx = cursor.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID);
            if (mAudioIdIdx < 0) {
                mAudioIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
            }
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View v = super.newView(context, cursor, parent);
            ImageView iv = (ImageView) v.findViewById(R.id.icon);
            if (mEditMode) {
                iv.setVisibility(View.VISIBLE);
                iv.setImageResource(R.drawable.ic_mp_move);
                ViewGroup.LayoutParams p = iv.getLayoutParams();
                p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                iv.setVisibility(View.GONE);
            }
            
            ViewHolder vh = new ViewHolder();
            vh.line1 = (TextView) v.findViewById(R.id.line1);
            vh.line2 = (TextView) v.findViewById(R.id.line2);
            vh.duration = (TextView) v.findViewById(R.id.duration);
            vh.play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
            vh.buffer1 = new CharArrayBuffer(100);
            vh.buffer2 = new char[200];
            v.setTag(vh);
            return v;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            
            ViewHolder vh = (ViewHolder) view.getTag();
            
            cursor.copyStringToBuffer(mTitleIdx, vh.buffer1);
            vh.line1.setText(vh.buffer1.data, 0, vh.buffer1.sizeCopied);
            
            int secs = cursor.getInt(mDurationIdx) / 1000;
            if (secs == 0) {
                vh.duration.setText("");
            } else {
                vh.duration.setText(MusicUtils.makeTimeString(context, secs));
            }
            
            final StringBuilder builder = mBuilder;
            builder.delete(0, builder.length());

            String name = cursor.getString(mAlbumIdx);
            if (name == null || name.equals(MediaFile.UNKNOWN_STRING)) {
                builder.append(mUnknownAlbum);
            } else {
                builder.append(name);
            }
            builder.append('\n');
            name = cursor.getString(mArtistIdx);
            if (name == null || name.equals(MediaFile.UNKNOWN_STRING)) {
                builder.append(mUnknownArtist);
            } else {
                builder.append(name);
            }
            int len = builder.length();
            if (vh.buffer2.length < len) {
                vh.buffer2 = new char[len];
            }
            builder.getChars(0, len, vh.buffer2, 0);
            vh.line2.setText(vh.buffer2, 0, len);

            ImageView iv = vh.play_indicator;
            int id = -1;
            if (MusicUtils.sService != null) {
                // TODO: IPC call on each bind??
                try {
                    if (mIsNowPlaying) {
                        id = MusicUtils.sService.getQueuePosition();
                    } else {
                        id = MusicUtils.sService.getAudioId();
                    }
                } catch (RemoteException ex) {
                }
            }
            if ( (mIsNowPlaying && cursor.getPosition() == id) ||
                 (!mIsNowPlaying && cursor.getInt(mAudioIdIdx) == id)) {
                iv.setImageResource(R.drawable.indicator_ic_mp_playing_list);
                iv.setVisibility(View.VISIBLE);
            } else {
                iv.setVisibility(View.GONE);
            }
        }
        
        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            mTrackCursor = cursor;
        }
        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            return getTrackCursor(constraint.toString());
        }
    }

    private ListView mTrackList;
    private Cursor mTrackCursor;
    private String mAlbumId;
    private String mArtistId;
    private String mPlaylist;
    private String mGenre;
    private String mSortOrder;
    private int mSelectedPosition;
    private long mSelectedId;
}

