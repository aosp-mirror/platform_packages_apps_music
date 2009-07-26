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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

public class StreamStarter extends Activity
{
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.streamstarter);
        
        TextView tv = (TextView) findViewById(R.id.streamloading);
        
        Uri uri = getIntent().getData();
        String msg = getString(R.string.streamloadingtext, uri.getHost());
        tv.setText(msg);
    }
    
    @Override
    public void onResume() {
        super.onResume();

        MusicUtils.bindToService(this, new ServiceConnection() {
            public void onServiceConnected(ComponentName classname, IBinder obj) {
                try {
                    IntentFilter f = new IntentFilter();
                    f.addAction(MediaPlaybackService.ASYNC_OPEN_COMPLETE);
                    f.addAction(MediaPlaybackService.PLAYBACK_COMPLETE);
                    registerReceiver(mStatusListener, new IntentFilter(f));
                    MusicUtils.sService.openFileAsync(getIntent().getData().toString());
                } catch (RemoteException ex) {
                }
            }

            public void onServiceDisconnected(ComponentName classname) {
            }
        });
    }
    
    private BroadcastReceiver mStatusListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(MediaPlaybackService.PLAYBACK_COMPLETE)) {
                // You would come here only in case of a failure in the
                // MediaPlayerService before PrepareAsync completes
                String msg = getString(R.string.fail_to_start_stream);
                Toast mt = Toast.makeText(StreamStarter.this, msg, Toast.LENGTH_SHORT);
                mt.show();
                finish();
                return;
            }
            try {
                MusicUtils.sService.play();
                intent = new Intent("com.android.music.PLAYBACK_VIEWER");
                intent.putExtra("oneshot", true);
                startActivity(intent);
            } catch (RemoteException ex) {
            }
            finish();
        }
    };

    @Override
    public void onPause() {
        if (MusicUtils.sService != null) {
            try {
                // This looks a little weird (when it's not playing, stop playing)
                // but it is correct. When nothing is playing, it means that this
                // was paused before a connection was established, in which case
                // we stop trying to connect/play.
                // Otherwise, this call to onPause() was a result of the call to
                // finish() above, and we should let playback continue.
                if (! MusicUtils.sService.isPlaying()) {
                    MusicUtils.sService.stop();
                }
            } catch (RemoteException ex) {
            }
        }
        unregisterReceiver(mStatusListener);
        MusicUtils.unbindFromService(this);
        super.onPause();
    }
}
