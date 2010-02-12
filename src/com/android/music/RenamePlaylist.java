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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.AudioManager;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class RenamePlaylist extends Activity
{
    private EditText mPlaylist;
    private TextView mPrompt;
    private Button mSaveButton;
    private long mRenameId;
    private String mOriginalName;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.create_playlist);
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                                    WindowManager.LayoutParams.WRAP_CONTENT);

        mPrompt = (TextView)findViewById(R.id.prompt);
        mPlaylist = (EditText)findViewById(R.id.playlist);
        mSaveButton = (Button) findViewById(R.id.create);
        mSaveButton.setOnClickListener(mOpenClicked);

        ((Button)findViewById(R.id.cancel)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        mRenameId = icicle != null ? icicle.getLong("rename")
                : getIntent().getLongExtra("rename", -1);
        mOriginalName = nameForId(mRenameId);
        String defaultname = icicle != null ? icicle.getString("defaultname") : mOriginalName;
        
        if (mRenameId < 0 || mOriginalName == null || defaultname == null) {
            Log.i("@@@@", "Rename failed: " + mRenameId + "/" + defaultname);
            finish();
            return;
        }
        
        String promptformat;
        if (mOriginalName.equals(defaultname)) {
            promptformat = getString(R.string.rename_playlist_same_prompt);
        } else {
            promptformat = getString(R.string.rename_playlist_diff_prompt);
        }
                
        String prompt = String.format(promptformat, mOriginalName, defaultname);
        mPrompt.setText(prompt);
        mPlaylist.setText(defaultname);
        mPlaylist.setSelection(defaultname.length());
        mPlaylist.addTextChangedListener(mTextWatcher);
        setSaveButton();
    }
    
    TextWatcher mTextWatcher = new TextWatcher() {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // don't care about this one
        }
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // check if playlist with current name exists already, and warn the user if so.
            setSaveButton();
        };
        public void afterTextChanged(Editable s) {
            // don't care about this one
        }
    };
    
    private void setSaveButton() {
        String typedname = mPlaylist.getText().toString();
        if (typedname.trim().length() == 0) {
            mSaveButton.setEnabled(false);
        } else {
            mSaveButton.setEnabled(true);
            if (idForplaylist(typedname) >= 0
                    && ! mOriginalName.equals(typedname)) {
                mSaveButton.setText(R.string.create_playlist_overwrite_text);
            } else {
                mSaveButton.setText(R.string.create_playlist_create_text);
            }
        }

    }
    
    private int idForplaylist(String name) {
        Cursor c = MusicUtils.query(this, MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.Audio.Playlists._ID },
                MediaStore.Audio.Playlists.NAME + "=?",
                new String[] { name },
                MediaStore.Audio.Playlists.NAME);
        int id = -1;
        if (c != null) {
            c.moveToFirst();
            if (!c.isAfterLast()) {
                id = c.getInt(0);
            }
        }
        c.close();
        return id;
    }
    
    private String nameForId(long id) {
        Cursor c = MusicUtils.query(this, MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.Audio.Playlists.NAME },
                MediaStore.Audio.Playlists._ID + "=?",
                new String[] { Long.valueOf(id).toString() },
                MediaStore.Audio.Playlists.NAME);
        String name = null;
        if (c != null) {
            c.moveToFirst();
            if (!c.isAfterLast()) {
                name = c.getString(0);
            }
        }
        c.close();
        return name;
    }
    
    
    @Override
    public void onSaveInstanceState(Bundle outcicle) {
        outcicle.putString("defaultname", mPlaylist.getText().toString());
        outcicle.putLong("rename", mRenameId);
    }
    
    @Override
    public void onResume() {
        super.onResume();
    }

    private View.OnClickListener mOpenClicked = new View.OnClickListener() {
        public void onClick(View v) {
            String name = mPlaylist.getText().toString();
            if (name != null && name.length() > 0) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues(1);
                values.put(MediaStore.Audio.Playlists.NAME, name);
                resolver.update(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                        values,
                        MediaStore.Audio.Playlists._ID + "=?",
                        new String[] { Long.valueOf(mRenameId).toString()});
                
                setResult(RESULT_OK);
                Toast.makeText(RenamePlaylist.this, R.string.playlist_renamed_message, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    };
}
