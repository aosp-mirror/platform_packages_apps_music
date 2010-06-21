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
package com.android.music.tests.stress;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.KeyEvent;
import android.util.Log;
import android.content.Context;


import com.android.music.MusicBrowserActivity;
import com.android.music.MusicUtils;
import com.android.music.TrackBrowserActivity;
import com.android.music.tests.MusicPlayerNames;

public class MusicPlaybackStress extends ActivityInstrumentationTestCase <TrackBrowserActivity>{
    private static String TAG = "mediaplayertests";
  
    public MusicPlaybackStress() {
      super("com.android.music",TrackBrowserActivity.class);
    }
  
    @Override 
    protected void setUp() throws Exception { 
      super.setUp(); 
    }
  
    @Override 
    protected void tearDown() throws Exception {   
      super.tearDown();           
    }

    @LargeTest
    public void testPlayAllSongs() {
      Activity mediaPlaybackActivity;
      try{
        Instrumentation inst = getInstrumentation();
        ActivityMonitor mediaPlaybackMon = inst.addMonitor("com.android.music.MediaPlaybackActivity", 
          null, false);
        inst.invokeMenuActionSync(getActivity(), MusicUtils.Defs.CHILD_MENU_BASE + 3, 0);
        Thread.sleep(MusicPlayerNames.WAIT_LONG_TIME);
        mediaPlaybackActivity = mediaPlaybackMon.waitForActivityWithTimeout(2000);
        for (int i=0;i< MusicPlayerNames.NO_SKIPPING_SONGS;i++){               
          Thread.sleep(MusicPlayerNames.SKIP_WAIT_TIME);
          if (i==0){
            //Set the repeat all
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
     
            //Set focus on the next button
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
          }
          inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);      
        }   
        mediaPlaybackActivity.finish();
      }catch (Exception e){
        Log.e(TAG, e.toString());
      }
      //Verification: check if it is in low memory
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      ((ActivityManager)getActivity().getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
      assertFalse(TAG, mi.lowMemory);      
    }
}
