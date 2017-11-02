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

import android.app.ExpandableListActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.android.music.utils.LogHelper;
import com.android.music.utils.MediaIDHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ArtistAlbumBrowserActivity extends ExpandableListActivity {
    private static final String TAG = LogHelper.makeLogTag(ArtistAlbumBrowserActivity.class);
    private static final String KEY_NUM_ALBUMS = "__NUM_ALBUMS__";
    private static final MediaBrowser.MediaItem DEFAULT_PARENT_ITEM =
            new MediaBrowser.MediaItem(new MediaDescription.Builder()
                                               .setMediaId(MediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST)
                                               .build(),
                    MediaBrowser.MediaItem.FLAG_BROWSABLE);

    private ArtistAlbumListAdapter mAdapter;
    private MediaBrowser mMediaBrowser;
    private MediaBrowser.MediaItem mParentItem;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        LogHelper.d(TAG, "onCreate()");
        // Handle past states
        if (icicle != null) {
            mParentItem = icicle.getParcelable(MusicUtils.TAG_PARENT_ITEM);
        } else if (getIntent() != null) {
            mParentItem = getIntent().getExtras().getParcelable(MusicUtils.TAG_PARENT_ITEM);
        }
        if (mParentItem == null) {
            mParentItem = DEFAULT_PARENT_ITEM;
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Init layout
        setContentView(R.layout.media_picker_activity_expanding);
        MusicUtils.updateButtonBar(this, R.id.artisttab);

        // Init expandable list
        ExpandableListView lv = getExpandableListView();
        lv.setTextFilterEnabled(true);
        mAdapter = (ArtistAlbumListAdapter) getLastNonConfigurationInstance();
        if (mAdapter == null) {
            // Log.i("@@@", "starting query");
            mAdapter = new ArtistAlbumListAdapter(this, new ArrayList<>(), new ArrayList<>());
            setListAdapter(mAdapter);
            setTitle(R.string.working_artists);
        } else {
            mAdapter.setActivity(this);
        }
        setListAdapter(mAdapter);
        LogHelper.d(TAG, "Creating MediaBrowser");
        mMediaBrowser = new MediaBrowser(this, new ComponentName(this, MediaPlaybackService.class),
                mConnectionCallback, null);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mAdapter;
    }

    @Override
    public void onSaveInstanceState(Bundle outcicle) {
        outcicle.putParcelable(MusicUtils.TAG_PARENT_ITEM, mParentItem);
        super.onSaveInstanceState(outcicle);
    }

    @Override
    public void onDestroy() {
        setListAdapter(null);
        mAdapter = null;
        setListAdapter(null);
        super.onDestroy();
    }

    @Override
    public void onStart() {
        LogHelper.d(TAG, "onStart()");
        super.onStart();
        mMediaBrowser.connect();
    }

    @Override
    public void onStop() {
        LogHelper.d(TAG, "onStop()");
        super.onStop();
        mMediaBrowser.disconnect();
    }

    private MediaBrowser
            .SubscriptionCallback mSubscriptionCallback = new MediaBrowser.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
            if (parentId.equals(MediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST)) {
                mAdapter.getArtistMap().clear();
                mAdapter.getGroupData().clear();
                mAdapter.notifyDataSetInvalidated();
                for (MediaBrowser.MediaItem item : children) {
                    ConcurrentHashMap<String, MediaBrowser.MediaItem> entry =
                            new ConcurrentHashMap<>();
                    entry.put(MediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST, item);
                    synchronized (this) {
                        mAdapter.getArtistMap().put(item.getDescription().getTitle().toString(),
                                mAdapter.getGroupData().size());
                        mAdapter.getGroupData().add(entry);
                        mAdapter.getChildData().add(new ArrayList<>());
                    }
                    mMediaBrowser.subscribe(item.getMediaId(), this);
                }
                mAdapter.notifyDataSetChanged();
            } else if (parentId.startsWith(MediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST)) {
                String artist = MediaIDHelper.getHierarchy(parentId)[1];
                if (!mAdapter.getArtistMap().containsKey(artist)) {
                    return;
                }
                int artistIndex = mAdapter.getArtistMap().get(artist);
                mAdapter.getChildData().get(artistIndex).clear();
                mAdapter.notifyDataSetInvalidated();
                Bundle extras = new Bundle();
                extras.putLong(KEY_NUM_ALBUMS, children.size());
                MediaBrowser.MediaItem newArtistItem =
                        new MediaBrowser.MediaItem(new MediaDescription.Builder()
                                                           .setMediaId("Count")
                                                           .setExtras(extras)
                                                           .build(),
                                0);
                mAdapter.getGroupData().get(artistIndex).put(KEY_NUM_ALBUMS, newArtistItem);
                for (MediaBrowser.MediaItem item : children) {
                    ConcurrentHashMap<String, MediaBrowser.MediaItem> entry =
                            new ConcurrentHashMap<>();
                    entry.put(MediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM, item);
                    mAdapter.getChildData().get(artistIndex).add(entry);
                }
                mAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onError(String id) {
            Toast.makeText(getApplicationContext(), R.string.error_loading_media, Toast.LENGTH_LONG)
                    .show();
        }
    };

    private MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    LogHelper.d(
                            TAG, "onConnected: session token ", mMediaBrowser.getSessionToken());
                    mMediaBrowser.subscribe(mParentItem.getMediaId(), mSubscriptionCallback);
                    if (mMediaBrowser.getSessionToken() == null) {
                        throw new IllegalArgumentException("No Session token");
                    }
                    MediaController mediaController = new MediaController(
                            ArtistAlbumBrowserActivity.this, mMediaBrowser.getSessionToken());
                    mediaController.registerCallback(mMediaControllerCallback);
                    ArtistAlbumBrowserActivity.this.setMediaController(mediaController);
                    if (mediaController.getMetadata() != null) {
                        MusicUtils.updateNowPlaying(ArtistAlbumBrowserActivity.this);
                    }
                }

                @Override
                public void onConnectionFailed() {
                    LogHelper.d(TAG, "onConnectionFailed");
                }

                @Override
                public void onConnectionSuspended() {
                    LogHelper.d(TAG, "onConnectionSuspended");
                    ArtistAlbumBrowserActivity.this.setMediaController(null);
                }
            };

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            MusicUtils.updateNowPlaying(ArtistAlbumBrowserActivity.this);
        }
    };

    private class ArtistAlbumListAdapter extends SimpleExpandableListAdapter {
        private final Drawable mNowPlayingOverlay;
        private final BitmapDrawable mDefaultAlbumIcon;
        private ArtistAlbumBrowserActivity mActivity;
        private ArrayList<ConcurrentHashMap<String, MediaBrowser.MediaItem>> mGroupData;
        private ArrayList < ArrayList
                < ConcurrentHashMap<String, MediaBrowser.MediaItem>>> mChildData;
        private ConcurrentHashMap<String, Integer> mArtistMap;

        private class ViewHolder {
            TextView line1;
            TextView line2;
            ImageView play_indicator;
            ImageView icon;
        }

        ArtistAlbumListAdapter(ArtistAlbumBrowserActivity currentActivity,
                ArrayList<ConcurrentHashMap<String, MediaBrowser.MediaItem>> groupData,
                ArrayList < ArrayList
                        < ConcurrentHashMap<String, MediaBrowser.MediaItem>>> childData) {
            super(currentActivity, groupData, R.layout.track_list_item_group, new String[] {},
                    new int[] {}, childData, R.layout.track_list_item_child, new String[] {},
                    new int[] {});
            mGroupData = groupData;
            mChildData = childData;
            mActivity = currentActivity;
            mNowPlayingOverlay = currentActivity.getResources().getDrawable(
                    R.drawable.indicator_ic_mp_playing_list, currentActivity.getTheme());
            mDefaultAlbumIcon = (BitmapDrawable) currentActivity.getResources().getDrawable(
                    R.drawable.albumart_mp_unknown_list, currentActivity.getTheme());
            // no filter or dither, it's a lot faster and we can't tell the difference
            mDefaultAlbumIcon.setFilterBitmap(false);
            mDefaultAlbumIcon.setDither(false);
            mArtistMap = new ConcurrentHashMap<>();
        }

        public ArrayList<ConcurrentHashMap<String, MediaBrowser.MediaItem>> getGroupData() {
            return mGroupData;
        }

        public ArrayList < ArrayList
                < ConcurrentHashMap<String, MediaBrowser.MediaItem>>> getChildData() {
            return mChildData;
        }

        public Map<String, Integer> getArtistMap() {
            return mArtistMap;
        }

        public void setActivity(ArtistAlbumBrowserActivity newactivity) {
            mActivity = newactivity;
        }

        @Override
        public View newGroupView(boolean isExpanded, ViewGroup parent) {
            View v = super.newGroupView(isExpanded, parent);
            ImageView iv = (ImageView) v.findViewById(R.id.icon);
            ViewGroup.LayoutParams p = iv.getLayoutParams();
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            ViewHolder vh = new ViewHolder();
            vh.line1 = (TextView) v.findViewById(R.id.line1);
            vh.line2 = (TextView) v.findViewById(R.id.line2);
            vh.play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
            vh.icon = (ImageView) v.findViewById(R.id.icon);
            vh.icon.setPadding(0, 0, 1, 0);
            v.setTag(vh);
            return v;
        }

        @Override
        public View newChildView(boolean isLastChild, ViewGroup parent) {
            View v = super.newChildView(isLastChild, parent);
            ViewHolder vh = new ViewHolder();
            vh.line1 = (TextView) v.findViewById(R.id.line1);
            vh.line2 = (TextView) v.findViewById(R.id.line2);
            vh.play_indicator = (ImageView) v.findViewById(R.id.play_indicator);
            vh.icon = (ImageView) v.findViewById(R.id.icon);
            vh.icon.setBackground(mDefaultAlbumIcon);
            vh.icon.setPadding(0, 0, 1, 0);
            v.setTag(vh);
            return v;
        }

        @Override
        public View getGroupView(
                int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = newGroupView(isExpanded, parent);
            }
            Map<String, MediaBrowser.MediaItem> artistEntry =
                    (Map<String, MediaBrowser.MediaItem>) getGroup(groupPosition);
            MediaBrowser.MediaItem artistItem =
                    artistEntry.get(MediaIDHelper.MEDIA_ID_MUSICS_BY_ARTIST);
            MediaBrowser.MediaItem countItem = artistEntry.get(KEY_NUM_ALBUMS);
            ViewHolder vh = (ViewHolder) convertView.getTag();
            vh.line1.setText(artistItem.getDescription().getTitle());
            int numalbums = -1;
            if (countItem != null) {
                Bundle extras = countItem.getDescription().getExtras();
                if (extras != null) {
                    numalbums = (int) extras.getLong(KEY_NUM_ALBUMS);
                }
            }
            String songs_albums = MusicUtils.makeAlbumsLabel(mActivity, numalbums, -1, false);
            vh.line2.setText(songs_albums);
            MediaController mediaController = mActivity.getMediaController();
            if (mediaController == null) {
                vh.play_indicator.setImageDrawable(null);
                return convertView;
            }
            MediaMetadata metadata = mediaController.getMetadata();
            if (metadata == null) {
                vh.play_indicator.setImageDrawable(null);
                return convertView;
            }
            if (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            .equals(artistItem.getDescription().getTitle())
                    && !isExpanded) {
                vh.play_indicator.setImageDrawable(mNowPlayingOverlay);
            } else {
                vh.play_indicator.setImageDrawable(null);
            }
            return convertView;
        }

        @Override
        public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
                View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = newChildView(isLastChild, parent);
            }
            Map<String, MediaBrowser.MediaItem> albumEntry =
                    (Map<String, MediaBrowser.MediaItem>) getChild(groupPosition, childPosition);
            MediaBrowser.MediaItem albumItem =
                    albumEntry.get(MediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM);
            ViewHolder vh = (ViewHolder) convertView.getTag();
            vh.line1.setText(albumItem.getDescription().getTitle());
            vh.line2.setText(albumItem.getDescription().getDescription());
            Bitmap albumArt = albumItem.getDescription().getIconBitmap();
            if (albumArt == null) {
                vh.icon.setBackground(mDefaultAlbumIcon);
            } else {
                vh.icon.setImageDrawable(MusicUtils.getDrawableBitmap(albumArt, mDefaultAlbumIcon));
            }
            MediaController mediaController = mActivity.getMediaController();
            if (mediaController == null) {
                vh.play_indicator.setImageDrawable(null);
                return convertView;
            }
            MediaMetadata metadata = mediaController.getMetadata();
            if (metadata == null) {
                vh.play_indicator.setImageDrawable(null);
                return convertView;
            }
            if (albumItem.getDescription().getTitle().equals(
                        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))) {
                vh.play_indicator.setImageDrawable(mNowPlayingOverlay);
            } else {
                vh.play_indicator.setImageDrawable(null);
            }
            return convertView;
        }
    }

    @Override
    public boolean onChildClick(
            ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
        Map<String, MediaBrowser.MediaItem> albumEntry =
                (Map<String, MediaBrowser.MediaItem>) mAdapter.getChild(
                        groupPosition, childPosition);
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
        intent.putExtra(
                MusicUtils.TAG_PARENT_ITEM, albumEntry.get(MediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM));
        startActivity(intent);
        return true;
    }
}
