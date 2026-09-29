/*
 * SPDX-FileCopyrightText: 2026 malachite-project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.policy;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.util.Slog;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

/**
 * Detects three fingers swiping down together on the default display.
 *
 * <p>Events come from a gesture monitor that only exists while the listener is enabled, so the
 * disabled feature costs nothing. Once three fingers are down, the touch is taken from the
 * window underneath so it does not scroll while the swipe is measured.
 *
 * <p>Fingers rarely land at once: a longer finger touches first and often starts moving before
 * the others arrive. Each finger is therefore measured from where it landed, not from where it
 * was when the third finger landed.
 *
 * <p>All methods, and the callback, run on the looper passed to the constructor.
 */
final class ThreeFingerSwipeListener {
    private static final String TAG = "ThreeFingerSwipe";

    /** The third finger must land within this time of the first (HyperOS: 280 ms). */
    private static final long MAX_LANDING_SPREAD_MS = 600;
    /** Where the three fingers landed must fit in this vertical band (HyperOS: 200 dp). */
    private static final float MAX_START_SPREAD_DP = 200f;
    /** Downward travel per finger, on average, that completes the swipe (HyperOS: 50 dp). */
    private static final float SWIPE_DISTANCE_DP = 50f;
    /** Every finger must have moved at least this far down, so pinches don't count. */
    private static final float MIN_FINGER_TRAVEL_DP = 20f;

    /** Pointer ids are 0-31 (MotionEvent.MAX_POINTER_ID). */
    private static final int MAX_POINTER_IDS = 32;

    private static final int STATE_IDLE = 0;      // waiting for three fingers
    private static final int STATE_TRACKING = 1;  // three fingers down, measuring the swipe
    private static final int STATE_DONE = 2;      // fired or ruled out until every finger lifts

    private final Context mContext;
    private final Looper mLooper;
    private final Runnable mOnSwipe;
    private final float mMaxStartSpread;
    private final float mSwipeDistance;
    private final float mMinFingerTravel;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mReceiver;

    private int mState = STATE_IDLE;
    private final int[] mPointerIds = new int[3];
    /** Where and when each pointer id landed. */
    private final float[] mLandingY = new float[MAX_POINTER_IDS];
    private final long[] mLandingTime = new long[MAX_POINTER_IDS];

    ThreeFingerSwipeListener(Context context, Looper looper, Runnable onSwipe) {
        mContext = context;
        mLooper = looper;
        mOnSwipe = onSwipe;
        final float density = context.getResources().getDisplayMetrics().density;
        mMaxStartSpread = MAX_START_SPREAD_DP * density;
        // Compared against the travel of all three fingers added together.
        mSwipeDistance = SWIPE_DISTANCE_DP * density * 3;
        mMinFingerTravel = MIN_FINGER_TRAVEL_DP * density;
    }

    void setEnabled(boolean enabled) {
        if (enabled == (mInputMonitor != null)) {
            return;
        }
        if (enabled) {
            try {
                mInputMonitor = mContext.getSystemService(InputManager.class)
                        .monitorGestureInput(TAG, Display.DEFAULT_DISPLAY);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Could not monitor touches", e);
                return;
            }
            mReceiver = new Receiver(mInputMonitor.getInputChannel(), mLooper);
            Slog.i(TAG, "enabled: landing within " + MAX_LANDING_SPREAD_MS + " ms and "
                    + mMaxStartSpread + " px, swipe " + mSwipeDistance
                    + " px (three fingers together), at least " + mMinFingerTravel
                    + " px each");
        } else {
            mReceiver.dispose();
            mReceiver = null;
            mInputMonitor.dispose();
            mInputMonitor = null;
            Slog.i(TAG, "disabled");
        }
        mState = STATE_IDLE;
    }

    private void onMotionEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mState = STATE_IDLE;
                recordLanding(event);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mState = STATE_IDLE;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                recordLanding(event);
                if (event.getPointerCount() >= 2) {
                    Slog.d(TAG, "pointer down: " + event.getPointerCount() + " pointers, state "
                            + mState);
                }
                if (mState == STATE_IDLE && event.getPointerCount() == 3) {
                    startTracking(event);
                } else if (mState == STATE_TRACKING) {
                    // A fourth finger.
                    Slog.d(TAG, "ignored: a fourth finger");
                    mState = STATE_DONE;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mState == STATE_TRACKING) {
                    Slog.d(TAG, "ignored: a finger lifted before the swipe completed");
                    mState = STATE_DONE;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mState == STATE_TRACKING) {
                    track(event);
                }
                break;
        }
    }

    private void recordLanding(MotionEvent event) {
        final int index = event.getActionIndex();
        final int id = event.getPointerId(index);
        if (id >= 0 && id < MAX_POINTER_IDS) {
            mLandingY[id] = event.getY(index);
            mLandingTime[id] = event.getEventTime();
        }
    }

    private void startTracking(MotionEvent event) {
        mState = STATE_DONE;
        long firstLanding = Long.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            final int id = event.getPointerId(i);
            if (id < 0 || id >= MAX_POINTER_IDS) {
                Slog.d(TAG, "ignored: pointer id " + id);
                return;
            }
            mPointerIds[i] = id;
            firstLanding = Math.min(firstLanding, mLandingTime[id]);
            minY = Math.min(minY, mLandingY[id]);
            maxY = Math.max(maxY, mLandingY[id]);
        }
        final long landing = event.getEventTime() - firstLanding;
        if (landing > MAX_LANDING_SPREAD_MS) {
            Slog.d(TAG, "ignored: fingers landed " + landing + " ms apart");
            return;
        }
        if (maxY - minY > mMaxStartSpread) {
            Slog.d(TAG, "ignored: fingers landed " + (maxY - minY) + " px apart vertically");
            return;
        }
        mState = STATE_TRACKING;
        mInputMonitor.pilferPointers();
        Slog.d(TAG, "tracking: landed within " + landing + " ms and " + (maxY - minY) + " px");
        // The first finger may already have moved far enough.
        track(event);
    }

    private void track(MotionEvent event) {
        float travel = 0;
        float minTravel = Float.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            final int id = mPointerIds[i];
            final int index = event.findPointerIndex(id);
            if (index < 0) {
                Slog.d(TAG, "ignored: pointer " + id + " missing from the move");
                mState = STATE_DONE;
                return;
            }
            final float fingerTravel = event.getY(index) - mLandingY[id];
            travel += fingerTravel;
            minTravel = Math.min(minTravel, fingerTravel);
        }
        if (travel >= mSwipeDistance && minTravel >= mMinFingerTravel) {
            mState = STATE_DONE;
            Slog.i(TAG, "swipe: taking a screenshot (" + travel + " px, least "
                    + minTravel + " px)");
            mOnSwipe.run();
        }
    }

    private final class Receiver extends InputEventReceiver {
        Receiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent motionEvent
                        && motionEvent.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                    onMotionEvent(motionEvent);
                }
            } finally {
                finishInputEvent(event, false /* handled */);
            }
        }
    }
}
