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

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaFile;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

public class AlbumBrowserActivity extends ListActivity
    implements View.OnCreateContextMenuListener, MusicUtils.Defs
{
    private String mCurrentAlbumId;
    private String mCurrentAlbumName;

    public AlbumBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        if (icicle != null) {
            mCurrentAlbumId = icicle.getString("selectedalbum");
            mArtistId = icicle.getString("artist");
        } else {
            mArtistId = getIntent().getStringExtra("artist");
        }
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        MusicUtils.bindToService(this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
        f.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addDataScheme("file");
        registerReceiver(mScanListener, f);

        init();
    }

    @Override
    public void onSaveInstanceState(Bundle outcicle) {
        // need to store the selected item so we don't lose it in case
        // of an orientation switch. Otherwise we could lose it while
        // in the middle of specifying a playlist to add the item to.
        outcicle.putString("selectedalbum", mCurrentAlbumId);
        outcicle.putString("artist", mArtistId);
        super.onSaveInstanceState(outcicle);
    }

    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(this);
        if (mAlbumCursor != null) {
            mAlbumCursor.close();
        }
        unregisterReceiver(mScanListener);
        super.onDestroy();
    }
    
    @Override
    public void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.META_CHANGED);
        f.addAction(MediaPlaybackService.QUEUE_CHANGED);
        registerReceiver(mTrackListListener, f);
        mTrackListListener.onReceive(null, null);

        MusicUtils.setSpinnerState(this);
    }

    private BroadcastReceiver mTrackListListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            getListView().invalidateViews();
        }
    };
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.setSpinnerState(AlbumBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
            if (intent.getAction().equals(Intent.ACTION_MEDIA_UNMOUNTED)) {
                MusicUtils.clearAlbumArtCache();
            }
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        public void handleMessage(Message msg) {
            init();
            if (mAlbumCursor == null) {
                sendEmptyMessageDelayed(0, 1000);
            }
        }
    };

    @Override
    public void onPause() {
        unregisterReceiver(mTrackListListener);
        mReScanHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    public void init() {

        mAlbumCursor = getAlbumCursor(null);

        /* The UI spec does not call for icons in the title bar
        if (mArtistId != null) {
            requestWindowFeature(Window.FEATURE_LEFT_ICON);
        }
        */
        setContentView(R.layout.media_picker_activity);
        /* The UI spec does not call for icons in the title bar
        if (mArtistId != null) {
            // this call needs to happen after setContentView
            setFeatureDrawableResource(Window.FEATURE_LEFT_ICON, R.drawable.person_title_icon);
        }
        */

        if (mAlbumCursor == null) {
            MusicUtils.displayDatabaseError(this);
            return;
        }

        CharSequence fancyName = "";
        if (mAlbumCursor.getCount() > 0) {
            mAlbumCursor.moveToFirst();
            fancyName = mAlbumCursor.getString(3);
            if (MediaFile.UNKNOWN_STRING.equals(fancyName))
                fancyName = getText(R.string.unknown_artist_name);

            if (mArtistId != null && fancyName != null)
                setTitle(fancyName);
            else
                setTitle(R.string.albums_title);
        } else {
            setTitle(R.string.no_albums_title);
        }

        // Map Cursor columns to views defined in media_list_item.xml
        AlbumListAdapter adapter = new AlbumListAdapter(
                this,
                R.layout.track_list_item,
                mAlbumCursor,
                new String[] {},
                new int[] {});

        setListAdapter(adapter);
        ListView lv = getListView();
        lv.setOnCreateContextMenuListener(this);
        lv.setTextFilterEnabled(true);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfoIn) {
        menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);
        SubMenu sub = menu.addSubMenu(0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
        MusicUtils.makePlaylistMenu(this, sub);
        menu.add(0, DELETE_ITEM, 0, R.string.delete_item);

        AdapterContextMenuInfo mi = (AdapterContextMenuInfo) menuInfoIn;
        mAlbumCursor.moveToPosition(mi.position);
        mCurrentAlbumId = mAlbumCursor.getString(mAlbumCursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID));
        mCurrentAlbumName = mAlbumCursor.getString(mAlbumCursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM));
        menu.setHeaderTitle(mCurrentAlbumName);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PLAY_SELECTION: {
                // play the selected album
                int [] list = MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                MusicUtils.playAll(this, list, 0);
                return true;
            }

            case QUEUE: {
                int [] list = MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
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
                int [] list = MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                int playlist = item.getIntent().getIntExtra("playlist", 0);
                MusicUtils.addToPlaylist(this, list, playlist);
                return true;
            }
            case DELETE_ITEM: {
                int [] list = MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                String f = getString(R.string.delete_album_desc); 
                String desc = String.format(f, mCurrentAlbumName);
                Bundle b = new Bundle();
                b.putString("description", desc);
                b.putIntArray("items", list);
                Intent intent = new Intent();
                intent.setClass(this, DeleteItems.class);
                intent.putExtras(b);
                startActivityForResult(intent, -1);
                return true;
            }

        }
        return super.onContextItemSelected(item);
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
                        int [] list = MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                        MusicUtils.addToPlaylist(this, list, Integer.parseInt(uri.getLastPathSegment()));
                    }
                }
                break;
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id)
    {
        if (mHasHeader) {
            position --;
        }
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
        intent.putExtra("album", Long.valueOf(id).toString());
        intent.putExtra("artist", mArtistId);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, GOTO_START, 0, R.string.goto_start).setIcon(R.drawable.ic_menu_music_library);
        menu.add(0, GOTO_PLAYBACK, 0, R.string.goto_playback).setIcon(R.drawable.ic_menu_playback);
        menu.add(0, SHUFFLE_ALL, 0, R.string.shuffle_all).setIcon(R.drawable.ic_menu_shuffle);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(GOTO_PLAYBACK).setVisible(MusicUtils.isMusicLoaded());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        Cursor cursor;
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

            case SHUFFLE_ALL:
                cursor = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String [] { MediaStore.Audio.Media._ID},
                        MediaStore.Audio.Media.IS_MUSIC + "=1", null,
                        MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
                if (cursor != null) {
                    MusicUtils.shuffleAll(this, cursor);
                    cursor.close();
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private Cursor getAlbumCursor(String filterstring) {
        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Albums.ALBUM + " != ''");
        
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
                where.append(MediaStore.Audio.Media.ALBUM_KEY + " LIKE ?");
            }
        }

        String whereclause = where.toString();  
            
        String[] cols = new String[] {
                MediaStore.Audio.Albums._ID,
                MediaStore.Audio.Albums.ALBUM,
                MediaStore.Audio.Albums.ALBUM_KEY,
                MediaStore.Audio.Albums.ARTIST,
                MediaStore.Audio.Albums.NUMBER_OF_SONGS,
                MediaStore.Audio.Albums.ALBUM_ART
        };
        Cursor ret;
        if (mArtistId != null) {
            ret = MusicUtils.query(this,
                    MediaStore.Audio.Artists.Albums.getContentUri("external", Long.valueOf(mArtistId)),
                    cols, whereclause, keywords, MediaStore.Audio.Albums.DEFAULT_SORT_ORDER);
        } else {
            ret = MusicUtils.query(this, MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                cols, whereclause, keywords, MediaStore.Audio.Albums.DEFAULT_SORT_ORDER);
        }
        return ret;
    }
    
    class AlbumListAdapter extends SimpleCursorAdapter {
        
        private final Drawable mNowPlayingOverlay;
        private final BitmapDrawable mDefaultAlbumIcon;
        private final int mAlbumIdx;
        private final int mArtistIdx;
        private final int mNumSongsIdx;
        private final int mAlbumArtIndex;
        private final Resources mResources;
        private final StringBuilder mStringBuilder = new StringBuilder();
        private final String mUnknownAlbum;
        private final String mUnknownArtist;
        private final String mAlbumSongSeparator;
        private final Object[] mFormatArgs = new Object[1];

        class ViewHolder {
            TextView line1;
            TextView line2;
            TextView duration;
            ImageView play_indicator;
            ImageView icon;
        }

        AlbumListAdapter(Context context, int layout, Cursor cursor, String[] from, int[] to) {
            super(context, layout, cursor, from, to);

            mUnknownAlbum = context.getString(R.string.unknown_album_name);
            mUnknownArtist = context.getString(R.string.unknown_artist_name);
            mAlbumSongSeparator = context.getString(R.string.albumsongseparator);

            Resources r = getResources();
            mNowPlayingOverlay = r.getDrawable(R.drawable.indicator_ic_mp_playing_list);

            Bitmap b = BitmapFactory.decodeResource(r, R.drawable.albumart_mp_unknown_list);
            mDefaultAlbumIcon = new BitmapDrawable(b);
            // no filter or dither, it's a lot faster and we can't tell the difference
            mDefaultAlbumIcon.setFilterBitmap(false);
            mDefaultAlbumIcon.setDither(false);
            mAlbumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM);
            mArtistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ARTIST);
            mNumSongsIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.NUMBER_OF_SONGS);
            mAlbumArtIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM_ART);
            
            mResources = context.getResources();
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
           View v = super.newView(context, cursor, parent);
           ViewHolder vh = new ViewHolder();
           vh.line1 = (TextView) v.findViewById(R.id.line1);
           vh.line2 = (TextView) v.findViewById(R.id.line2);
           vh.duration = (TextView) v.findViewById(R.id.duration);
           vh.play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
           vh.icon = (ImageView) v.findViewById(R.id.icon);
           vh.icon.setBackgroundDrawable(mDefaultAlbumIcon);
           vh.icon.setPadding(1, 1, 1, 1);
           v.setTag(vh);
           return v;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            
            ViewHolder vh = (ViewHolder) view.getTag();

            String name = cursor.getString(mAlbumIdx);
            String displayname = name;
            if (MediaFile.UNKNOWN_STRING.equals(name)) {
                displayname = mUnknownAlbum;
            }
            vh.line1.setText(displayname);
            
            name = cursor.getString(mArtistIdx);
            displayname = name;
            if (MediaFile.UNKNOWN_STRING.equals(name)) {
                displayname = mUnknownArtist;
            }
            StringBuilder builder = mStringBuilder;
            builder.delete(0, builder.length());
            builder.append(displayname);
            builder.append(mAlbumSongSeparator);
            
            int numsongs = cursor.getInt(mNumSongsIdx);
            if (numsongs == 1) {
                builder.append(context.getString(R.string.onesong));
            } else {
                final Object[] args = mFormatArgs;
                args[0] = numsongs;
                builder.append(mResources.getQuantityString(R.plurals.Nsongs, numsongs, args));
            }
            vh.line2.setText(builder.toString());

            ImageView iv = vh.icon;
            // We don't actually need the path to the thumbnail file,
            // we just use it to see if there is album art or not
            String art = cursor.getString(mAlbumArtIndex);
            if (art == null || art.length() == 0) {
                iv.setImageDrawable(null);
            } else {
                int artIndex = cursor.getInt(0);
                Drawable d = MusicUtils.getCachedArtwork(context, artIndex, mDefaultAlbumIcon);
                iv.setImageDrawable(d);
            }
            
            int currentalbumid = MusicUtils.getCurrentAlbumId();
            int aid = cursor.getInt(0);
            iv = vh.play_indicator;
            if (currentalbumid == aid) {
                iv.setImageDrawable(mNowPlayingOverlay);
            } else {
                iv.setImageDrawable(null);
            }
        }
        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            mAlbumCursor = cursor;
        }
        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            return getAlbumCursor(constraint.toString());
        }
    }

    private Cursor mAlbumCursor;
    private String mArtistId;
    private boolean mHasHeader = false;
}

