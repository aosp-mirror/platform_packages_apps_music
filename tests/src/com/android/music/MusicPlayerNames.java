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

package com.android.music.tests;

import android.os.Environment;

/**
 *
 * This class has the names of the all the activity name and variables
 * in the instrumentation test.
 *
 */
public class MusicPlayerNames {

  //Expected result of the sorted playlistname
    public static final String expectedPlaylistTitle[] = { "**1E?:|}{[]~~.,;'",
        "//><..", "0123456789",
        "0random@112", "MyPlaylist", "UPPERLETTER",
        "combination011", "loooooooog",
        "normal", "~!@#$%^&*()_+"
    };

  //Unsorted input playlist name
    public static final String unsortedPlaylistTitle[] = { "//><..","MyPlaylist",
        "0random@112", "UPPERLETTER","normal",
        "combination011", "0123456789",
        "~!@#$%^&*()_+","**1E?:|}{[]~~.,;'",
        "loooooooog"
    };

    public static final String DELETE_PLAYLIST_NAME = "testDeletPlaylist";
    public static final String ORIGINAL_PLAYLIST_NAME = "original_playlist_name";
    public static final String RENAMED_PLAYLIST_NAME = "rename_playlist_name";

    public static int NO_OF_PLAYLIST = 10;
    public static int WAIT_SHORT_TIME = 1000;
    public static int WAIT_LONG_TIME = 2000;
    public static int WAIT_VERY_LONG_TIME = 6000;
    public static int SKIP_WAIT_TIME = 500;
    public static int DEFAULT_PLAYLIST_LENGTH = 15;
    public static int NO_ALBUMS_TOBE_PLAYED = 50;
    public static int NO_SKIPPING_SONGS = 500;

    public static final String EXTERNAL_DIR =
        Environment.getExternalStorageDirectory().toString();
    public static final String DELETESONG = EXTERNAL_DIR + "/toBeDeleted.amr";
    public static final String GOLDENSONG = EXTERNAL_DIR + "/media_api/music/AMRNB.amr";
    public static final String TOBEDELETESONGNAME = "toBeDeleted";

    public static int EXPECTED_NO_RINGTONE = 1;
}
