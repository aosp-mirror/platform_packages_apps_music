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

package com.android.music;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.gadget.GadgetManager;
import android.gadget.GadgetProvider;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.media.MediaFile;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Config;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Simple gadget to show currently playing album art along
 * with play/pause and next track buttons.  
 */
public class MediaGadgetProvider extends GadgetProvider {
    static final String TAG = "MusicGadgetProvider";
    
    public static final String CMDGADGETUPDATE = "gadgetupdate";
    
    static final ComponentName THIS_GADGET =
        new ComponentName("com.android.music",
                "com.android.music.MediaGadgetProvider");
    
    private static MediaGadgetProvider sInstance;
    
    static synchronized MediaGadgetProvider getInstance() {
        if (sInstance == null) {
            sInstance = new MediaGadgetProvider();
        }
        return sInstance;
    }

    @Override
    public void onUpdate(Context context, GadgetManager gadgetManager, int[] gadgetIds) {
        defaultGadget(context, gadgetIds);
        
        // Send broadcast intent to any running MediaPlaybackService so it can
        // wrap around with an immediate update.
        Intent updateIntent = new Intent(MediaPlaybackService.SERVICECMD);
        updateIntent.putExtra(MediaPlaybackService.CMDNAME,
                MediaGadgetProvider.CMDGADGETUPDATE);
        updateIntent.putExtra(GadgetManager.EXTRA_GADGET_IDS, gadgetIds);
        updateIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        context.sendBroadcast(updateIntent);
    }
    
    /**
     * Initialize given gadgets to default state, where we launch Music on default click
     * and hide actions if service not running.
     */
    private void defaultGadget(Context context, int[] gadgetIds) {
        final Resources res = context.getResources();
        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.gadget);
        
        views.setTextViewText(R.id.title, res.getText(R.string.emptyplaylist));

        linkButtons(context, views, false /* not playing */);
        pushUpdate(context, gadgetIds, views);
    }
    
    private void pushUpdate(Context context, int[] gadgetIds, RemoteViews views) {
        // Update specific list of gadgetIds if given, otherwise default to all
        final GadgetManager gm = GadgetManager.getInstance(context);
        if (gadgetIds != null) {
            gm.updateGadget(gadgetIds, views);
        } else {
            final ComponentName thisGadget = new ComponentName(context,
                    MediaGadgetProvider.class);
            gm.updateGadget(thisGadget, views);
        }
    }
    
    /**
     * Check against {@link GadgetManager} if there are any instances of this gadget.
     */
    private boolean hasInstances(Context context) {
        GadgetManager gadgetManager = GadgetManager.getInstance(context);
        int[] gadgetIds = gadgetManager.getGadgetIds(THIS_GADGET);
        return (gadgetIds.length > 0);
    }

    /**
     * Handle a change notification coming over from {@link MediaPlaybackService}
     */
    void notifyChange(MediaPlaybackService service, String what) {
        if (hasInstances(service)) {
            if (MediaPlaybackService.PLAYBACK_COMPLETE.equals(what) ||
                    MediaPlaybackService.META_CHANGED.equals(what) ||
                    MediaPlaybackService.PLAYSTATE_CHANGED.equals(what)) {
                performUpdate(service, null);
            }
        }
    }
    
    /**
     * Update all active gadget instances by pushing changes 
     */
    void performUpdate(MediaPlaybackService service, int[] gadgetIds) {
        final Resources res = service.getResources();
        final RemoteViews views = new RemoteViews(service.getPackageName(), R.layout.gadget);
        
        final int track = service.getQueuePosition() + 1;
        final String titleName = service.getTrackName();
        final String artistName = service.getArtistName();
        
        // Format title string with track number, or show SD card message
        CharSequence titleString = "";
        String status = Environment.getExternalStorageState();
        if (titleName != null) {
            titleString = res.getString(R.string.gadget_track_num_title, track, titleName);
        } else if (status.equals(Environment.MEDIA_SHARED) ||
                status.equals(Environment.MEDIA_UNMOUNTED)) {
            titleString = res.getText(R.string.sdcard_busy_title);
        } else if (status.equals(Environment.MEDIA_REMOVED)) {
            titleString = res.getText(R.string.sdcard_missing_title);
        } else {
            titleString = res.getText(R.string.emptyplaylist);
        }
        
        views.setTextViewText(R.id.title, titleString);
        views.setTextViewText(R.id.artist, artistName);
        
        // Set correct drawable for pause state
        final boolean playing = service.isPlaying();
        views.setImageViewResource(R.id.control_play, playing ?
                R.drawable.gadget_pause : R.drawable.gadget_play);

        // Link actions buttons to intents
        linkButtons(service, views, playing);
        
        pushUpdate(service, gadgetIds, views);
    }

    /**
     * Link up various button actions using {@link PendingIntents}.
     * 
     * @param playerActive True if player is active in background, which means
     *            gadget click will launch {@link MediaPlaybackActivity},
     *            otherwise we launch {@link MusicBrowserActivity}.
     */
    private void linkButtons(Context context, RemoteViews views, boolean playerActive) {
        // Connect up various buttons and touch events
        Intent intent;
        PendingIntent pendingIntent;
        
        final ComponentName serviceName = new ComponentName(context, MediaPlaybackService.class);
        
        if (playerActive) {
            intent = new Intent(context, MediaPlaybackActivity.class);
            pendingIntent = PendingIntent.getActivity(context,
                    0 /* no requestCode */, intent, 0 /* no flags */);
            views.setOnClickPendingIntent(R.id.album_gadget, pendingIntent);
        } else {
            intent = new Intent(context, MusicBrowserActivity.class);
            pendingIntent = PendingIntent.getActivity(context,
                    0 /* no requestCode */, intent, 0 /* no flags */);
            views.setOnClickPendingIntent(R.id.album_gadget, pendingIntent);
        }
        
        intent = new Intent(MediaPlaybackService.TOGGLEPAUSE_ACTION);
        intent.setComponent(serviceName);
        pendingIntent = PendingIntent.getService(context,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.control_play, pendingIntent);
        
        intent = new Intent(MediaPlaybackService.NEXT_ACTION);
        intent.setComponent(serviceName);
        pendingIntent = PendingIntent.getService(context,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.control_next, pendingIntent);
    }
}
