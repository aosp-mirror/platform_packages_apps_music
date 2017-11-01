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

import android.Manifest.permission;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import com.android.music.utils.LogHelper;

public class MusicBrowserActivity extends Activity {
    private static final String TAG = LogHelper.makeLogTag(MusicBrowserActivity.class);

    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 42;

    public MusicBrowserActivity() {}

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        LogHelper.d(TAG, "onCreate()");
        if (checkSelfPermission(permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {permission.READ_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
            return;
        }
        initApp();
    }

    public void initApp() {
        int activeTab = MusicUtils.getIntPref(this, "activetab", R.id.artisttab);
        LogHelper.d(TAG, "initApp() activeTab = ", activeTab);
        if (activeTab != R.id.artisttab && activeTab != R.id.albumtab && activeTab != R.id.songtab
                && activeTab != R.id.playlisttab) {
            activeTab = R.id.artisttab;
        }
        MusicUtils.activateTab(this, activeTab);
    }

    @Override
    public void onDestroy() {
        LogHelper.d(TAG, "onDestroy()");
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE: {
                if (grantResults.length == 0
                        || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    finish();
                    return;
                }
                initApp();
            }
        }
    }
}
