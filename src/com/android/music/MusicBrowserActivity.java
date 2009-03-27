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
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaFile;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.TextView;

public class MusicBrowserActivity extends Activity
    implements MusicUtils.Defs, View.OnClickListener {
    private View mNowPlayingView;
    private TextView mTitle;
    private TextView mArtist;
    private boolean mAutoShuffle = false;
    private static final int SEARCH_MUSIC = CHILD_MENU_BASE;

    public MusicBrowserActivity() {
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        String shuf = getIntent().getStringExtra("autoshuffle");
        if ("true".equals(shuf)) {
            mAutoShuffle = true;
        }
        MusicUtils.bindToService(this, new ServiceConnection() {
            public void onServiceConnected(ComponentName classname, IBinder obj) {
                updateMenu();
            }

            public void onServiceDisconnected(ComponentName classname) {
                updateMenu();
            }
        
        });
        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);
        init();
    }

    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(this);
        super.onDestroy();
    }

    public void init() {
        setContentView(R.layout.music_library);
        mNowPlayingView = findViewById(R.id.nowplaying);
        mTitle = (TextView) mNowPlayingView.findViewById(R.id.title);
        mArtist = (TextView) mNowPlayingView.findViewById(R.id.artist);
        
        View b = (View) findViewById(R.id.browse_button); 
        b.setOnClickListener(this);
        
        b = (View) findViewById(R.id.albums_button);
        b.setOnClickListener(this);

        b = (View) findViewById(R.id.tracks_button);
        b.setOnClickListener(this);

        b = (View) findViewById(R.id.playlists_button);
        b.setOnClickListener(this);
    }
    
    private void updateMenu() {
        try {
            if (MusicUtils.sService != null && MusicUtils.sService.getAudioId() != -1) {
                makeNowPlayingView();
                mNowPlayingView.setVisibility(View.VISIBLE);
                return;
            }
        } catch (RemoteException ex) {
        }
        mNowPlayingView.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(MediaPlaybackService.META_CHANGED);
        registerReceiver(mStatusListener, new IntentFilter(f));
        updateMenu();
        if (mAutoShuffle) {
            mAutoShuffle = false;
            doAutoShuffle();
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mStatusListener);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
         menu.add(0, PARTY_SHUFFLE, 0, R.string.party_shuffle); // icon will be set in onPrepareOptionsMenu()
         menu.add(0, SEARCH_MUSIC, 0, R.string.search_title).setIcon(android.R.drawable.ic_menu_search);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(PARTY_SHUFFLE);
        if (item != null) {
            int shuffle = MusicUtils.getCurrentShuffleMode();
            if (shuffle == MediaPlaybackService.SHUFFLE_AUTO) {
                item.setIcon(R.drawable.ic_menu_party_shuffle);
                item.setTitle(R.string.party_shuffle_off);
            } else {
                item.setIcon(R.drawable.ic_menu_party_shuffle);
                item.setTitle(R.string.party_shuffle);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        try {
            switch (item.getItemId()) {
                case PARTY_SHUFFLE:
                    int shuffle = MusicUtils.sService.getShuffleMode();
                    if (shuffle == MediaPlaybackService.SHUFFLE_AUTO) {
                        MusicUtils.sService.setShuffleMode(MediaPlaybackService.SHUFFLE_NONE);
                    } else {
                        MusicUtils.sService.setShuffleMode(MediaPlaybackService.SHUFFLE_AUTO);
                    }
                    break;
                    
                case SEARCH_MUSIC: {
                    startSearch("", false, null, false);
                    return true;
                }
            }
        } catch (RemoteException ex) {
        }
        return super.onOptionsItemSelected(item);
    }
    
    public void onClick(View v) {
        Intent intent;
        switch (v.getId()) {
            case R.id.browse_button:
                intent = new Intent(Intent.ACTION_PICK);
                intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/artistalbum");
                startActivity(intent);
                break;
            case R.id.albums_button:
                intent = new Intent(Intent.ACTION_PICK);
                intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/album");
                startActivity(intent);
                break;
            case R.id.tracks_button:
                intent = new Intent(Intent.ACTION_PICK);
                intent.setDataAndType(Uri.EMPTY, "vnd.android.cursor.dir/track");
                startActivity(intent);
                break;
            case R.id.playlists_button:
                intent = new Intent(Intent.ACTION_PICK);
                intent.setDataAndType(Uri.EMPTY, MediaStore.Audio.Playlists.CONTENT_TYPE);
                startActivity(intent);
                break;
            case R.id.nowplaying:
                intent = new Intent("com.android.music.PLAYBACK_VIEWER");
                startActivity(intent);
                break;
        }
    }

    private void doAutoShuffle() {
        bindService((new Intent()).setClass(this, MediaPlaybackService.class), autoshuffle, 0);
    }

    private ServiceConnection autoshuffle = new ServiceConnection() {
        public void onServiceConnected(ComponentName classname, IBinder obj) {
            // we need to be able to bind again, so unbind
            try {
                unbindService(this);
            } catch (IllegalArgumentException e) {
            }
            IMediaPlaybackService serv = IMediaPlaybackService.Stub.asInterface(obj);
            if (serv != null) {
                try {
                    serv.setShuffleMode(MediaPlaybackService.SHUFFLE_AUTO);
                    updateMenu();
                } catch (RemoteException ex) {
                }
            }
        }

        public void onServiceDisconnected(ComponentName classname) {
        }
    };

    private void makeNowPlayingView() {
        try {
            mTitle.setText(MusicUtils.sService.getTrackName());
            String artistName = MusicUtils.sService.getArtistName();
            if (MediaFile.UNKNOWN_STRING.equals(artistName)) {
                artistName = getString(R.string.unknown_artist_name);
            }
            mArtist.setText(artistName);
            mNowPlayingView.setOnFocusChangeListener(mFocuser);
            mNowPlayingView.setOnClickListener(this);
        } catch (RemoteException ex) {

        }
    }

    View.OnFocusChangeListener mFocuser = new View.OnFocusChangeListener() {
        Drawable mBack;

        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus) {
                if (mBack == null) {
                    mBack = mNowPlayingView.getBackground();
                }
                Drawable dr = getResources().getDrawable(android.R.drawable.menuitem_background);
                dr.setState(new int[] { android.R.attr.state_focused});
                mNowPlayingView.setBackgroundDrawable(dr);
                mNowPlayingView.setSelected(true);
            } else {
                mNowPlayingView.setBackgroundDrawable(mBack);
                mNowPlayingView.setSelected(false);
            }
        }
    };

    private BroadcastReceiver mStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // this receiver is only used for META_CHANGED events
            updateMenu();
        }
    };
}

