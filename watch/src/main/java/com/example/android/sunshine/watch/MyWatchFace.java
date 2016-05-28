/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.watch;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.example.tobias.watch.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * I followed this guide https://developer.android.com/training/wearables/data-layer/index.html
 * to attain the knowledge needed to finish this project.
 *
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final String LOG_TAG = MyWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        private GoogleApiClient mGoogleApiClient;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mDigitalTimePaint;

        Paint mDatePaint;

        Paint mIconPaint;
        Bitmap mIconBitmap;
        int mIconSize;

        Paint mHighTemperaturePaint;
        String mHighTemperatureText;

        Paint mLowTemperaturePaint;
        String mLowTemperatureText;

        boolean mAmbient;
        boolean mIsRound;

        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mDigitalTimePaint = new Paint();
            mDigitalTimePaint = createTextPaint(resources.getColor(R.color.time));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.date));

            mIconPaint = new Paint();

            mHighTemperaturePaint = new Paint();
            mHighTemperaturePaint = createTextPaint(resources.getColor(R.color.high_temp));

            mLowTemperaturePaint = new Paint();
            mLowTemperaturePaint = createTextPaint(resources.getColor(R.color.low_temp));

            mTime = new Time();

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();
            Log.d(LOG_TAG, "Connected to GoogleApiClient? " + mGoogleApiClient.isConnected());
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            mIsRound = insets.isRound();
            mXOffset = resources.getDimension(mIsRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            float timeTextSize = resources.getDimension(mIsRound ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(mIsRound ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            float temperatureTextSize = resources.getDimension(mIsRound ? R.dimen.temperature_text_size_round : R.dimen.temperature_text_size);

            mDigitalTimePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mHighTemperaturePaint.setTextSize(temperatureTextSize);
            mLowTemperaturePaint.setTextSize(temperatureTextSize);

            mIconSize = mIsRound ? 45 : 40;
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mDigitalTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            float centerX = bounds.width() / 2;
            float centerY = bounds.height() / 2;

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            @SuppressLint("DefaultLocale") String time = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);

            float yOffset = 50;

            canvas.drawText(
                    time,
                    centerX - mDigitalTimePaint.measureText(time) / 2,
                    centerY - yOffset,
                    mDigitalTimePaint
            );

            Calendar calendar = Calendar.getInstance();
            @SuppressLint("SimpleDateFormat") SimpleDateFormat simpleDateFormat = new SimpleDateFormat("EEE, MMM d yyyy");
            String date = simpleDateFormat.format(calendar.getTime());
            canvas.drawText(date, centerX - mDatePaint.measureText(date) / 2, centerY, mDatePaint);


            int iconYOffset = (int) yOffset - 35;
            if (mIsRound) {
                iconYOffset = (int) yOffset - 45;
            }

            if (mIconBitmap != null) {
                canvas.drawBitmap(
                        mIconBitmap,
                        centerX - (mHighTemperaturePaint.measureText(mHighTemperatureText) + 25),
                        centerY + iconYOffset,
                        mIconPaint
                );
            }

            Log.d(LOG_TAG, "mHighTemperatureText value: " + mHighTemperatureText);
            if (mHighTemperatureText != null) {
                canvas.drawText(
                        mHighTemperatureText,
                        centerX - mHighTemperaturePaint.measureText(mHighTemperatureText) / 2,
                        centerY + yOffset,
                        mHighTemperaturePaint
                );
            }

            Log.d(LOG_TAG, "mLowTemperatureText value: " + mLowTemperatureText);
            if (mLowTemperatureText != null) {
                canvas.drawText(
                        mLowTemperatureText,
                        (centerX - mLowTemperaturePaint.measureText(mLowTemperatureText) / 2) +
                                mHighTemperaturePaint.measureText(mHighTemperatureText) + 5,
                        centerY + yOffset,
                        mLowTemperaturePaint
                );
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        /**
         * I used the following guide for this one
         * https://developer.android.com/training/displaying-bitmaps/process-bitmap.html
         */
        private class SetIconBitmap extends AsyncTask<Asset, Void, Void> {
            protected Void doInBackground(Asset... params) {
                Asset asset = params[0];
                mIconBitmap = Bitmap.createScaledBitmap(loadBitmapFromAsset(asset), mIconSize, mIconSize, false);

                return null;
            }
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(30000, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                Log.w(LOG_TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "onDataChanged called");
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED &&
                        event.getDataItem().getUri().getPath().equals("/weather-data")) {
                    DataItem dataItem = event.getDataItem();
                    DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                    Asset iconAsset = dataMap.getAsset("icon");
                    new SetIconBitmap().execute(iconAsset);

                    Log.d(LOG_TAG, "High Temperature value: " + dataMap.getString("high-temperature"));
                    mHighTemperatureText = dataMap.getString("high-temperature");

                    Log.d(LOG_TAG, "Low Temperature value: " + dataMap.getString("low-temperature"));
                    mLowTemperatureText = dataMap.getString("low-temperature");
                }
            }
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(LOG_TAG, "Connected to Google Api Service");
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "Connection to Google Api Service failed");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Connection to Google Api Service suspended");
        }
    }
}
