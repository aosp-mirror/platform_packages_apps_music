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
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.KeyEvent;
import android.util.Log;

import com.android.music.AlbumBrowserActivity;
import com.android.music.tests.MusicPlayerNames;

public class AlbumsPlaybackStress extends ActivityInstrumentationTestCase <AlbumBrowserActivity>{
  
  private Activity browseActivity;
  private String[] testing;
  private String TAG = "AlbumsPlaybackStress";
  
  public AlbumsPlaybackStress() {
      super("com.android.music",AlbumBrowserActivity.class);
  }
  
  @Override 
  protected void setUp() throws Exception { 
      super.setUp(); 
  }
  
  @Override 
  protected void tearDown() throws Exception {   
      super.tearDown();           
  }

  /*
   * Test case: Keeps launching music playback from Albums and then go 
   * back to the album screen
   * Verification: Check if it is in low memory
   * The test depends on the test media in the sdcard
   */
    @LargeTest
    public void testAlbumPlay() { 
      Instrumentation inst = getInstrumentation();
      try{
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        Thread.sleep(MusicPlayerNames.WAIT_LONG_TIME);
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);     
        for(int i=0; i< MusicPlayerNames.NO_ALBUMS_TOBE_PLAYED; i++){
          inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
          inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
          inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
          Thread.sleep(MusicPlayerNames.WAIT_LONG_TIME);
          inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
          inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);       
        } 
      }catch (Exception e){
          Log.v(TAG, e.toString());
      }
      inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    
      //Verification: check if it is in low memory
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      ((ActivityManager)getActivity().getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
      assertFalse(TAG, mi.lowMemory); 
     
   
  }
}
