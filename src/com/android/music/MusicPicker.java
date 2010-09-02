/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.content.AsyncQueryHandler;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.CharArrayBuffer;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.SectionIndexer;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import java.io.IOException;
import java.text.Collator;
import java.util.Formatter;
import java.util.Locale;

/**
 * Activity allowing the user to select a music track on the device, and
 * return it to its caller.  The music picker user interface is fairly
 * extensive, providing information about each track like the music
 * application (title, author, album, duration), as well as the ability to
 * previous tracks and sort them in different orders.
 * 
 * <p>This class also illustrates how you can load data from a content
 * provider asynchronously, providing a good UI while doing so, perform
 * indexing of the content for use inside of a {@link FastScrollView}, and
 * perform filtering of the data as the user presses keys.
 */
public class MusicPicker extends ListActivity
        implements View.OnClickListener, MediaPlayer.OnCompletionListener,
        MusicUtils.Defs {
    static final boolean DBG = false;
    static final String TAG = "MusicPicker";
    
    /** Holds the previous state of the list, to restore after the async
     * query has completed. */
    static final String LIST_STATE_KEY = "liststate";
    /** Remember whether the list last had focus for restoring its state. */
    static final String FOCUS_KEY = "focused";
    /** Remember the last ordering mode for restoring state. */
    static final String SORT_MODE_KEY = "sortMode";
    
    /** Arbitrary number, doesn't matter since we only do one query type. */
    static final int MY_QUERY_TOKEN = 42;
    
    /** Menu item to sort the music list by track title. */
    static final int TRACK_MENU = Menu.FIRST;
    /** Menu item to sort the music list by album title. */
    static final int ALBUM_MENU = Menu.FIRST+1;
    /** Menu item to sort the music list by artist name. */
    static final int ARTIST_MENU = Menu.FIRST+2;
    
    /** These are the columns in the music cursor that we are interested in. */
    static final String[] CURSOR_COLS = new String[] {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.TITLE_KEY,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK
    };
    
    /** Formatting optimization to avoid creating many temporary objects. */
    static StringBuilder sFormatBuilder = new StringBuilder();
    /** Formatting optimization to avoid creating many temporary objects. */
    static Formatter sFormatter = new Formatter(sFormatBuilder, Locale.getDefault());
    /** Formatting optimization to avoid creating many temporary objects. */
    static final Object[] sTimeArgs = new Object[5];

    /** Uri to the directory of all music being displayed. */
    Uri mBaseUri;
    
    /** This is the adapter used to display all of the tracks. */
    TrackListAdapter mAdapter;
    /** Our instance of QueryHandler used to perform async background queries. */
    QueryHandler mQueryHandler;
    
    /** Used to keep track of the last scroll state of the list. */
    Parcelable mListState = null;
    /** Used to keep track of whether the list last had focus. */
    boolean mListHasFocus;
    
    /** The current cursor on the music that is being displayed. */
    Cursor mCursor;
    /** The actual sort order the user has selected. */
    int mSortMode = -1;
    /** SQL order by string describing the currently selected sort order. */
    String mSortOrder;

    /** Container of the in-screen progress indicator, to be able to hide it
     * when done loading the initial cursor. */
    View mProgressContainer;
    /** Container of the list view hierarchy, to be able to show it when done
     * loading the initial cursor. */
    View mListContainer;
    /** Set to true when the list view has been shown for the first time. */
    boolean mListShown;
    
    /** View holding the okay button. */
    View mOkayButton;
    /** View holding the cancel button. */
    View mCancelButton;
    
    /** Which track row ID the user has last selected. */
    long mSelectedId = -1;
    /** Completel Uri that the user has last selected. */
    Uri mSelectedUri;
    
    /** If >= 0, we are currently playing a track for preview, and this is its
     * row ID. */
    long mPlayingId = -1;
    
    /** This is used for playing previews of the music files. */
    MediaPlayer mMediaPlayer;
    
    /**
     * A special implementation of SimpleCursorAdapter that knows how to bind
     * our cursor data to our list item structure, and takes care of other
     * advanced features such as indexing and filtering.
     */
    class TrackListAdapter extends SimpleCursorAdapter
            implements SectionIndexer {
        final ListView mListView;
        
        private final StringBuilder mBuilder = new StringBuilder();
        private final String mUnknownArtist;
        private final String mUnknownAlbum;

        private int mIdIdx;
        private int mTitleIdx;
        private int mArtistIdx;
        private int mAlbumIdx;
        private int mDurationIdx;

        private boolean mLoading = true;
        private int mIndexerSortMode;
        private MusicAlphabetIndexer mIndexer;
        
        class ViewHolder {
            TextView line1;
            TextView line2;
            TextView duration;
            RadioButton radio;
            ImageView play_indicator;
            CharArrayBuffer buffer1;
            char [] buffer2;
        }
        
        TrackListAdapter(Context context, ListView listView, int layout,
                String[] from, int[] to) {
            super(context, layout, null, from, to);
            mListView = listView;
            mUnknownArtist = context.getString(R.string.unknown_artist_name);
            mUnknownAlbum = context.getString(R.string.unknown_album_name);
        }

        /**
         * The mLoading flag is set while we are performing a background
         * query, to avoid displaying the "No music" empty view during
         * this time.
         */
        public void setLoading(boolean loading) {
            mLoading = loading;
        }

        @Override
        public boolean isEmpty() {
            if (mLoading) {
                // We don't want the empty state to show when loading.
                return false;
            } else {
                return super.isEmpty();
            }
        }
        
        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View v = super.newView(context, cursor, parent);
            ViewHolder vh = new ViewHolder();
            vh.line1 = (TextView) v.findViewById(R.id.line1);
            vh.line2 = (TextView) v.findViewById(R.id.line2);
            vh.duration = (TextView) v.findViewById(R.id.duration);
            vh.radio = (RadioButton) v.findViewById(R.id.radio);
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
            if (name == null || name.equals("<unknown>")) {
                builder.append(mUnknownAlbum);
            } else {
                builder.append(name);
            }
            builder.append('\n');
            name = cursor.getString(mArtistIdx);
            if (name == null || name.equals("<unknown>")) {
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

            // Update the checkbox of the item, based on which the user last
            // selected.  Note that doing it this way means we must have the
            // list view update all of its items when the selected item
            // changes.
            final long id = cursor.getLong(mIdIdx);
            vh.radio.setChecked(id == mSelectedId);
            if (DBG) Log.v(TAG, "Binding id=" + id + " sel=" + mSelectedId
                    + " playing=" + mPlayingId + " cursor=" + cursor);
            
            // Likewise, display the "now playing" icon if this item is
            // currently being previewed for the user.
            ImageView iv = vh.play_indicator;
            if (id == mPlayingId) {
                iv.setImageResource(R.drawable.indicator_ic_mp_playing_list);
                iv.setVisibility(View.VISIBLE);
            } else {
                iv.setVisibility(View.GONE);
            }
        }
        
        /**
         * This method is called whenever we receive a new cursor due to
         * an async query, and must take care of plugging the new one in
         * to the adapter.
         */
        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            if (DBG) Log.v(TAG, "Setting cursor to: " + cursor
                    + " from: " + MusicPicker.this.mCursor);
            
            MusicPicker.this.mCursor = cursor;
            
            if (cursor != null) {
                // Retrieve indices of the various columns we are interested in.
                mIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                mTitleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                mArtistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                mAlbumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                mDurationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                // If the sort mode has changed, or we haven't yet created an
                // indexer one, then create a new one that is indexing the
                // appropriate column based on the sort mode.
                if (mIndexerSortMode != mSortMode || mIndexer == null) {
                    mIndexerSortMode = mSortMode;
                    int idx = mTitleIdx;
                    switch (mIndexerSortMode) {
                        case ARTIST_MENU:
                            idx = mArtistIdx;
                            break;
                        case ALBUM_MENU:
                            idx = mAlbumIdx;
                            break;
                    }
                    mIndexer = new MusicAlphabetIndexer(cursor, idx,
                            getResources().getString(R.string.fast_scroll_alphabet));
                    
                // If we have a valid indexer, but the cursor has changed since
                // its last use, then point it to the current cursor.
                } else {
                    mIndexer.setCursor(cursor);
                }
            }
            
            // Ensure that the list is shown (and initial progress indicator
            // hidden) in case this is the first cursor we have gotten.
            makeListShown();
        }
        
        /**
         * This method is called from a background thread by the list view
         * when the user has typed a letter that should result in a filtering
         * of the displayed items.  It returns a Cursor, when will then be
         * handed to changeCursor.
         */
        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            if (DBG) Log.v(TAG, "Getting new cursor...");
            return doQuery(true, constraint.toString());
        }
        
        public int getPositionForSection(int section) {
            Cursor cursor = getCursor();
            if (cursor == null) {
                // No cursor, the section doesn't exist so just return 0
                return 0;
            }
            
            return mIndexer.getPositionForSection(section);
        }

        public int getSectionForPosition(int position) {
            return 0;
        }

        public Object[] getSections() {
            if (mIndexer != null) {
                return mIndexer.getSections();
            }
            return null;
        }
    }

    /**
     * This is our specialization of AsyncQueryHandler applies new cursors
     * to our state as they become available.
     */
    private final class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(Context context) {
            super(context.getContentResolver());
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            if (!isFinishing()) {
                // Update the adapter: we are no longer loading, and have
                // a new cursor for it.
                mAdapter.setLoading(false);
                mAdapter.changeCursor(cursor);
                setProgressBarIndeterminateVisibility(false);
    
                // Now that the cursor is populated again, it's possible to restore the list state
                if (mListState != null) {
                    getListView().onRestoreInstanceState(mListState);
                    if (mListHasFocus) {
                        getListView().requestFocus();
                    }
                    mListHasFocus = false;
                    mListState = null;
                }
            } else {
                cursor.close();
            }
        }
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        
        int sortMode = TRACK_MENU;
        if (icicle == null) {
            mSelectedUri = getIntent().getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI);
        } else {
            mSelectedUri = (Uri)icicle.getParcelable(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI);
            // Retrieve list state. This will be applied after the
            // QueryHandler has run
            mListState = icicle.getParcelable(LIST_STATE_KEY);
            mListHasFocus = icicle.getBoolean(FOCUS_KEY);
            sortMode = icicle.getInt(SORT_MODE_KEY, sortMode);
        }
        if (Intent.ACTION_GET_CONTENT.equals(getIntent().getAction())) {
            mBaseUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        } else {
            mBaseUri = getIntent().getData();
            if (mBaseUri == null) {
                Log.w("MusicPicker", "No data URI given to PICK action");
                finish();
                return;
            }
        }
        
        setContentView(R.layout.music_picker);

        mSortOrder = MediaStore.Audio.Media.TITLE_KEY;

        final ListView listView = getListView();

        listView.setItemsCanFocus(false);
        
        mAdapter = new TrackListAdapter(this, listView,
                R.layout.music_picker_item, new String[] {},
                new int[] {});

        setListAdapter(mAdapter);
        
        listView.setTextFilterEnabled(true);
        
        // We manually save/restore the listview state
        listView.setSaveEnabled(false);

        mQueryHandler = new QueryHandler(this);
        
        mProgressContainer = findViewById(R.id.progressContainer);
        mListContainer = findViewById(R.id.listContainer);
        
        mOkayButton = findViewById(R.id.okayButton);
        mOkayButton.setOnClickListener(this);
        mCancelButton = findViewById(R.id.cancelButton);
        mCancelButton.setOnClickListener(this);
        
        // If there is a currently selected Uri, then try to determine who
        // it is.
        if (mSelectedUri != null) {
            Uri.Builder builder = mSelectedUri.buildUpon();
            String path = mSelectedUri.getEncodedPath();
            int idx = path.lastIndexOf('/');
            if (idx >= 0) {
                path = path.substring(0, idx);
            }
            builder.encodedPath(path);
            Uri baseSelectedUri = builder.build();
            if (DBG) Log.v(TAG, "Selected Uri: " + mSelectedUri);
            if (DBG) Log.v(TAG, "Selected base Uri: " + baseSelectedUri);
            if (DBG) Log.v(TAG, "Base Uri: " + mBaseUri);
            if (baseSelectedUri.equals(mBaseUri)) {
                // If the base Uri of the selected Uri is the same as our
                // content's base Uri, then use the selection!
                mSelectedId = ContentUris.parseId(mSelectedUri);
            }
        }
        
        setSortMode(sortMode);
    }

    @Override public void onRestart() {
        super.onRestart();
        doQuery(false, null);
    }
    
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (setSortMode(item.getItemId())) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(Menu.NONE, TRACK_MENU, Menu.NONE, R.string.sort_by_track);
        menu.add(Menu.NONE, ALBUM_MENU, Menu.NONE, R.string.sort_by_album);
        menu.add(Menu.NONE, ARTIST_MENU, Menu.NONE, R.string.sort_by_artist);
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle icicle) {
        super.onSaveInstanceState(icicle);
        // Save list state in the bundle so we can restore it after the
        // QueryHandler has run
        icicle.putParcelable(LIST_STATE_KEY, getListView().onSaveInstanceState());
        icicle.putBoolean(FOCUS_KEY, getListView().hasFocus());
        icicle.putInt(SORT_MODE_KEY, mSortMode);
    }
    
    @Override public void onPause() {
        super.onPause();
        stopMediaPlayer();
    }
    
    @Override public void onStop() {
        super.onStop();
        
        // We don't want the list to display the empty state, since when we
        // resume it will still be there and show up while the new query is
        // happening. After the async query finishes in response to onResume()
        // setLoading(false) will be called.
        mAdapter.setLoading(true);
        mAdapter.changeCursor(null);
    }
    
    /**
     * Changes the current sort order, building the appropriate query string
     * for the selected order.
     */
    boolean setSortMode(int sortMode) {
        if (sortMode != mSortMode) {
            switch (sortMode) {
                case TRACK_MENU:
                    mSortMode = sortMode;
                    mSortOrder = MediaStore.Audio.Media.TITLE_KEY;
                    doQuery(false, null);
                    return true;
                case ALBUM_MENU:
                    mSortMode = sortMode;
                    mSortOrder = MediaStore.Audio.Media.ALBUM_KEY + " ASC, "
                            + MediaStore.Audio.Media.TRACK + " ASC, "
                            + MediaStore.Audio.Media.TITLE_KEY + " ASC";
                    doQuery(false, null);
                    return true;
                case ARTIST_MENU:
                    mSortMode = sortMode;
                    mSortOrder = MediaStore.Audio.Media.ARTIST_KEY + " ASC, "
                            + MediaStore.Audio.Media.ALBUM_KEY + " ASC, "
                            + MediaStore.Audio.Media.TRACK + " ASC, "
                            + MediaStore.Audio.Media.TITLE_KEY + " ASC";
                    doQuery(false, null);
                    return true;
            }
            
        }
        return false;
    }
    
    /**
     * The first time this is called, we hide the large progress indicator
     * and show the list view, doing fade animations between them.
     */
    void makeListShown() {
        if (!mListShown) {
            mListShown = true;
            mProgressContainer.startAnimation(AnimationUtils.loadAnimation(
                    this, android.R.anim.fade_out));
            mProgressContainer.setVisibility(View.GONE);
            mListContainer.startAnimation(AnimationUtils.loadAnimation(
                    this, android.R.anim.fade_in));
            mListContainer.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * Common method for performing a query of the music database, called for
     * both top-level queries and filtering.
     * 
     * @param sync If true, this query should be done synchronously and the
     * resulting cursor returned.  If false, it will be done asynchronously and
     * null returned.
     * @param filterstring If non-null, this is a filter to apply to the query.
     */
    Cursor doQuery(boolean sync, String filterstring) {
        // Cancel any pending queries
        mQueryHandler.cancelOperation(MY_QUERY_TOKEN);
        
        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Media.TITLE + " != ''");
        
        // We want to show all audio files, even recordings.  Enforcing the
        // following condition would hide recordings.
        //where.append(" AND " + MediaStore.Audio.Media.IS_MUSIC + "=1");

        Uri uri = mBaseUri;
        if (!TextUtils.isEmpty(filterstring)) {
            uri = uri.buildUpon().appendQueryParameter("filter", Uri.encode(filterstring)).build();
        }

        if (sync) {
            try {
                return getContentResolver().query(uri, CURSOR_COLS,
                        where.toString(), null, mSortOrder);
            } catch (UnsupportedOperationException ex) {
            }
        } else {
            mAdapter.setLoading(true);
            setProgressBarIndeterminateVisibility(true);
            mQueryHandler.startQuery(MY_QUERY_TOKEN, null, uri, CURSOR_COLS,
                    where.toString(), null, mSortOrder);
        }
        return null;
    }
    
    @Override protected void onListItemClick(ListView l, View v, int position,
            long id) {
        mCursor.moveToPosition(position);
        if (DBG) Log.v(TAG, "Click on " + position + " (id=" + id
                + ", cursid="
                + mCursor.getLong(mCursor.getColumnIndex(MediaStore.Audio.Media._ID))
                + ") in cursor " + mCursor
                + " adapter=" + l.getAdapter());
        setSelected(mCursor);
    }
    
    void setSelected(Cursor c) {
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        long newId = mCursor.getLong(mCursor.getColumnIndex(MediaStore.Audio.Media._ID));
        mSelectedUri = ContentUris.withAppendedId(uri, newId);
        
        mSelectedId = newId;
        if (newId != mPlayingId || mMediaPlayer == null) {
            stopMediaPlayer();
            mMediaPlayer = new MediaPlayer();
            try {
                mMediaPlayer.setDataSource(this, mSelectedUri);
                mMediaPlayer.setOnCompletionListener(this);
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_RING);
                mMediaPlayer.prepare();
                mMediaPlayer.start();
                mPlayingId = newId;
                getListView().invalidateViews();
            } catch (IOException e) {
                Log.w("MusicPicker", "Unable to play track", e);
            }
        } else if (mMediaPlayer != null) {
            stopMediaPlayer();
            getListView().invalidateViews();
        }
    }
    
    public void onCompletion(MediaPlayer mp) {
        if (mMediaPlayer == mp) {
            mp.stop();
            mp.release();
            mMediaPlayer = null;
            mPlayingId = -1;
            getListView().invalidateViews();
        }
    }
    
    void stopMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
            mPlayingId = -1;
        }
    }
    
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.okayButton:
                if (mSelectedId >= 0) {
                    setResult(RESULT_OK, new Intent().setData(mSelectedUri));
                    finish();
                }
                break;

            case R.id.cancelButton:
                finish();
                break;
        }
    }
}
