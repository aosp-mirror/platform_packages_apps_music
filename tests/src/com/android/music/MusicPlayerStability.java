/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.music.tests;

import android.app.Instrumentation;
import com.android.music.TrackBrowserActivity;
import android.view.KeyEvent;
import android.widget.ListView;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * Junit / Instrumentation test case for the Music Player
 */

public class MusicPlayerStability extends ActivityInstrumentationTestCase2 <TrackBrowserActivity>{
    private static String TAG = "musicplayerstability";
    private static int PLAY_TIME = 30000;
    private ListView mTrackList;

    public MusicPlayerStability() {
        super("com.android.music",TrackBrowserActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        getActivity();
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test case 1: This test case is for the power and general stability
     * measurment. We don't need to do the validation. This test case simply
     * play the mp3 for 30 seconds then stop.
     * The sdcard should have the target mp3 files.
     */
    @LargeTest
    public void testPlay30sMP3() throws Exception {
        // Launch the songs list. Pick the fisrt song and play
        try {
            Instrumentation inst = getInstrumentation();
            //Make sure the song list shown up
            Thread.sleep(2000);
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            mTrackList = getActivity().getListView();
            int scrollCount = mTrackList.getMaxScrollAmount();
            //Make sure there is at least one song in the sdcard
            if (scrollCount != -1) {
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
            } else {
                assertTrue("testPlayMP3", false);
            }
            Thread.sleep(PLAY_TIME);
        } catch (Exception e) {
            assertTrue("testPlayMP3", false);
        }
    }

    @LargeTest
    public void testLaunchMusicPlayer() throws Exception {
        // Launch music player and sleep for 30 seconds to capture
        // the music player power usage base line.
        try {
            Thread.sleep(PLAY_TIME);
        } catch (Exception e) {
            assertTrue("MusicPlayer Do Nothing", false);
        }
        assertTrue("MusicPlayer Do Nothing", true);
    }
}
