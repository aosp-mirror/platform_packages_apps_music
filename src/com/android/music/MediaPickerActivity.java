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

import com.android.music.MusicUtils.ServiceToken;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import java.util.ArrayList;

public class MediaPickerActivity extends ListActivity implements MusicUtils.Defs
{
    private ServiceToken mToken;

    public MediaPickerActivity()
    {
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);

        mFirstYear = getIntent().getStringExtra("firstyear");
        mLastYear = getIntent().getStringExtra("lastyear");

        if (mFirstYear == null) {
            setTitle(R.string.all_title);
        } else if (mFirstYear.equals(mLastYear)) {
            setTitle(mFirstYear);
        } else {
            setTitle(mFirstYear + "-" + mLastYear);
        }
        mToken = MusicUtils.bindToService(this);
        init();
    }

    @Override
    public void onDestroy() {
        MusicUtils.unbindFromService(mToken);
        super.onDestroy();
        if (mCursor != null) {
            mCursor.close();
        }
    }

    public void init() {

        setContentView(R.layout.media_picker_activity);

        MakeCursor();
        if (null == mCursor || 0 == mCursor.getCount()) {
            return;
        }

        PickListAdapter adapter = new PickListAdapter(
                this,
                R.layout.track_list_item,
                mCursor,
                new String[] {},
                new int[] {});

        setListAdapter(adapter);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id)
    {
        mCursor.moveToPosition(position);
        String type = mCursor.getString(mCursor.getColumnIndexOrThrow(
                MediaStore.Audio.Media.MIME_TYPE));

        String action = getIntent().getAction();
        if (Intent.ACTION_GET_CONTENT.equals(action)) {
            Uri uri;

            long mediaId;
            if (type.startsWith("video")) {
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                mediaId = mCursor.getLong(mCursor.getColumnIndexOrThrow(
                        MediaStore.Video.Media._ID));
            } else {
                uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                mediaId = mCursor.getLong(mCursor.getColumnIndexOrThrow(
                        MediaStore.Audio.Media._ID));
            }

            setResult(RESULT_OK, new Intent().setData(ContentUris.withAppendedId(uri, mediaId)));
            finish();
            return;
        }

        // Need to stop the playbackservice, in case it is busy playing audio
        // and the user selected a video.
        if (MusicUtils.sService != null) {
            try {
                MusicUtils.sService.stop();
            } catch (RemoteException ex) {
            }
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), type);

        startActivity(intent);
    }

    private void MakeCursor() {
        String[] audiocols = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.YEAR
        };
        String[] videocols = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE
        };

        Cursor[] cs;
        // Use ArrayList for the moment, since we don't know the size of
        // Cursor[]. If the length of Corsor[] larger than really used,
        // a NPE will come up when access the content of Corsor[].
        ArrayList<Cursor> cList = new ArrayList<Cursor>();
        Intent intent = getIntent();
        String type = intent.getType();

        if (mFirstYear != null) {
            // If mFirstYear is not null, the picker only for audio because
            // video has no year column.
            if(type.equals("video/*")) {
                mCursor = null;
                return;
            }

            mWhereClause = MediaStore.Audio.Media.YEAR + ">=" + mFirstYear + " AND " +
                           MediaStore.Audio.Media.YEAR + "<=" + mLastYear;
        }

        // If use Cursor[] as before, the Cursor[i] could be null when there is
        // no video/audio/sdcard. Then a NPE will come up when access the content of the
        // Array.

        Cursor c;
        if (type.equals("video/*")) {
            // Only video.
            c = MusicUtils.query(this, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videocols, null , null, mSortOrder);
            if (c != null) {
                cList.add(c);
            }
        } else {
            c = MusicUtils.query(this, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    audiocols, mWhereClause , null, mSortOrder);

            if (c != null) {
                cList.add(c);
            }

            if (mFirstYear == null && intent.getType().equals("media/*")) {
                // video has no year column
                c = MusicUtils.query(this, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videocols, null , null, mSortOrder);
                if (c != null) {
                    cList.add(c);
                }
            }
        }

        // Get the ArrayList size.
        int size = cList.size();
        if (0 == size) {
            // If no video/audio/SDCard exist, return.
            mCursor = null;
            return;
        }

        // The size is known now, we're sure each item of Cursor[] is not null.
        cs = new Cursor[size];
        cs = cList.toArray(cs);
        mCursor = new SortCursor(cs, MediaStore.Audio.Media.TITLE);
    }

    private Cursor mCursor;
    private String mSortOrder = MediaStore.Audio.Media.TITLE + " COLLATE UNICODE";
    private String mFirstYear;
    private String mLastYear;
    private String mWhereClause;

    static class PickListAdapter extends SimpleCursorAdapter {
        int mTitleIdx;
        int mArtistIdx;
        int mAlbumIdx;
        int mMimeIdx;

        PickListAdapter(Context context, int layout, Cursor cursor, String[] from, int[] to) {
            super(context, layout, cursor, from, to);

            mTitleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            mArtistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            mAlbumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            mMimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
        }
        
        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
           View v = super.newView(context, cursor, parent);
           ImageView iv = (ImageView) v.findViewById(R.id.icon);
           iv.setVisibility(View.VISIBLE);
           ViewGroup.LayoutParams p = iv.getLayoutParams();
           p.width = ViewGroup.LayoutParams.WRAP_CONTENT;
           p.height = ViewGroup.LayoutParams.WRAP_CONTENT;

           TextView tv = (TextView) v.findViewById(R.id.duration);
           tv.setVisibility(View.GONE);
           iv = (ImageView) v.findViewById(R.id.play_indicator);
           iv.setVisibility(View.GONE);
           
           return v;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {

            TextView tv = (TextView) view.findViewById(R.id.line1);
            String name = cursor.getString(mTitleIdx);
            tv.setText(name);
            
            tv = (TextView) view.findViewById(R.id.line2);
            name = cursor.getString(mAlbumIdx);
            StringBuilder builder = new StringBuilder();
            if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                builder.append(context.getString(R.string.unknown_album_name));
            } else {
                builder.append(name);
            }
            builder.append("\n");
            name = cursor.getString(mArtistIdx);
            if (name == null || name.equals(MediaStore.UNKNOWN_STRING)) {
                builder.append(context.getString(R.string.unknown_artist_name));
            } else {
                builder.append(name);
            }
            tv.setText(builder.toString());

            String text = cursor.getString(mMimeIdx);
            ImageView iv = (ImageView) view.findViewById(R.id.icon);;
            if("audio/midi".equals(text)) {
                iv.setImageResource(R.drawable.midi);
            } else if(text != null && (text.startsWith("audio") ||
                    text.equals("application/ogg") ||
                    text.equals("application/x-ogg"))) {
                iv.setImageResource(R.drawable.ic_search_category_music_song);
            } else if(text != null && text.startsWith("video")) {
                iv.setImageResource(R.drawable.movie);
            } else {
                iv.setImageResource(0);
            }
        }
    }
}
