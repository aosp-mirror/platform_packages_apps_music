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
    static void defaultGadget(Context context, int[] gadgetIds) {
        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.gadget);

        linkButtons(context, views, false /* not from service */);
        pushUpdate(context, gadgetIds, views);
    }
    
    private static void pushUpdate(Context context, int[] gadgetIds, RemoteViews views) {
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
     * Update all active gadget instances by pushing changes 
     * @param metaChanged
     */
    static void updateAllGadgets(MediaPlaybackService service, int[] gadgetIds) {
        final Resources res = service.getResources();
        final RemoteViews views = new RemoteViews(service.getPackageName(), R.layout.gadget);
        
        final int track = service.getQueuePosition() + 1;
        final String titleName = service.getTrackName();
        final String artistName = service.getArtistName();
        final String albumName = service.getAlbumName();
        
        int albumId = service.getAlbumId();
        if (MediaFile.UNKNOWN_STRING.equals(albumName)) {
            albumId = -1;
        }
        
        // Try loading album artwork and resize if found
        Bitmap artwork = MusicUtils.getArtwork(service, albumId, false);
        if (artwork != null) {
            artwork = scaleArtwork(artwork, service);
            views.setImageViewBitmap(R.id.artwork, artwork);
            views.setViewVisibility(R.id.artwork, View.VISIBLE);
            views.setViewVisibility(R.id.no_artwork, View.GONE);
        } else {
            views.setViewVisibility(R.id.artwork, View.GONE);
            views.setViewVisibility(R.id.no_artwork, View.VISIBLE);
        }
        
        // Format title string with track number
        final String titleString = res.getString(R.string.gadget_track_num_title, track, titleName);
        
        views.setTextViewText(R.id.title, titleString);
        views.setTextViewText(R.id.artist, artistName);
        
        // Set chronometer to correct value
        final boolean playing = service.isPlaying();
        final long start = SystemClock.elapsedRealtime() - service.position();
        final long end = start + service.duration();
        
        views.setChronometer(android.R.id.text1, start, null, playing);
        views.setLong(R.id.progress_group, "setDurationBase", end);

        // Set correct drawable for pause state
        views.setImageViewResource(R.id.control_play, playing ?
                R.drawable.gadget_pause : R.drawable.gadget_play);

        // Link actions buttons to intents
        linkButtons(service, views, true /* not from service */);
        
        pushUpdate(service, gadgetIds, views);
    }

    /**
     * Link up various button actions using {@link PendingIntents}.
     * 
     * @param fromService If false, {@link MediaPlaybackService} isn't running,
     *            and we should link the play button to start that service.
     *            Otherwise, we assume the service is awake and send broadcast
     *            Intents instead.
     */
    private static void linkButtons(Context context, RemoteViews views, boolean fromService) {
        // Connect up various buttons and touch events
        Intent intent;
        PendingIntent pendingIntent;
        
        intent = new Intent(context, MediaPlaybackActivity.class);
        pendingIntent = PendingIntent.getActivity(context,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.album_gadget, pendingIntent);
        
        intent = new Intent(MediaPlaybackService.PREVIOUS_ACTION);
        pendingIntent = PendingIntent.getBroadcast(context,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.control_prev, pendingIntent);

        // Use broadcast to trigger play/pause, otherwise 
        if (fromService) {
            intent = new Intent(MediaPlaybackService.TOGGLEPAUSE_ACTION);
            pendingIntent = PendingIntent.getBroadcast(context,
                    0 /* no requestCode */, intent, 0 /* no flags */);
            views.setOnClickPendingIntent(R.id.control_play, pendingIntent);
        } else {
            intent = new Intent(MediaPlaybackService.TOGGLEPAUSE_ACTION);
            intent.setComponent(new ComponentName(context, MediaPlaybackService.class));
            pendingIntent = PendingIntent.getService(context,
                    0 /* no requestCode */, intent, 0 /* no flags */);
            views.setOnClickPendingIntent(R.id.control_play, pendingIntent);
        }
        
        intent = new Intent(MediaPlaybackService.NEXT_ACTION);
        pendingIntent = PendingIntent.getBroadcast(context,
                0 /* no requestCode */, intent, 0 /* no flags */);
        views.setOnClickPendingIntent(R.id.control_next, pendingIntent);
    }
    
    /**
     * Scale and chop given album artwork to prepare as gadget background.
     */
    private static Bitmap scaleArtwork(Bitmap bitmap, Context context) {
        final int cutoutSize = (int) context.getResources().getDimension(R.dimen.gadget_cutout);
        final int srcWidth = bitmap.getWidth();
        final int srcHeight = bitmap.getHeight();
        final int srcDiameter = Math.min(srcWidth, srcHeight);
        
        // Figure out best circle size
        final Rect src = new Rect((srcWidth - srcDiameter) / 2,
                (srcHeight - srcDiameter) / 2, srcDiameter, srcDiameter);
        final Rect dest = new Rect(0, 0, cutoutSize, cutoutSize);
        
        final Bitmap thumb = Bitmap.createBitmap(cutoutSize, cutoutSize,
                Bitmap.Config.ARGB_8888);

        final Canvas canvas = new Canvas(thumb);
        final Paint paint = new Paint();
        
        paint.setAntiAlias(true);
        
        // Draw a mask circle using default paint
        final int radius = cutoutSize / 2;
        canvas.drawCircle(radius, radius, radius, paint);
        
        paint.setDither(false);
        paint.setFilterBitmap(true);
        paint.setAlpha(96);
        
        // Draw the actual album art, using the mask circle from earlier. Using
        // this approach allows us to alpha-blend the circle edges, which isn't
        // possible with Canvas.clipPath()
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, src, dest, paint);
        
        return thumb;
    }
    
}
