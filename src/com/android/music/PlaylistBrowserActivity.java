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

import android.app.Activity;
import android.app.ListActivity;
import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteException;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import com.android.music.utils.LogHelper;
import com.android.music.utils.MediaIDHelper;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

public class PlaylistBrowserActivity
        extends ListActivity implements View.OnCreateContextMenuListener {
    private static final String TAG = LogHelper.makeLogTag(PlaylistBrowserActivity.class);
    private PlaylistListAdapter mAdapter;
    boolean mAdapterSent;
    private static final MediaBrowser.MediaItem DEFAULT_PARENT_ITEM = new MediaBrowser.MediaItem(
            new MediaDescription.Builder()
                    .setMediaId(MediaIDHelper.MEDIA_ID_MUSICS_BY_PLAYLIST)
                    .setTitle("Playlists")
                    .build(),
            MediaBrowser.MediaItem.FLAG_BROWSABLE);

    private MediaBrowser mMediaBrowser;
    private MediaBrowser.MediaItem mParentItem;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        LogHelper.d(TAG, "onCreate()");
        // Process past states
        Intent intent = getIntent();
        if (icicle != null) {
            LogHelper.d(TAG, "Launch by saved instance state");
            mParentItem = icicle.getParcelable(MusicUtils.TAG_PARENT_ITEM);
            MusicUtils.updateNowPlaying(this);
        } else if (intent != null) {
            LogHelper.d(TAG, "Launch by intent");
            mParentItem = intent.getExtras().getParcelable(MusicUtils.TAG_PARENT_ITEM);
        }
        if (mParentItem == null) {
            LogHelper.d(TAG, "Launch by default parameters");
            mParentItem = DEFAULT_PARENT_ITEM;
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        setTitle(R.string.playlists_title);
        setContentView(R.layout.media_picker_activity);
        MusicUtils.updateButtonBar(this, R.id.playlisttab);
        ListView lv = getListView();
        lv.setOnCreateContextMenuListener(this);
        lv.setTextFilterEnabled(true);

        mAdapter = (PlaylistListAdapter) getLastNonConfigurationInstance();
        if (mAdapter == null) {
            // Log.i("@@@", "starting query");
            mAdapter = new PlaylistListAdapter(this, R.layout.track_list_item);
            setTitle(R.string.working_playlists);
        } else {
            mAdapter.setActivity(this);
        }
        setListAdapter(mAdapter);
        Log.d(TAG, "Creating MediaBrowser");
        mMediaBrowser = new MediaBrowser(this, new ComponentName(this, MediaPlaybackService.class),
                mConnectionCallback, null);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        PlaylistListAdapter a = mAdapter;
        mAdapterSent = true;
        return a;
    }

    @Override
    public void onDestroy() {
        setListAdapter(null);
        mAdapter = null;
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
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

    private MediaBrowser.SubscriptionCallback mSubscriptionCallback =
            new MediaBrowser.SubscriptionCallback() {

                @Override
                public void onChildrenLoaded(
                        String parentId, List<MediaBrowser.MediaItem> children) {
                    mAdapter.clear();
                    mAdapter.notifyDataSetInvalidated();
                    for (MediaBrowser.MediaItem item : children) {
                        mAdapter.add(item);
                    }
                    mAdapter.notifyDataSetChanged();
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
                            PlaylistBrowserActivity.this, mMediaBrowser.getSessionToken());
                    mediaController.registerCallback(mMediaControllerCallback);
                    PlaylistBrowserActivity.this.setMediaController(mediaController);
                    if (mediaController.getMetadata() != null) {
                        MusicUtils.updateNowPlaying(PlaylistBrowserActivity.this);
                    }
                }

                @Override
                public void onConnectionFailed() {
                    Log.d(TAG, "onConnectionFailed");
                }

                @Override
                public void onConnectionSuspended() {
                    Log.d(TAG, "onConnectionSuspended");
                    PlaylistBrowserActivity.this.setMediaController(null);
                }
            };

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            MusicUtils.updateNowPlaying(PlaylistBrowserActivity.this);
        }
    };

    private class PlaylistListAdapter extends ArrayAdapter<MediaBrowser.MediaItem> {
        private int mLayoutId;
        private Activity mActivity;

        PlaylistListAdapter(PlaylistBrowserActivity currentactivity, int layout) {
            super(currentactivity, layout);
            mActivity = currentactivity;
            mLayoutId = layout;
        }

        private class ViewHolder {
            TextView line1;
            TextView line2;
            ImageView icon;
            ImageView play_indicator;
        }

        public void setActivity(PlaylistBrowserActivity newactivity) {
            mActivity = newactivity;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(mLayoutId, parent, false);
                ViewHolder vhx = new ViewHolder();
                vhx.line1 = (TextView) convertView.findViewById(R.id.line1);
                vhx.line2 = (TextView) convertView.findViewById(R.id.line2);
                vhx.icon = (ImageView) convertView.findViewById(R.id.icon);
                vhx.play_indicator = (ImageView) convertView.findViewById(R.id.play_indicator);
                convertView.setTag(vhx);
            }
            ViewHolder vh = (ViewHolder) convertView.getTag();
            MediaBrowser.MediaItem item = getItem(position);
            vh.line1.setText(item.getDescription().getTitle());
            vh.line2.setVisibility(View.GONE);
            vh.play_indicator.setVisibility(View.GONE);
            vh.icon.setImageResource(R.drawable.ic_mp_playlist_list);
            ViewGroup.LayoutParams p = vh.icon.getLayoutParams();
            p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            p.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            return convertView;
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        MediaBrowser.MediaItem item = mAdapter.getItem(position);
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
        intent.putExtra(MusicUtils.TAG_PARENT_ITEM, item);
        startActivity(intent);
    }
}
