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
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import com.android.internal.database.ArrayListCursor;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.AudioManager;
import android.media.MediaFile;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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

public class QueryBrowserActivity extends ListActivity implements MusicUtils.Defs
{
    private final static int PLAY_NOW = 0;
    private final static int ADD_TO_QUEUE = 1;
    private final static int PLAY_NEXT = 2;
    private final static int PLAY_ARTIST = 3;
    private final static int EXPLORE_ARTIST = 4;
    private final static int PLAY_ALBUM = 5;
    private final static int EXPLORE_ALBUM = 6;
    private final static int REQUERY = 3;
    private String mSearchString = null;

    public QueryBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        MusicUtils.bindToService(this);
        
        if (icicle == null) {
            Intent intent = getIntent();
            mSearchString = intent.getStringExtra(SearchManager.QUERY);
        }
        if (mSearchString == null) {
            mSearchString = "";
        }
        init();
    }
    
    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(this);
        super.onDestroy();
    }
    
    public void init() {
        // Set the layout for this activity.  You can find it
        // in assets/res/any/layout/media_picker_activity.xml
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.query_activity);
        setTitle(R.string.search_title);
        mTrackList = (ListView) findViewById(android.R.id.list);

        mQueryCursor = getQueryCursor(mSearchString.length() == 0 ? "" : null);

        ListView lv = getListView();
        lv.setTextFilterEnabled(true);
        
        if (mQueryCursor == null) {
            MusicUtils.displayDatabaseError(this);
            setListAdapter(null);
            lv.setFocusable(false);
            return;
        }

        // Map Cursor columns to views defined in media_list_item.xml
        QueryListAdapter adapter = new QueryListAdapter(
                this,
                R.layout.track_list_item,
                mQueryCursor,
                new String[] {},
                new int[] {});

        setListAdapter(adapter);

        if (mSearchString.length() != 0) {
            // Hack. Can be removed once ListView supports starting filtering with a given string
            lv.setOnHierarchyChangeListener(mHierarchyListener);
        }
    }
    
    OnHierarchyChangeListener mHierarchyListener = new OnHierarchyChangeListener() {
        public void onChildViewAdded(View parent, View child) {
            ((ListView)parent).setOnHierarchyChangeListener(null);
            // need to do this here to be sure all the views have been initialized
            startFilteringWithString(mSearchString);
        }

        public void onChildViewRemoved(View parent, View child) {
        }
    };

    private KeyEvent eventForChar(char c) {
        int code = -1;
        if (c >= 'a' && c <= 'z') {
            code = KeyEvent.KEYCODE_A + (c - 'a');
        } else if (c >= 'A' && c <= 'Z') {
            code = KeyEvent.KEYCODE_A + (c - 'A');
        } else if (c >= '0' && c <= '9') {
            code = KeyEvent.KEYCODE_0 + (c - '0');
        }
        if (code != -1) {
            return new KeyEvent(KeyEvent.ACTION_DOWN, code);
        }
        return null;
    }
    private void startFilteringWithString(String filterstring) {
        ListView lv = getListView();
        for (int i = 0; i < filterstring.length(); i++) {
            KeyEvent event = eventForChar(filterstring.charAt(i));
            if (event != null) {
                lv.onKeyDown(event.getKeyCode(), event);
            }
        }
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
        String selectedType = mQueryCursor.getString(mQueryCursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE));
        
        if ("artist".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/album");
            intent.putExtra("artist", Long.valueOf(id).toString());
            startActivity(intent);
        } else if ("album".equals(selectedType)) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra("album", Long.valueOf(id).toString());
            startActivity(intent);
        } else if (position >= 0 && id >= 0){
            int [] list = new int[] { (int) id };
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

    private Cursor getQueryCursor(String filterstring) {
        String[] ccols = new String[] {
                "_id",   // this will be the artist, album or track ID
                MediaStore.Audio.Media.MIME_TYPE, // mimetype of audio file, or "artist" or "album"
                SearchManager.SUGGEST_COLUMN_TEXT_1,
                "data1",
                "data2"
        };
        if (filterstring == null) {
            ArrayList<ArrayList> placeholder = new ArrayList<ArrayList>();
            ArrayList<Object> row = new ArrayList<Object>(5);
            row.add(-1);
            row.add("");
            row.add("");
            row.add("");
            row.add("");
            placeholder.add(row);
            return new ArrayListCursor(ccols, placeholder);
        }
        Uri search = Uri.parse("content://media/external/audio/" + 
                SearchManager.SUGGEST_URI_PATH_QUERY + "/" + Uri.encode(filterstring));
        
        return MusicUtils.query(this, search, ccols, null, null, null);
        
    }
    
    class QueryListAdapter extends SimpleCursorAdapter {
        QueryListAdapter(Context context, int layout, Cursor cursor, String[] from, int[] to) {
            super(context, layout, cursor, from, to);
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
            
            String mimetype = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE));
            
            if (mimetype == null) {
                mimetype = "audio/";
            }
            if (mimetype.equals("artist")) {
                iv.setImageResource(R.drawable.ic_search_category_music_artist);
                String name = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1));
                String displayname = name;
                if (name.equals(MediaFile.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                }
                tv1.setText(displayname);

                int numalbums = cursor.getInt(cursor.getColumnIndex("data1"));
                int numsongs = cursor.getInt(cursor.getColumnIndex("data2"));
                
                String songs_albums = MusicUtils.makeAlbumsSongsLabel(context,
                        numalbums, numsongs, name.equals(MediaFile.UNKNOWN_STRING));
                
                tv2.setText(songs_albums);
            
            } else if (mimetype.equals("album")) {
                iv.setImageResource(R.drawable.ic_search_category_music_album);
                String name = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1));
                String displayname = name;
                if (name.equals(MediaFile.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_album_name);
                }
                tv1.setText(displayname);
                
                name = cursor.getString(cursor.getColumnIndex("data1"));
                displayname = name;
                if (name.equals(MediaFile.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                }
                tv2.setText(displayname);
                
            } else if(mimetype.startsWith("audio/") ||
                    mimetype.equals("application/ogg") ||
                    mimetype.equals("application/x-ogg")) {
                iv.setImageResource(R.drawable.ic_search_category_music_song);
                String name = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1));
                tv1.setText(name);

                String displayname = cursor.getString(cursor.getColumnIndex("data1"));
                if (name.equals(MediaFile.UNKNOWN_STRING)) {
                    displayname = context.getString(R.string.unknown_artist_name);
                }
                name = cursor.getString(cursor.getColumnIndex("data2"));
                if (name.equals(MediaFile.UNKNOWN_STRING)) {
                    name = context.getString(R.string.unknown_artist_name);
                }
                tv2.setText(displayname + " - " + name);
            }
        }
        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            mQueryCursor = cursor;
        }
        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            return getQueryCursor(constraint.toString());
        }
    }

    private ListView mTrackList;
    private Cursor mQueryCursor;
}

