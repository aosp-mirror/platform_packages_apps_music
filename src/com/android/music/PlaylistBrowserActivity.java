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

import java.text.Collator;
import java.util.ArrayList;

import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;

import com.android.internal.database.ArrayListCursor;
import android.database.Cursor;
import android.database.MergeCursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

public class PlaylistBrowserActivity extends ListActivity
    implements View.OnCreateContextMenuListener, MusicUtils.Defs
{
    private static final int DELETE_PLAYLIST = CHILD_MENU_BASE + 1;
    private static final int EDIT_PLAYLIST = CHILD_MENU_BASE + 2;
    private static final int RENAME_PLAYLIST = CHILD_MENU_BASE + 3;
    private static final int CHANGE_WEEKS = CHILD_MENU_BASE + 4;
    private static final long RECENTLY_ADDED_PLAYLIST = -1;
    private static final long ALL_SONGS_PLAYLIST = -2;

    private boolean mCreateShortcut;

    public PlaylistBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_CREATE_SHORTCUT.equals(action)) {
            mCreateShortcut = true;
        }

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        MusicUtils.bindToService(this, new ServiceConnection() {
            public void onServiceConnected(ComponentName classname, IBinder obj) {
                if (Intent.ACTION_VIEW.equals(action)) {
                    long id = Long.parseLong(intent.getExtras().getString("playlist"));
                    if (id == RECENTLY_ADDED_PLAYLIST) {
                        playRecentlyAdded();
                    } else if (id == ALL_SONGS_PLAYLIST) {
                        int [] list = MusicUtils.getAllSongs(PlaylistBrowserActivity.this);
                        if (list != null) {
                            MusicUtils.playAll(PlaylistBrowserActivity.this, list, 0);
                        }
                    } else {
                        MusicUtils.playPlaylist(PlaylistBrowserActivity.this, id);
                    }
                    finish();
                }
            }

            public void onServiceDisconnected(ComponentName classname) {
            }
        
        });
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        registerReceiver(mScanListener, f);

        init();
    }
    
    
    
    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(this);
        if (mPlaylistCursor != null) {
            mPlaylistCursor.close();
        }        
        unregisterReceiver(mScanListener);
        super.onDestroy();
    }
    
    @Override
    public void onResume() {
        super.onResume();

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
            MusicUtils.setSpinnerState(PlaylistBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        public void handleMessage(Message msg) {
            init();
            if (mPlaylistCursor == null) {
                sendEmptyMessageDelayed(0, 1000);
            }
        }
    };
    public void init() {

        mPlaylistCursor = getPlaylistCursor(null);

        if (mPlaylistCursor == null) {
            MusicUtils.displayDatabaseError(this);
            setContentView(R.layout.no_sd_card);
            return;
        }

        setContentView(R.layout.media_picker_activity);

        if (mPlaylistCursor.getCount() > 0) {
            mPlaylistCursor.moveToFirst();

            setTitle(R.string.playlists_title);
        } else {
            setTitle(R.string.no_playlists_title);
        }

        // Map Cursor columns to views defined in media_list_item.xml
        PlaylistListAdapter adapter = new PlaylistListAdapter(
                this,
                R.layout.track_list_item,
                mPlaylistCursor,
                new String[] { MediaStore.Audio.Playlists.NAME},
                new int[] { android.R.id.text1 });

        setListAdapter(adapter);
        ListView lv = getListView();
        lv.setOnCreateContextMenuListener(this);
        lv.setTextFilterEnabled(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!mCreateShortcut) {
            menu.add(0, GOTO_START, 0, R.string.goto_start).setIcon(
                    R.drawable.ic_menu_music_library);
            menu.add(0, GOTO_PLAYBACK, 0, R.string.goto_playback).setIcon(
                    R.drawable.ic_menu_playback).setVisible(MusicUtils.isMusicLoaded());
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case GOTO_START:
                intent = new Intent();
                intent.setClass(this, MusicBrowserActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                return true;

            case GOTO_PLAYBACK:
                intent = new Intent("com.android.music.PLAYBACK_VIEWER");
                startActivity(intent);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfoIn) {
        if (mCreateShortcut) {
            return;
        }

        AdapterContextMenuInfo mi = (AdapterContextMenuInfo) menuInfoIn;
        
        if (mi.id < 0) {
            menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);
            menu.add(0, EDIT_PLAYLIST, 0, R.string.edit_playlist_menu);
            mPlaylistCursor.moveToPosition(mi.position);
            menu.setHeaderTitle(mPlaylistCursor.getString(mPlaylistCursor.getColumnIndex(MediaStore.Audio.Playlists.NAME)));
        } else {
            menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);
            menu.add(0, DELETE_PLAYLIST, 0, R.string.delete_playlist_menu);
            menu.add(0, EDIT_PLAYLIST, 0, R.string.edit_playlist_menu);
            menu.add(0, RENAME_PLAYLIST, 0, R.string.rename_playlist_menu);
            mPlaylistCursor.moveToPosition(mi.position);
            menu.setHeaderTitle(mPlaylistCursor.getString(mPlaylistCursor.getColumnIndex(MediaStore.Audio.Playlists.NAME)));
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo mi = (AdapterContextMenuInfo) item.getMenuInfo();
        switch (item.getItemId()) {
            case PLAY_SELECTION:
                if (mi.id == RECENTLY_ADDED_PLAYLIST) {
                    playRecentlyAdded();
                } else {
                    MusicUtils.playPlaylist(this, mi.id);
                }
                break;
            case DELETE_PLAYLIST:
                mPlaylistCursor.moveToPosition(mi.position);
                mPlaylistCursor.deleteRow();
                mPlaylistCursor.commitUpdates();
                Toast.makeText(this, R.string.playlist_deleted_message, Toast.LENGTH_SHORT).show();
                if (mPlaylistCursor.getCount() == 0) {
                    setTitle(R.string.no_playlists_title);
                }
                break;
            case EDIT_PLAYLIST:
                if (mi.id == RECENTLY_ADDED_PLAYLIST) {
                    Intent intent = new Intent();
                    intent.setClass(this, WeekSelector.class);
                    startActivityForResult(intent, CHANGE_WEEKS);
                    return true;
                } else {
                    Intent intent = new Intent(Intent.ACTION_EDIT);
                    intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
                    intent.putExtra("playlist", Long.valueOf(mi.id).toString());
                    startActivity(intent);
                }
                break;
            case RENAME_PLAYLIST:
                Intent intent = new Intent();
                intent.setClass(this, RenamePlaylist.class);
                intent.putExtra("rename", mi.id);
                startActivityForResult(intent, RENAME_PLAYLIST);
                break;
        }
        return true;
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
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id)
    {
        if (mCreateShortcut) {
            final Intent shortcut = new Intent();
            shortcut.setAction(Intent.ACTION_VIEW);
            shortcut.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/playlist");
            shortcut.putExtra("playlist", String.valueOf(id));

            final Intent intent = new Intent();
            intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcut);
            intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, ((TextView) v.findViewById(R.id.line1)).getText());
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(
                    this, R.drawable.app_music));

            setResult(RESULT_OK, intent);
            finish();
            return;
        }
        if (id == RECENTLY_ADDED_PLAYLIST) {
            playRecentlyAdded();
        } else {
            MusicUtils.playPlaylist(this, id);
        }
    }

    private void playRecentlyAdded() {
        // do a query for all playlists in the last X weeks
        int X = MusicUtils.getIntPref(this, "numweeks", 2) * (3600 * 24 * 7);
        final String[] ccols = new String[] { MediaStore.Audio.Media._ID};
        String where = MediaStore.MediaColumns.DATE_ADDED + ">" + (System.currentTimeMillis() / 1000 - X);
        Cursor cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                ccols, where, null, MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
        
        if (cursor == null) {
            // Todo: show a message
            return;
        }
        int len = cursor.getCount();
        int [] list = new int[len];
        for (int i = 0; i < len; i++) {
            cursor.moveToNext();
            list[i] = cursor.getInt(0);
        }
        cursor.close();
        MusicUtils.playAll(this, list, 0);
    }
    
    private Cursor getPlaylistCursor(String filterstring) {
        String[] cols = new String[] {
                MediaStore.Audio.Playlists._ID,
                MediaStore.Audio.Playlists.NAME
        };

        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Playlists.NAME + " != ''");
        
        // Add in the filtering constraints
        String [] keywords = null;
        if (filterstring != null) {
            String [] searchWords = filterstring.split(" ");
            keywords = new String[searchWords.length];
            Collator col = Collator.getInstance();
            col.setStrength(Collator.PRIMARY);
            for (int i = 0; i < searchWords.length; i++) {
                keywords[i] = '%' + searchWords[i] + '%';
            }
            for (int i = 0; i < searchWords.length; i++) {
                where.append(" AND ");
                where.append(MediaStore.Audio.Playlists.NAME + " LIKE ?");
            }
        }
        
        String whereclause = where.toString();
        
        Cursor results = MusicUtils.query(this, MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
            cols, whereclause, keywords,
            MediaStore.Audio.Playlists.NAME);
        
        if (results == null) {
            return null;
        }
        ArrayList<ArrayList> autoplaylists = new ArrayList<ArrayList>();
        if (mCreateShortcut) {
            ArrayList<Object> all = new ArrayList<Object>(2);
            all.add(ALL_SONGS_PLAYLIST);
            all.add(getString(R.string.play_all));
            autoplaylists.add(all);
        }
        ArrayList<Object> recent = new ArrayList<Object>(2);
        recent.add(RECENTLY_ADDED_PLAYLIST);
        recent.add(getString(R.string.recentlyadded));
        autoplaylists.add(recent);

        ArrayListCursor autoplaylistscursor = new ArrayListCursor(cols, autoplaylists);
        
        Cursor c = new MergeCursor(new Cursor [] {autoplaylistscursor, results});
        
        return c;
    }
    
    class PlaylistListAdapter extends SimpleCursorAdapter {
        int mTitleIdx;

        PlaylistListAdapter(Context context, int layout, Cursor cursor, String[] from, int[] to) {
            super(context, layout, cursor, from, to);
            
            mTitleIdx = cursor.getColumnIndex(MediaStore.Audio.Playlists.NAME);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            
            TextView tv = (TextView) view.findViewById(R.id.line1);
            
            String name = cursor.getString(mTitleIdx);
            tv.setText(name);
            
            ImageView iv = (ImageView) view.findViewById(R.id.icon);
            iv.setImageResource(R.drawable.ic_mp_playlist_list);
            ViewGroup.LayoutParams p = iv.getLayoutParams();
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;

            iv = (ImageView) view.findViewById(R.id.play_indicator);
            iv.setVisibility(View.GONE);
        }

        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            mPlaylistCursor = cursor;
        }
        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            return getPlaylistCursor(constraint.toString());
        }
    }
    
    private Cursor mPlaylistCursor;
}

