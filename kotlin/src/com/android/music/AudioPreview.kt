/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.music

import android.app.Activity
import android.content.AsyncQueryHandler
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaPlayer.OnErrorListener
import android.media.MediaPlayer.OnPreparedListener
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

/**
 * Dialog that comes up in response to various music-related VIEW intents.
 */
class AudioPreview : Activity(), OnPreparedListener, OnErrorListener, OnCompletionListener {
    private lateinit var mTextLine1: TextView
    private lateinit var mTextLine2: TextView
    private lateinit var mLoadingText: TextView
    private lateinit var mSeekBar: SeekBar
    private lateinit var mAudioManager: AudioManager

    private var mPlayer: PreviewPlayer? = null
    private var mSeeking = false
    private var mUiPaused = true
    private var mDuration = 0
    private var mUri: Uri? = null
    private var mMediaId: Long = -1
    private var mPausedByTransientLossOfFocus = false

    private val mProgressRefresher = Handler()

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        val intent: Intent? = getIntent()
        if (intent == null) {
            finish()
            return
        }
        mUri = intent.getData()
        if (mUri == null) {
            finish()
            return
        }
        val scheme: String? = mUri?.getScheme()
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.audiopreview)
        mTextLine1 = findViewById(R.id.line1) as TextView
        mTextLine2 = findViewById(R.id.line2) as TextView
        mLoadingText = findViewById(R.id.loading) as TextView
        if (scheme == "http") {
            val msg: String = getString(R.string.streamloadingtext, mUri!!.getHost())
            mLoadingText.setText(msg)
        } else {
            mLoadingText.setVisibility(View.GONE)
        }
        mSeekBar = findViewById(R.id.progress) as SeekBar
        mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val player = getLastNonConfigurationInstance() as PreviewPlayer?
        if (player == null) {
            mPlayer = PreviewPlayer()
            mPlayer!!.setActivity(this)
            try {
                mPlayer!!.setDataSourceAndPrepare(mUri!!)
            } catch (ex: Exception) {
                // catch generic Exception, since we may be called with a media
                // content URI, another content provider's URI, a file URI,
                // an http URI, and there are different exceptions associated
                // with failure to open each of those.
                Log.d(TAG, "Failed to open file: $ex")
                Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        } else {
            mPlayer = player
            mPlayer!!.setActivity(this)
            // onResume will update the UI
        }
        val mAsyncQueryHandler: AsyncQueryHandler = object : AsyncQueryHandler(getContentResolver()) {
            protected override fun onQueryComplete(token: Int, cookie: Any?, cursor: Cursor?) {
                if (cursor != null && cursor.moveToFirst()) {
                    val titleIdx: Int = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistIdx: Int = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val idIdx: Int = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    val displaynameIdx: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idIdx >= 0) {
                        mMediaId = cursor.getLong(idIdx)
                    }
                    if (titleIdx >= 0) {
                        val title: String = cursor.getString(titleIdx)
                        mTextLine1.setText(title)
                        if (artistIdx >= 0) {
                            val artist: String = cursor.getString(artistIdx)
                            mTextLine2.setText(artist)
                        }
                    } else if (displaynameIdx >= 0) {
                        val name: String = cursor.getString(displaynameIdx)
                        mTextLine1.setText(name)
                    } else {
                        // Couldn't find anything to display, what to do now?
                        Log.w(TAG, "Cursor had no names for us")
                    }
                } else {
                    Log.w(TAG, "empty cursor")
                }
                cursor?.let {
                    it.close()
                }
                setNames()
            }
        }
        if (scheme == ContentResolver.SCHEME_CONTENT) {
            if (mUri!!.getAuthority() === MediaStore.AUTHORITY) {
                // try to get title and artist from the media content provider
                mAsyncQueryHandler.startQuery(0, null, mUri, arrayOf<String>(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
                        null, null, null)
            } else {
                // Try to get the display name from another content provider.
                // Don't specifically ask for the display name though, since the
                // provider might not actually support that column.
                mAsyncQueryHandler.startQuery(0, null, mUri, null, null, null, null)
            }
        } else if (scheme == "file") {
            // check if this file is in the media database (clicking on a download
            // in the download manager might follow this path
            val path: String? = mUri?.getPath()
            mAsyncQueryHandler.startQuery(0, null, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf<String>(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST), MediaStore.Audio.Media.DATA.toString() + "=?", arrayOf(path), null)
        } else {
            // We can't get metadata from the file/stream itself yet, because
            // that API is hidden, so instead we display the URI being played
            if (mPlayer!!.isPrepared) {
                setNames()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mUiPaused = true
        mProgressRefresher.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        mUiPaused = false
        if (mPlayer!!.isPrepared) {
            showPostPrepareUI()
        }
    }

    override fun onRetainNonConfigurationInstance(): Any? {
        val player = mPlayer
        mPlayer = null
        return player
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun stopPlayback() {
        mProgressRefresher.removeCallbacksAndMessages(null)
        if (mPlayer != null) {
            mPlayer?.release()
            mPlayer = null
            mAudioManager.abandonAudioFocus(mAudioFocusListener)
        }
    }

    override fun onUserLeaveHint() {
        stopPlayback()
        finish()
        super.onUserLeaveHint()
    }

    override fun onPrepared(mp: MediaPlayer?) {
        if (isFinishing()) return
        mPlayer = mp as PreviewPlayer
        setNames()
        mPlayer?.start()
        showPostPrepareUI()
    }

    private fun showPostPrepareUI() {
        val pb: ProgressBar = findViewById(R.id.spinner) as ProgressBar
        pb.setVisibility(View.GONE)
        mDuration = mPlayer!!.getDuration()
        if (mDuration != 0) {
            mSeekBar.setMax(mDuration)
            mSeekBar.setVisibility(View.VISIBLE)
            if (!mSeeking) {
                mSeekBar.setProgress(mPlayer!!.getCurrentPosition())
            }
        }
        mSeekBar.setOnSeekBarChangeListener(mSeekListener)
        mLoadingText.setVisibility(View.GONE)
        val v: View = findViewById(R.id.titleandbuttons)
        v.setVisibility(View.VISIBLE)
        mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        mProgressRefresher.removeCallbacksAndMessages(null)
        mProgressRefresher.postDelayed(ProgressRefresher(), 200)
        updatePlayPause()
    }

    private val mAudioFocusListener: OnAudioFocusChangeListener = object : OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {
            if (mPlayer == null) {
                // this activity has handed its MediaPlayer off to the next activity
                // (e.g. portrait/landscape switch) and should abandon its focus
                mAudioManager.abandonAudioFocus(this)
                return
            }
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    mPausedByTransientLossOfFocus = false
                    mPlayer?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (mPlayer!!.isPlaying()) {
                    mPausedByTransientLossOfFocus = true
                    mPlayer?.pause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (mPausedByTransientLossOfFocus) {
                    mPausedByTransientLossOfFocus = false
                    start()
                }
            }
            updatePlayPause()
        }
    }

    private fun start() {
        mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        mPlayer?.start()
        mProgressRefresher.postDelayed(ProgressRefresher(), 200)
    }

    private fun setNames() {
        if (TextUtils.isEmpty(mTextLine1.getText())) {
            mTextLine1.setText(mUri!!.getLastPathSegment())
        }
        if (TextUtils.isEmpty(mTextLine2.getText())) {
            mTextLine2.setVisibility(View.GONE)
        } else {
            mTextLine2.setVisibility(View.VISIBLE)
        }
    }

    internal inner class ProgressRefresher : Runnable {
        override fun run() {
            if (mPlayer != null && !mSeeking && mDuration != 0) {
                mSeekBar.setProgress(mPlayer!!.getCurrentPosition())
            }
            mProgressRefresher.removeCallbacksAndMessages(null)
            if (!mUiPaused) {
                mProgressRefresher.postDelayed(ProgressRefresher(), 200)
            }
        }
    }

    private fun updatePlayPause() {
        val b: ImageButton? = findViewById(R.id.playpause) as ImageButton?
        if (b != null && mPlayer != null) {
            if (mPlayer!!.isPlaying()) {
                b.setImageResource(R.drawable.btn_playback_ic_pause_small)
            } else {
                b.setImageResource(R.drawable.btn_playback_ic_play_small)
                mProgressRefresher.removeCallbacksAndMessages(null)
            }
        }
    }

    private val mSeekListener: OnSeekBarChangeListener = object : OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar?) {
            mSeeking = true
        }

        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromuser: Boolean) {
            if (!fromuser) {
                return
            }
            // Protection for case of simultaneously tapping on seek bar and exit
            mPlayer?.let{
                it.seekTo(progress)
            }
        }

        override fun onStopTrackingTouch(bar: SeekBar?) {
            mSeeking = false
        }
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show()
        finish()
        return true
    }

    override fun onCompletion(mp: MediaPlayer?) {
        mSeekBar.setProgress(mDuration)
        updatePlayPause()
    }

    fun playPauseClicked(v: View?) {
        // Protection for case of simultaneously tapping on play/pause and exit
        mPlayer?.let {
            if (it.isPlaying()) {
                it.pause()
            } else {
                start()
            }
            updatePlayPause()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        // TODO: if mMediaId != -1, then the playing file has an entry in the media
        // database, and we could open it in the full music app instead.
        // Ideally, we would hand off the currently running mediaplayer
        // to the music UI, which can probably be done via a public static
        menu.add(0, OPEN_IN_MUSIC, 0, "open in music")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item: MenuItem = menu.findItem(OPEN_IN_MUSIC)
        if (mMediaId >= 0) {
            item.setVisible(true)
            return true
        }
        item.setVisible(false)
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (mPlayer!!.isPlaying()) {
                    mPlayer?.pause()
                } else {
                    start()
                }
                updatePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                start()
                updatePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (mPlayer!!.isPlaying()) {
                    mPlayer?.pause()
                }
                updatePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND -> return true
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_BACK -> {
                stopPlayback()
                finish()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
    }

    /*
     * Wrapper class to help with handing off the MediaPlayer to the next instance
     * of the activity in case of orientation change, without losing any state.
     */
    private class PreviewPlayer : MediaPlayer(), OnPreparedListener {
        private lateinit var mActivity: AudioPreview
        var isPrepared = false

        fun setActivity(activity: AudioPreview) {
            mActivity = activity
            setOnPreparedListener(this)
            setOnErrorListener(mActivity)
            setOnCompletionListener(mActivity)
        }

        @Throws(IllegalArgumentException::class, SecurityException::class, IllegalStateException::class, IOException::class)
        fun setDataSourceAndPrepare(uri: Uri) {
            setDataSource(mActivity, uri)
            prepareAsync()
        }

        /* (non-Javadoc)
         * @see android.media.MediaPlayer.OnPreparedListener#onPrepared(android.media.MediaPlayer)
         */
        override fun onPrepared(mp: MediaPlayer?) {
            isPrepared = true
            mActivity.onPrepared(mp)
        }
    }

    companion object {
        private const val TAG = "AudioPreview"
        private const val OPEN_IN_MUSIC = 1
    }
}