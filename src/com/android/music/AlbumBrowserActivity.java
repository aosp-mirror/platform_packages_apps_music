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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import com.android.music.utils.LogHelper;
import com.android.music.utils.MediaIDHelper;
import com.android.music.utils.Playback;

import java.util.ArrayList;
import java.util.List;

/*
This activity is the albums browsing tab
 */
public class AlbumBrowserActivity extends ListActivity {
    private static final String TAG = LogHelper.makeLogTag(AlbumBrowserActivity.class);
    private static final MediaBrowser.MediaItem DEFAULT_PARENT_ITEM =
            new MediaBrowser.MediaItem(new MediaDescription.Builder()
                                               .setMediaId(MediaIDHelper.MEDIA_ID_MUSICS_BY_ALBUM)
                                               .build(),
                    MediaBrowser.MediaItem.FLAG_BROWSABLE);

    private ListView mAlbumList;
    private AlbumBrowseAdapter mBrowseListAdapter;
    private MediaBrowser mMediaBrowser;
    private MediaBrowser.MediaItem mParentItem;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        Log.d(TAG, "onCreate()");
        super.onCreate(icicle);
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

        setContentView(R.layout.media_picker_activity);
        MusicUtils.updateButtonBar(this, R.id.albumtab);
        mAlbumList = getListView();
        mAlbumList.setOnCreateContextMenuListener(this);
        mAlbumList.setTextFilterEnabled(true);

        mBrowseListAdapter = (AlbumBrowseAdapter) getLastNonConfigurationInstance();
        if (mBrowseListAdapter == null) {
            // Log.i("@@@", "starting query");
            mBrowseListAdapter = new AlbumBrowseAdapter(this, R.layout.track_list_item);
            setTitle(R.string.working_albums);
        }
        setListAdapter(mBrowseListAdapter);
        Log.d(TAG, "Creating MediaBrowser");
        mMediaBrowser = new MediaBrowser(this, new ComponentName(this, MediaPlaybackService.class),
                mConnectionCallback, null);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        ListView lv = getListView();
        // Because we pass the adapter to the next activity, we need to make
        // sure it doesn't keep a reference to this activity. We can do this
        // by clearing its DatasetObservers, which setListAdapter(null) does.
        setListAdapter(null);
        mBrowseListAdapter = null;
        super.onDestroy();
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart()");
        super.onStart();
        mMediaBrowser.connect();
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop()");
        super.onStop();
        mMediaBrowser.disconnect();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outcicle) {
        outcicle.putParcelable(MusicUtils.TAG_PARENT_ITEM, mParentItem);
        super.onSaveInstanceState(outcicle);
    }

    private void setTitle() {
        CharSequence fancyName = "";
        setTitle(R.string.albums_title);
    }

    private MediaBrowser.SubscriptionCallback mSubscriptionCallback =
            new MediaBrowser.SubscriptionCallback() {

                @Override
                public void onChildrenLoaded(
                        String parentId, List<MediaBrowser.MediaItem> children) {
                    mBrowseListAdapter.clear();
                    mBrowseListAdapter.notifyDataSetInvalidated();
                    for (MediaBrowser.MediaItem item : children) {
                        mBrowseListAdapter.add(item);
                    }
                    mBrowseListAdapter.notifyDataSetChanged();
                }

                @Override
                public void onError(String id) {
                    Toast.makeText(getApplicationContext(), R.string.error_loading_media,
                                 Toast.LENGTH_LONG)
                            .show();
                }
            };

    private MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    Log.d(TAG, "onConnected: session token " + mMediaBrowser.getSessionToken());
                    mMediaBrowser.subscribe(mParentItem.getMediaId(), mSubscriptionCallback);
                    if (mMediaBrowser.getSessionToken() == null) {
                        throw new IllegalArgumentException("No Session token");
                    }
                    MediaController mediaController = new MediaController(
                            AlbumBrowserActivity.this, mMediaBrowser.getSessionToken());
                    mediaController.registerCallback(mMediaControllerCallback);
                    AlbumBrowserActivity.this.setMediaController(mediaController);
                    if (mediaController.getMetadata() != null) {
                        MusicUtils.updateNowPlaying(AlbumBrowserActivity.this);
                    }
                }

                @Override
                public void onConnectionFailed() {
                    Log.d(TAG, "onConnectionFailed");
                }

                @Override
                public void onConnectionSuspended() {
                    Log.d(TAG, "onConnectionSuspended");
                    AlbumBrowserActivity.this.setMediaController(null);
                }
            };

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            MusicUtils.updateNowPlaying(AlbumBrowserActivity.this);
            if (mBrowseListAdapter != null) {
                mBrowseListAdapter.notifyDataSetChanged();
            }
        }
    };

    // An adapter for showing the list of browsed MediaItem's
    private class AlbumBrowseAdapter extends ArrayAdapter<MediaBrowser.MediaItem> {
        private final Drawable mNowPlayingOverlay;
        private final BitmapDrawable mDefaultAlbumIcon;
        private int mLayoutId;

        private class ViewHolder {
            TextView line1;
            TextView line2;
            ImageView play_indicator;
            ImageView icon;
        }

        AlbumBrowseAdapter(Context context, int layout) {
            super(context, layout, new ArrayList<>());
            mNowPlayingOverlay = context.getResources().getDrawable(
                    R.drawable.indicator_ic_mp_playing_list, context.getTheme());
            Bitmap b = BitmapFactory.decodeResource(
                    context.getResources(), R.drawable.albumart_mp_unknown_list);
            mDefaultAlbumIcon = new BitmapDrawable(context.getResources(), b);
            // no filter or dither, it's a lot faster and we can't tell the difference
            mDefaultAlbumIcon.setFilterBitmap(false);
            mDefaultAlbumIcon.setDither(false);
            mLayoutId = layout;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Log.d(TAG, "getView()");
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(mLayoutId, parent, false);
                ViewHolder vh = new ViewHolder();
                vh.line1 = (TextView) convertView.findViewById(R.id.line1);
                vh.line2 = (TextView) convertView.findViewById(R.id.line2);
                vh.play_indicator = (ImageView) convertView.findViewById(R.id.play_indicator);
                vh.icon = (ImageView) convertView.findViewById(R.id.icon);
                vh.icon.setBackground(mDefaultAlbumIcon);
                vh.icon.setPadding(0, 0, 1, 0);
                vh.icon.setVisibility(View.VISIBLE);
                convertView.setTag(vh);
            }
            ViewHolder vh = (ViewHolder) convertView.getTag();
            MediaBrowser.MediaItem item = getItem(position);
            Log.d(TAG, "Album: " + item.getDescription().getTitle());
            vh.line1.setText(item.getDescription().getTitle());
            Log.d(TAG, "Artist: " + item.getDescription().getSubtitle());
            vh.line2.setText(item.getDescription().getSubtitle());
            Bitmap albumArt = item.getDescription().getIconBitmap();
            LogHelper.d(TAG, "looking for album art");
            if (albumArt != null) {
                vh.icon.setImageDrawable(MusicUtils.getDrawableBitmap(albumArt, mDefaultAlbumIcon));
            } else {
                vh.icon.setImageDrawable(mDefaultAlbumIcon);
            }
            MediaController mediaController = AlbumBrowserActivity.this.getMediaController();
            if (mediaController == null) {
                vh.play_indicator.setImageDrawable(null);
                return convertView;
            }
            MediaMetadata metadata = mediaController.getMetadata();
            if (metadata == null) {
                vh.play_indicator.setImageDrawable(null);
                return convertView;
            }
            if (metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                            .equals(item.getDescription().getTitle())) {
                vh.play_indicator.setImageDrawable(mNowPlayingOverlay);
            } else {
                vh.play_indicator.setImageDrawable(null);
            }
            return convertView;
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Log.d(TAG, "onListItemClick at position " + position + ", id " + id);
        MediaBrowser.MediaItem item = mBrowseListAdapter.getItem(position);
        if (item.isBrowsable()) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
            intent.putExtra(MusicUtils.TAG_PARENT_ITEM, item);
            intent.putExtra(MusicUtils.TAG_WITH_TABS, false);
            startActivity(intent);
        }
    }
}
