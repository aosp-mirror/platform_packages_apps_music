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

import android.app.ExpandableListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaFile;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.SimpleCursorTreeAdapter;
import android.widget.TextView;


public class ArtistAlbumBrowserActivity extends ExpandableListActivity
        implements View.OnCreateContextMenuListener, MusicUtils.Defs
{
    private String mCurrentArtistId;
    private String mCurrentArtistName;
    private String mCurrentAlbumId;
    private String mCurrentAlbumName;

    public ArtistAlbumBrowserActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (icicle != null) {
            mCurrentAlbumId = icicle.getString("selectedalbum");
            mCurrentAlbumName = icicle.getString("selectedalbumname");
            mCurrentArtistId = icicle.getString("selectedartist");
            mCurrentArtistName = icicle.getString("selectedartistname");
        }
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
        outcicle.putString("selectedalbumname", mCurrentAlbumName);
        outcicle.putString("selectedartist", mCurrentArtistId);
        outcicle.putString("selectedartistname", mCurrentArtistName);
        super.onSaveInstanceState(outcicle);
    }

    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(this);
        if (mArtistCursor != null) {
            mArtistCursor.close();
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
            getExpandableListView().invalidateViews();
        }
    };
    private BroadcastReceiver mScanListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            MusicUtils.setSpinnerState(ArtistAlbumBrowserActivity.this);
            mReScanHandler.sendEmptyMessage(0);
            if (intent.getAction().equals(Intent.ACTION_MEDIA_UNMOUNTED)) {
                MusicUtils.clearAlbumArtCache();
            }
        }
    };
    
    private Handler mReScanHandler = new Handler() {
        public void handleMessage(Message msg) {
            init();
            if (mArtistCursor == null) {
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

        mArtistCursor = getArtistCursor(null);

        if (mArtistCursor == null) {
            MusicUtils.displayDatabaseError(this);
            return;
        }

        setContentView(android.R.layout.expandable_list_content);

        // Map Cursor columns to views defined in media_list_item.xml
        ArtistAlbumListAdapter adapter = new ArtistAlbumListAdapter(
                this,
                mArtistCursor,
                R.layout.track_list_item_group,
                new String[] {},
                new int[] {},
                R.layout.track_list_item_child,
                new String[] {},
                new int[] {});

        setTitle(R.string.artists_title);
        setListAdapter(adapter);
        ExpandableListView lv = getExpandableListView();
        lv.setOnCreateContextMenuListener(this);
        lv.setTextFilterEnabled(true);
    }

    @Override
    public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {

        mCurrentAlbumId = Long.valueOf(id).toString();
        
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
        intent.putExtra("album", mCurrentAlbumId);
        mArtistCursor.moveToPosition(groupPosition);
        mCurrentArtistId = Long.valueOf(id).toString();
        intent.putExtra("artist", mCurrentArtistId);
        startActivity(intent);
        return true;
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

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfoIn) {
        menu.add(0, PLAY_SELECTION, 0, R.string.play_selection);
        SubMenu sub = menu.addSubMenu(0, ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
        MusicUtils.makePlaylistMenu(this, sub);
        menu.add(0, DELETE_ITEM, 0, R.string.delete_item);
        
        ExpandableListContextMenuInfo mi = (ExpandableListContextMenuInfo) menuInfoIn;
        
        int itemtype = ExpandableListView.getPackedPositionType(mi.packedPosition);
        int gpos = ExpandableListView.getPackedPositionGroup(mi.packedPosition);
        int cpos = ExpandableListView.getPackedPositionChild(mi.packedPosition);
        if (itemtype == ExpandableListView.PACKED_POSITION_TYPE_GROUP) {
            if (gpos == -1) {
                // this shouldn't happen
                Log.d("Artist/Album", "no group");
                return;
            }
            gpos = gpos - getExpandableListView().getHeaderViewsCount();
            mArtistCursor.moveToPosition(gpos);
            mCurrentArtistId = mArtistCursor.getString(mArtistCursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID));
            mCurrentArtistName = mArtistCursor.getString(mArtistCursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.ARTIST));
            mCurrentAlbumId = null;
            menu.setHeaderTitle(mCurrentArtistName);
            return;
        } else if (itemtype == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
            if (cpos == -1) {
                // this shouldn't happen
                Log.d("Artist/Album", "no child");
                return;
            }
            Cursor c = (Cursor) getExpandableListAdapter().getChild(gpos, cpos);
            c.moveToPosition(cpos);
            mCurrentArtistId = null;
            mCurrentAlbumId = Long.valueOf(mi.id).toString();
            mCurrentAlbumName = c.getString(c.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM));
            menu.setHeaderTitle(mCurrentAlbumName);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case PLAY_SELECTION: {
                // play everything by the selected artist
                int [] list =
                    mCurrentArtistId != null ?
                    MusicUtils.getSongListForArtist(this, Integer.parseInt(mCurrentArtistId))
                    : MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                        
                MusicUtils.playAll(this, list, 0);
                return true;
            }

            case QUEUE: {
                int [] list =
                    mCurrentArtistId != null ?
                    MusicUtils.getSongListForArtist(this, Integer.parseInt(mCurrentArtistId))
                    : MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
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
                int [] list =
                    mCurrentArtistId != null ?
                    MusicUtils.getSongListForArtist(this, Integer.parseInt(mCurrentArtistId))
                    : MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                int playlist = item.getIntent().getIntExtra("playlist", 0);
                MusicUtils.addToPlaylist(this, list, playlist);
                return true;
            }
            
            case DELETE_ITEM: {
                int [] list;
                String desc;
                if (mCurrentArtistId != null) {
                    list = MusicUtils.getSongListForArtist(this, Integer.parseInt(mCurrentArtistId));
                    String f = getString(R.string.delete_artist_desc);
                    desc = String.format(f, mCurrentArtistName);
                } else {
                    list = MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                    String f = getString(R.string.delete_album_desc); 
                    desc = String.format(f, mCurrentAlbumName);
                }
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
                        int [] list = null;
                        if (mCurrentArtistId != null) {
                            list = MusicUtils.getSongListForArtist(this, Integer.parseInt(mCurrentArtistId));
                        } else if (mCurrentAlbumId != null) {
                            list = MusicUtils.getSongListForAlbum(this, Integer.parseInt(mCurrentAlbumId));
                        }
                        MusicUtils.addToPlaylist(this, list, Integer.parseInt(uri.getLastPathSegment()));
                    }
                }
                break;
        }
    }

    private Cursor getArtistCursor(String filterstring) {

        StringBuilder where = new StringBuilder();
        where.append(MediaStore.Audio.Artists.ARTIST + " != ''");
        
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
                where.append(MediaStore.Audio.Media.ARTIST_KEY + " LIKE ?");
            }
        }

        String whereclause = where.toString();  
        String[] cols = new String[] {
                MediaStore.Audio.Artists._ID,
                MediaStore.Audio.Artists.ARTIST,
                MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
                MediaStore.Audio.Artists.NUMBER_OF_TRACKS
        };
        Cursor ret;
        ret = MusicUtils.query(this, MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
                cols, whereclause , keywords, MediaStore.Audio.Artists.ARTIST_KEY);
        return ret;
    }
    
    class ArtistAlbumListAdapter extends SimpleCursorTreeAdapter {
        
        private final Drawable mNowPlayingOverlay;
        private final BitmapDrawable mDefaultAlbumIcon;
        private final int mGroupArtistIdIdx;
        private final int mGroupArtistIdx;
        private final int mGroupAlbumIdx;
        private final int mGroupSongIdx;
        private final Resources mResources;
        private final String mAlbumSongSeparator;
        private final String mUnknownAlbum;
        private final String mUnknownArtist;
        private final StringBuilder mBuffer = new StringBuilder();
        private final Object[] mFormatArgs = new Object[1];
        
        class ViewHolder {
            TextView line1;
            TextView line2;
            ImageView play_indicator;
            ImageView icon;
        }

        ArtistAlbumListAdapter(Context context, Cursor cursor,
                int glayout, String[] gfrom, int[] gto, 
                int clayout, String[] cfrom, int[] cto) {
            super(context, cursor, glayout, gfrom, gto, clayout, cfrom, cto);

            Resources r = getResources();
            mNowPlayingOverlay = r.getDrawable(R.drawable.indicator_ic_mp_playing_list);
            mDefaultAlbumIcon = (BitmapDrawable) r.getDrawable(R.drawable.albumart_mp_unknown_list);
            // no filter or dither, it's a lot faster and we can't tell the difference
            mDefaultAlbumIcon.setFilterBitmap(false);
            mDefaultAlbumIcon.setDither(false);
            mGroupArtistIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID);
            mGroupArtistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.ARTIST);
            mGroupAlbumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.NUMBER_OF_ALBUMS);
            mGroupSongIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.NUMBER_OF_TRACKS);
            
            mResources = context.getResources();
            mAlbumSongSeparator = context.getString(R.string.albumsongseparator);
            mUnknownAlbum = context.getString(R.string.unknown_album_name);
            mUnknownArtist = context.getString(R.string.unknown_artist_name);
        }

        @Override
        public View newGroupView(Context context, Cursor cursor, boolean isExpanded, ViewGroup parent) {
            View v = super.newGroupView(context, cursor, isExpanded, parent);
            ImageView iv = (ImageView) v.findViewById(R.id.icon);
            ViewGroup.LayoutParams p = iv.getLayoutParams();
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            ViewHolder vh = new ViewHolder();
            vh.line1 = (TextView) v.findViewById(R.id.line1);
            vh.line2 = (TextView) v.findViewById(R.id.line2);
            vh.play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
            vh.icon = (ImageView) v.findViewById(R.id.icon);
            vh.icon.setPadding(1, 1, 1, 1);
            v.setTag(vh);
            return v;
        }

        @Override
        public View newChildView(Context context, Cursor cursor, boolean isLastChild,
                ViewGroup parent) {
            View v = super.newChildView(context, cursor, isLastChild, parent);
            ViewHolder vh = new ViewHolder();
            vh.line1 = (TextView) v.findViewById(R.id.line1);
            vh.line2 = (TextView) v.findViewById(R.id.line2);
            vh.play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
            vh.icon = (ImageView) v.findViewById(R.id.icon);
            vh.icon.setBackgroundDrawable(mDefaultAlbumIcon);
            vh.icon.setPadding(1, 1, 1, 1);
            v.setTag(vh);
            return v;
        }
        
        @Override
        public void bindGroupView(View view, Context context, Cursor cursor, boolean isexpanded) {

            ViewHolder vh = (ViewHolder) view.getTag();

            String artist = cursor.getString(mGroupArtistIdx);
            String displayartist = artist;
            boolean unknown = MediaFile.UNKNOWN_STRING.equals(artist);
            if (unknown) {
                displayartist = mUnknownArtist;
            }
            vh.line1.setText(displayartist);

            int numalbums = cursor.getInt(mGroupAlbumIdx);
            int numsongs = cursor.getInt(mGroupSongIdx);
            
            String songs_albums = MusicUtils.makeAlbumsSongsLabel(context,
                    numalbums, numsongs, unknown);
            
            vh.line2.setText(songs_albums);
            
            int currentartistid = MusicUtils.getCurrentArtistId();
            int artistid = cursor.getInt(mGroupArtistIdIdx);
            if (currentartistid == artistid && !isexpanded) {
                vh.play_indicator.setImageDrawable(mNowPlayingOverlay);
            } else {
                vh.play_indicator.setImageDrawable(null);
            }
        }

        @Override
        public void bindChildView(View view, Context context, Cursor cursor, boolean islast) {

            ViewHolder vh = (ViewHolder) view.getTag();

            String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM));
            String displayname = name;
            if (name.equals(MediaFile.UNKNOWN_STRING)) {
                displayname = mUnknownAlbum;
            }
            vh.line1.setText(displayname);

            int numsongs = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.NUMBER_OF_SONGS));
            int first = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.FIRST_YEAR));
            int last = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.LAST_YEAR));

            if (first == 0) {
                first = last;
            }

            final StringBuilder builder = mBuffer;
            builder.delete(0, builder.length());
            if (numsongs == 1) {
                builder.append(context.getString(R.string.onesong));
            } else {
                final Object[] args = mFormatArgs;
                args[0] = numsongs;
                builder.append(mResources.getQuantityString(R.plurals.Nsongs, numsongs, args));
            }
            if (first != 0 && last != 0) {
                builder.append(mAlbumSongSeparator);
                
                builder.append(first);
                if (first != last) {
                    builder.append('-');
                    builder.append(last);
                } else {
                }
            }
            vh.line2.setText(builder.toString());
            
            ImageView iv = vh.icon;
            // We don't actually need the path to the thumbnail file,
            // we just use it to see if there is album art or not
            String art = cursor.getString(cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART));
            if (art == null || art.length() == 0) {
                iv.setBackgroundDrawable(mDefaultAlbumIcon);
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
        protected Cursor getChildrenCursor(Cursor groupCursor) {
            
            int id = groupCursor.getInt(groupCursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID));
            
            String[] cols = new String[] {
                    MediaStore.Audio.Albums._ID,
                    MediaStore.Audio.Albums.ALBUM,
                    MediaStore.Audio.Albums.ARTIST,
                    MediaStore.Audio.Albums.NUMBER_OF_SONGS,
                    MediaStore.Audio.Albums.FIRST_YEAR,
                    MediaStore.Audio.Albums.LAST_YEAR,
                    MediaStore.Audio.Albums.ALBUM_ART
            };
            return MusicUtils.query(ArtistAlbumBrowserActivity.this,
                    MediaStore.Audio.Artists.Albums.getContentUri("external", id),
                    cols, null, null, MediaStore.Audio.Albums.DEFAULT_SORT_ORDER);
        }

        @Override
        public void changeCursor(Cursor cursor) {
            super.changeCursor(cursor);
            mArtistCursor = cursor;
        }
        @Override
        public Cursor runQueryOnBackgroundThread(CharSequence constraint) {
            return getArtistCursor(constraint.toString());
        }

    }
    
    private Cursor mArtistCursor;
}

