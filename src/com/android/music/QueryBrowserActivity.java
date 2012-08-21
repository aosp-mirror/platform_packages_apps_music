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

import com.android.music.MusicUtils.ServiceToken;

import android.app.ListActivity;
import android.app.SearchManager;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ViewGroup.OnHierarchyChangeListener;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import java.util.ArrayList;

public class QueryBrowserActivity extends ListActivity
implements MusicUtils.Defs, ServiceConnection
{
    private final static int PLAY_NOW = 0;
    private final static int ADD_TO_QUEUE = 1;
    private final static int PLAY_NEXT = 2;
    private final static int PLAY_ARTIST = 3;
    private final static int EXPLORE_ARTIST = 4;
    private final static int PLAY_ALBUM = 5;
    private final static int EXPLORE_ALBUM = 6;
    private final static int REQUERY = 3;
    private QueryListAdapter mAdapter;
    private boolean mAdapterSent;
    private String mFilterString = "";
    private ServiceToken mToken;

    public QueryBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mAdapter = (QueryListAdapter) getLastNonConfigurationInstance();
        mToken = MusicUtils.bindToService(this, this);
        // defer the real work until we're bound to the service
    }


    public void onServiceConnected(ComponentName name, IBinder service) {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        registerReceiver(mScanListener, f);
        
        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;
        
        if (Intent.ACTION_VIEW.equals(action)) {
            // this is something we got from the search bar
            Uri uri = intent.getData();
            String path = uri.toString();
            if (path.startsWith("content://media/external/audio/media/")) {
                // This is a specific file
                String id = uri.getLastPathSegment();
                long [] list = new long[] { Long.valueOf(id) };
                MusicUtils.playAll(this, list, 0);
                finish();
                return;
            } else if (path.startsWith("content://media/external/audio/albums/")) {
                // This is an album, show the songs on it
                Intent i = new Intent(Intent.ACTION_PICK);
                i.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
                i.putExtra("album", uri.getLastPathSegment());
                startActivity(i);
                finish();
                return;
            } else if (path.startsWith("content://media/external/audio/artists/")) {
                // This is an artist, show the albums for that artist
                Intent i = new Intent(Intent.ACTION_PICK);
                i.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/album");
                i.putExtra("artist", uri.getLastPathSegment());
                startActivity(i);
                finish();
                return;
            }
        }

        mFilterString = intent.getStringExtra(SearchManager.QUERY);
        if (MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)) {
            String focus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS);
            String artist = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST);
            String album = intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM);
            String title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE);
            if (focus != null) {
                if (focus.startsWith("audio/") && title != null) {
                    mFilterString = title;
                } else if (focus.equals(MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)) {
                    if (album != null) {
                        mFilterString = album;
                        if (artist != null) {
                            mFilterString = mFilterString + " " + artist;
                        }
                    }
                } else if (focus.equals(MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)) {
                    if (artist != null) {
                        mFilterString = artist;
                    }
                }
            }
        }

        setContentView(R.layout.query_activity);
        mTrackList = getListView();
        mTrackList.setTextFilterEnabled(true);
        if (mAdapter == null) {
            mAdapter = new QueryListAdapter(
                    getApplication(),
                    this,
                    R.layout.track_list_item,
                    null, // cursor
                    new String[] {},
                    new int[] {});
            setListAdapter(mAdapter);
            if (TextUtils.isEmpty(mFilterString)) {
                getQueryCursor(mAdapter.getQueryHandler(), null);
            } else {
                mTrackList.setFilterText(mFilterString);
                mFilterString = null;
            }
        } else {
            mAdapter.setActivity(this);
            setListAdapter(mAdapter);
            mQueryCursor = mAdapter.getCursor();
            if (mQueryCursor != null) {
                init(mQueryCursor);
            } else {
                getQueryCursor(mAdapter.getQueryHandler(), mFilterString);
            }
        }
    }

    public void onServiceDisconnected(ComponentName name) {
        
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        mAdapterSent = true;
        return mAdapter;
    }
    
    @Override
    public void onPause() {
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(mToken);
        unregisterReceiver(mScanListener);
        // If we have an adapter and didn't send it off to another activity yet, we should
        // close its cursor, which we do by assigning a null cursor to it. Doing this
        // instead of closing the cursor directly keeps the framework from accessing
        // the closed cursor later.
        if (!mAdapterSent && mAdapter != null) {
            mAdapter.changeCursor(null);
        }
        // Because we pass the adapter to the next activity, we need to make
        // sure it doesn't keep a reference to this activity. We can do this
        // by clearing its DatasetObservers, which setListAdapter(null) does.
        if (getListView() != null) {
            setListAdapter(null);
        }
        mAdapter = null;
        super.onDestroy();
    }
    
    /*
     * This listener gets called when the media scanner starts up, and when the
     * sd card is unmounted.
     */
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.setSpinnerState(QueryBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mAdapter != null) {
                getQueryCursor(mAdapter.getQueryHandler(), null);
            }
            // if the query results in a null cursor, onQueryComplete() will
            // call init(), which will post a delayed message to this handler
            // in order to try again.
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch (requestCode) {
            case SCAN_DONE:
                if (resultCode == RESULT_CANCELED) {
                    finish();
                } else {
                    getQueryCursor(mAdapter.getQueryHandler(), null);
                }
                break;
        }
    }
    
    public void init(Cursor c) {

        if (mAdapter == null) {
            return;
        }
        mAdapter.changeCursor(c);

        if (mQueryCursor == null) {
            MusicUtils.displayDatabaseError(this);
            setListAdapter(null);
            mReScanHandler.sendEmptyMessageDelayed(0, 1000);
            return;
        }
        MusicUtils.hideDatabaseError(this);
    }
    
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id)
    {
        // Dialog doesn't allow us to wait for a result, so we need to store
        // the info we need for when the dialog posts its result
        mQueryCursor.moveToPosition(position);
        if (mQueryCursor.isBeforeFirst() || mQueryCursor.isAfterLast()) {
            return;
        }
        String selectedType = mQueryCursor.getString(mQueryCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.MIME_TYPE));
        
        if ("artist".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/album");
            intent.putExtra("artist", Long.valueOf(id).toString());
            startActivity(intent);
        } else if ("album".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra("album", Long.valueOf(id).toString());
            startActivity(intent);
        } else if (position >= 0 && id >= 0){
            long [] list = new long[] { id };
            MusicUtils.playAll(this, list, 0);
        } else {
            Log.e("QueryBrowser", "invalid position/id: " + position + "/" + id);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case USE_AS_RINGTONE: {
                // Set the system setting to make this the current ringtone
                MusicUtils.setRingtone(this, mTrackList.getSelectedItemId());
                return true;
            }

        }
        return super.onOptionsItemSelected(item);
    }

    private Cursor getQueryCursor(AsyncQueryHandler async, String filter) {
        if (filter == null) {
            filter = "";
        }
        String[] ccols = new String[] {
                BaseColumns._ID,   // this will be the artist, album or track ID
                MediaStore.Audio.Media.MIME_TYPE, // mimetype of audio file, or "artist" or "album"
                MediaStore.Audio.Artists.ARTIST,
                MediaStore.Audio.Albums.ALBUM,
                MediaStore.Audio.Media.TITLE,
                "data1",
                "data2"
        };

        Uri search = Uri.parse("content://media/external/audio/search/fancy/" +
                Uri.encode(filter));
        
        Cursor ret = null;
        if (async != null) {
            async.startQuery(0, null, search, ccols, null, null, null);
        } else {
            ret = MusicUtils.query(this, search, ccols, null, null, null);
        }
        return ret;
    }
    
    static class QueryListAdapter extends SimpleCursorAdapter {
        private QueryBrowserActivity mActivity = null;
        private AsyncQueryHandler mQueryHandler;
        private String mConstraint = null;
        private boolean mConstraintIsValid = false;

        class QueryHandler extends AsyncQueryHandler {
            QueryHandler(ContentResolver res) {
                super(res);
            }
            
            @Override
            protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                mActivity.init(cursor);
            }
        }

        QueryListAdapter(Context context, QueryBrowserActivity currentactivity,
                int layout, Cursor cursor, String[] from, int[] to) {
            super(context, layout, cursor, from, to);
            mActivity = currentactivity;
            mQueryHandler = new QueryHandler(context.getContentResolver());
        }

        public void setActivity(QueryBrowserActivity newactivity) {
            mActivity = newactivity;
        }
        
        public AsyncQueryHandler getQueryHandler() {
            return mQueryHandler;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            
            TextView tv1 = (TextView) view.findViewById(R.id.line1);
            TextView tv2 = (TextView) view.findViewById(R.id.line2);
            ImageView iv = (ImageView) view.findViewById(R.id.icon);
            ViewGroup.LayoutParams p = iv.getLayoutParams();
            if (p == null) {
                // seen this happen, not sure why
                DatabaseUtils.dumpCursor(cursor);
                return;
            }
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            
            String mimetype = cursor.getString(cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.MIME_TYPE));
            
            if (mimetype == null) {
                mimetype = "audio/";
            }
            if (mimetype.equals("artist")) {
                iv.setImageResource(R.drawable.ic_mp_artist_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                String displayname = name;
                boolean isunknown = false;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                    isunknown = true;
                }
                tv1.setText(displayname);

                int numalbums = cursor.getInt(cursor.getColumnIndexOrThrow("data1"));
                int numsongs = cursor.getInt(cursor.getColumnIndexOrThrow("data2"));
                
                String songs_albums = MusicUtils.makeAlbumsSongsLabel(context,
                        numalbums, numsongs, isunknown);
                
                tv2.setText(songs_albums);
            
            } else if (mimetype.equals("album")) {
                iv.setImageResource(R.drawable.albumart_mp_unknown_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Albums.ALBUM));
                String displayname = name;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_album_name);
                }
                tv1.setText(displayname);
                
                name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                displayname = name;
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                }
                tv2.setText(displayname);
                
            } else if(mimetype.startsWith("audio/") ||
                    mimetype.equals("application/ogg") ||
                    mimetype.equals("application/x-ogg")) {
                iv.setImageResource(R.drawable.ic_mp_song_list);
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media.TITLE));
                tv1.setText(name);

                String displayname = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Artists.ARTIST));
                if (displayname == null || displayname.equals(MediaStore.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                }
                name = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Albums.ALBUM));
                if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                    name = context.getString(R.string.unknown_album_name);
                }
                tv2.setText(displayname + " - " + name);
            }
        }
        @Override
        public void changeCursor(Cursor cursor) {
            if (mActivity.isFinishing() && cursor != null) {
                cursor.close();
                cursor = null;
            }
            if (cursor != mActivity.mQueryCursor) {
                mActivity.mQueryCursor = cursor;
                super.changeCursor(cursor);
            }
        }
        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            String s = constraint.toString();
            if (mConstraintIsValid && (
                    (s == null && mConstraint == null) ||
                    (s != null && s.equals(mConstraint)))) {
                return getCursor();
            }
            Cursor c = mActivity.getQueryCursor(null, s);
            mConstraint = s;
            mConstraintIsValid = true;
            return c;
        }
    }

    private ListView mTrackList;
    private Cursor mQueryCursor;
}

