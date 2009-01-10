/*
 * Copyright (C) 2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.music.tests;

import com.android.music.tests.stress.AlbumsPlaybackStress;
import com.android.music.tests.stress.MusicPlaybackStress;

import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;

import junit.framework.TestSuite;

/**
 * Instrumentation Test Runner for all music player stress tests.
 * 
 * Running all tests:
 *
 * adb shell am instrument \
 *   -w com.android.music.tests/.MusicPlayerStressTestRunner
 */

public class MusicPlayerStressTestRunner extends InstrumentationTestRunner {

  @Override
    public TestSuite getAllTests() {
      TestSuite suite = new InstrumentationTestSuite(this);  
      //suite.addTestSuite(MusicPlaybackStress.class);
      suite.addTestSuite(AlbumsPlaybackStress.class);
      return suite;
    }

    @Override
    public ClassLoader getLoader() {
      return MusicPlayerStressTestRunner.class.getClassLoader();
    }
}

