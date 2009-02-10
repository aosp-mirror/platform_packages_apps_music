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
import android.gadget.GadgetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Config;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Simple gadget to show currently playing album art along
 * with play/pause and next track buttons.  
 */
public class MediaGadgetProvider extends BroadcastReceiver {
    static final String TAG = "MusicGadgetProvider";
    static final boolean LOGD = Config.LOGD || false;
    
    public static final String CMDGADGETUPDATE = "gadgetupdate";
    
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (GadgetManager.GADGET_ENABLED_ACTION.equals(action)) {
            if (LOGD) Log.d(TAG, "ENABLED");
        } else if (GadgetManager.GADGET_DISABLED_ACTION.equals(action)) {
            if (LOGD) Log.d(TAG, "DISABLED");
        } else if (GadgetManager.GADGET_UPDATE_ACTION.equals(action)) {
            if (LOGD) Log.d(TAG, "UPDATE");
            int[] gadgetIds = intent.getIntArrayExtra(GadgetManager.EXTRA_GADGET_IDS);
            
            defaultGadget(context, gadgetIds);
            
            // Send broadcast intent to any running MediaPlaybackService so it can
            // wrap around with an immediate update.
            Intent updateIntent = new Intent(MediaPlaybackService.SERVICECMD);
            updateIntent.putExtra(MediaPlaybackService.CMDNAME,
                    MediaGadgetProvider.CMDGADGETUPDATE);
            updateIntent.putExtra(GadgetManager.EXTRA_GADGET_IDS, gadgetIds);
            context.sendBroadcast(updateIntent);
        }
    }
    
    /**
     * Initialize given gadgets to default state, where we launch Music on default click
     * and hide actions if service not running.
     */
    static void defaultGadget(Context context, int[] gadgetIds) {
        
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.album_gadget);

        // Link up default touch to launch media player
        Intent intent = new Intent(context, MediaPlaybackActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.album_gadget, pendingIntent);

        // And hide other action buttons
        views.setViewVisibility(R.id.control_play, View.GONE);
        views.setViewVisibility(R.id.control_next, View.GONE);
        
        pushUpdate(context, gadgetIds, views);

    }
    
    private static void pushUpdate(Context context, int[] gadgetIds, RemoteViews views) {
        // Update specific list of gadgetIds if given, otherwise default to all
        GadgetManager gm = GadgetManager.getInstance(context);
        if (gadgetIds != null) {
            gm.updateGadget(gadgetIds, views);
        } else {
            ComponentName thisGadget = new ComponentName(context,
                    MediaGadgetProvider.class);
            gm.updateGadget(thisGadget, views);
        }
    }
    
    /**
     * Update all active gadget instances by pushing changes 
     * @param metaChanged
     */
    static void updateAllGadgets(MediaPlaybackService service,
            boolean metaChanged, int[] gadgetIds) {
        RemoteViews views = new RemoteViews(service.getPackageName(), R.layout.album_gadget);
        
        // Currently force metaChanged to make sure artwork is pushed to surface
        // TODO: make GadgetHostView accept partial RemoteView updates so
        // we can enable this optimization
        if (metaChanged || true) {
            int albumId = service.getAlbumId();
            Bitmap artwork = MusicUtils.getArtwork(service, albumId);
            
            // If nothing found, pull out default artwork
            if (artwork == null) {
                artwork = BitmapFactory.decodeResource(service.getResources(),
                        R.drawable.albumart_mp_unknown);
            }
            
            if (artwork != null) {
                artwork = scaleArtwork(artwork, service);
                views.setImageViewBitmap(R.id.artwork, artwork);
            }
        }
        
        boolean playing = service.isPlaying();
        views.setImageViewResource(R.id.control_play, playing ?
                android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        
        // Connect up various buttons and touch events
        Intent intent;
        PendingIntent pendingIntent;
        
        intent = new Intent(service, MusicBrowserActivity.class);
        pendingIntent = PendingIntent.getActivity(service,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.album_gadget, pendingIntent);
        
        intent = new Intent(MediaPlaybackService.TOGGLEPAUSE_ACTION);
        pendingIntent = PendingIntent.getBroadcast(service,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.control_play, pendingIntent);
        views.setViewVisibility(R.id.control_play, View.VISIBLE);
        
        intent = new Intent(MediaPlaybackService.NEXT_ACTION);
        pendingIntent = PendingIntent.getBroadcast(service,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.control_next, pendingIntent);
        views.setViewVisibility(R.id.control_next, View.VISIBLE);
        
        pushUpdate(service, gadgetIds, views);
    }
    
    private static final int ARTWORK_SIZE = 175;
    
    /**
     * Rescale given album artwork to a specific size for gadget display.
     */
    private static Bitmap scaleArtwork(Bitmap bitmap, Context context) {
        if (bitmap == null) {
            return null;
        }
        final Bitmap thumb = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE,
                Bitmap.Config.RGB_565);
        
        final Canvas canvas = new Canvas();
        canvas.setBitmap(thumb);
        
        final Paint paint = new Paint();
        paint.setDither(false);
        paint.setFilterBitmap(true);
        
        Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        Rect dest = new Rect(0, 0, thumb.getWidth(), thumb.getHeight());
        
        canvas.drawBitmap(bitmap, src, dest, paint);
        
        return thumb;
    }
    
}

