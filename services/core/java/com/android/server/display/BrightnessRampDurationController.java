/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.PowerManager;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;

import java.io.PrintWriter;

/**
 * Gives brightness changes that the user starts or watches a fixed duration instead of the
 * constant ramp rate: HDR content appearing or going away, the brightness slider, brightness
 * keys and settings, a window brightness override, and the dim timeout.
 *
 * <p>The durations come from config resources; 0 keeps the rate-based ramp for that case, and
 * when all of them are 0 no controller is created. Auto-brightness ramps (slow changes) keep
 * their configured rates.
 *
 * <p>Ramps run in a space linear in nits (nits / nits at full brightness), brightening so that
 * e^value is linear in time and darkening exponentially, as on stock HyperOS.
 *
 * <p>All methods run on the display power controller's handler thread.
 */
final class BrightnessRampDurationController implements RampAnimator.TimedRamp {
    private static final String TAG = "BrightnessRampDuration";

    // Why the ramp in progress has a fixed duration. Each one lasts until the ramp ends, so a
    // ramp re-planned half way (a new target) keeps its duration type.
    @VisibleForTesting
    static final int RAMP_DIM = 1 << 0;
    @VisibleForTesting
    static final int RAMP_TEMPORARY = 1 << 1;
    @VisibleForTesting
    static final int RAMP_OVERRIDE = 1 << 2;
    @VisibleForTesting
    static final int RAMP_HDR = 1 << 3;

    private static final long NO_TIME = -1;

    private final long mDimDurationMillis;
    private final long mTemporaryDurationMillis;
    private final long mOverrideDurationMillis;
    private final long mHdrDurationMillis;
    private final long mManualDurationMillis;

    @Nullable
    private DisplayDeviceConfig mDisplayDeviceConfig;
    // Nits at full brightness, or 0 when the display has no nits mapping. Then the ramp runs
    // in the brightness space itself.
    private float mMaxNits;

    private int mRampReasons;
    private long mLastTemporaryMillis = NO_TIME;
    private int mLastPolicy = -1;
    private boolean mLastHdr;
    private boolean mLastOverride;
    private float mLastTarget = Float.NaN;
    private float mLastSdrTarget = Float.NaN;
    private long mLastDurationMillis;

    /**
     * Returns a controller for the durations in the config resources, or null when all of them
     * are 0.
     */
    @Nullable
    static BrightnessRampDurationController create(Resources resources,
            @Nullable DisplayDeviceConfig displayDeviceConfig) {
        final long dim = resources.getInteger(R.integer.config_brightnessRampDimDurationMillis);
        final long temporary = resources.getInteger(
                R.integer.config_brightnessRampTemporaryDurationMillis);
        final long override = resources.getInteger(
                R.integer.config_brightnessRampOverrideDurationMillis);
        final long hdr = resources.getInteger(R.integer.config_brightnessRampHdrDurationMillis);
        final long manual = resources.getInteger(
                R.integer.config_brightnessRampManualDurationMillis);
        if (dim <= 0 && temporary <= 0 && override <= 0 && hdr <= 0 && manual <= 0) {
            return null;
        }
        return new BrightnessRampDurationController(dim, temporary, override, hdr, manual,
                displayDeviceConfig);
    }

    @VisibleForTesting
    BrightnessRampDurationController(long dimDurationMillis, long temporaryDurationMillis,
            long overrideDurationMillis, long hdrDurationMillis, long manualDurationMillis,
            @Nullable DisplayDeviceConfig displayDeviceConfig) {
        mDimDurationMillis = Math.max(0, dimDurationMillis);
        mTemporaryDurationMillis = Math.max(0, temporaryDurationMillis);
        mOverrideDurationMillis = Math.max(0, overrideDurationMillis);
        mHdrDurationMillis = Math.max(0, hdrDurationMillis);
        mManualDurationMillis = Math.max(0, manualDurationMillis);
        setDisplayDeviceConfig(displayDeviceConfig);
    }

    /** Sets the display config whose nits mapping defines the ramp space. */
    void setDisplayDeviceConfig(@Nullable DisplayDeviceConfig displayDeviceConfig) {
        mDisplayDeviceConfig = displayDeviceConfig;
        mMaxNits = 0;
        if (displayDeviceConfig != null) {
            final float maxNits = brightnessToNits(PowerManager.BRIGHTNESS_MAX);
            if (maxNits > 0 && !Float.isNaN(maxNits) && !Float.isInfinite(maxNits)) {
                mMaxNits = maxNits;
            }
        }
    }

    /**
     * Updates the state with the brightness request of this power state update and returns
     * the duration of the ramp to the new targets, or 0 to use the rate-based ramp.
     *
     * @param policy The display power request policy.
     * @param reason Why the brightness is what it is, with the HDR modifier when HDR content
     *               raises the brightness.
     * @param isTemporary Whether the brightness is a temporary one (slider being dragged).
     * @param slowChange Whether the change is a slow (auto-brightness) one.
     * @param hasCustomRate Whether a custom animation rate was requested for this change.
     * @param target The target brightness, including any HDR boost.
     * @param sdrTarget The target brightness of SDR content.
     * @param current The brightness now.
     * @param currentSdr The SDR brightness now.
     * @param isAnimating Whether a brightness ramp is in progress.
     * @param nowMillis The current uptime.
     */
    long update(int policy, BrightnessReason reason, boolean isTemporary, boolean slowChange,
            boolean hasCustomRate, float target, float sdrTarget, float current,
            float currentSdr, boolean isAnimating, long nowMillis) {
        final boolean bright = policy == POLICY_BRIGHT;
        final boolean dim = policy == POLICY_DIM;
        final boolean hdr = (reason.getModifier() & BrightnessReason.MODIFIER_HDR) != 0;
        final boolean override = reason.getReason() == BrightnessReason.REASON_OVERRIDE;
        final boolean targetChanged = Float.compare(target, mLastTarget) != 0;
        final boolean sdrTargetChanged = Float.compare(sdrTarget, mLastSdrTarget) != 0;
        final boolean wasDim = mLastPolicy == POLICY_DIM;

        if (bright) {
            // HDR content appeared or went away, or only the HDR level moved.
            if (mHdrDurationMillis > 0 && (hdr != mLastHdr
                    || (hdr && targetChanged && !sdrTargetChanged))) {
                mRampReasons |= RAMP_HDR;
            }
            // A window override was applied or removed, or its value changed.
            if (mOverrideDurationMillis > 0 && (override != mLastOverride
                    || (override && sdrTargetChanged))) {
                mRampReasons |= RAMP_OVERRIDE;
            }
            // The slider is being dragged, or was released while its ramp still runs.
            if (isTemporary || (mRampReasons & RAMP_TEMPORARY) != 0) {
                mRampReasons &= ~(RAMP_HDR | RAMP_OVERRIDE);
                if (mTemporaryDurationMillis > 0 && isTemporary) {
                    mLastTemporaryMillis = nowMillis;
                    mRampReasons |= RAMP_TEMPORARY;
                } else if (mTemporaryDurationMillis <= 0 || mLastTemporaryMillis == NO_TIME
                        || nowMillis - mLastTemporaryMillis > mTemporaryDurationMillis) {
                    mRampReasons &= ~RAMP_TEMPORARY;
                    mLastTemporaryMillis = NO_TIME;
                }
            }
        }
        if (dim || (bright && wasDim)) {
            // Into dim, or back out of it.
            mRampReasons = mDimDurationMillis > 0 ? RAMP_DIM : 0;
        } else {
            mRampReasons &= ~RAMP_DIM;
        }
        // Doze, off, or a brightness that is not on the screen yet: the rate-based ramp, or
        // the jump, decides.
        if ((!bright && !dim) || !BrightnessUtils.isValidBrightnessValue(current)
                || !BrightnessUtils.isValidBrightnessValue(currentSdr)) {
            mRampReasons = 0;
        }

        mLastPolicy = policy;
        mLastHdr = hdr;
        mLastOverride = override;
        mLastTarget = target;
        mLastSdrTarget = sdrTarget;

        final boolean changed = target != current || sdrTarget != currentSdr;
        if (!changed && !isAnimating) {
            // Nothing to ramp: nothing carries over to a later change.
            mRampReasons = 0;
        }

        final long duration = selectDuration(bright, isTemporary, slowChange, hasCustomRate);
        if (changed && duration != mLastDurationMillis) {
            Slog.i(TAG, "Ramp " + rampReasonsToString(mRampReasons, isTemporary, duration)
                    + " to " + target + " (sdr " + sdrTarget + ")");
        }
        if (changed) {
            mLastDurationMillis = duration;
        }
        return duration;
    }

    private long selectDuration(boolean bright, boolean isTemporary, boolean slowChange,
            boolean hasCustomRate) {
        if ((mRampReasons & RAMP_DIM) != 0) {
            return mDimDurationMillis;
        }
        if ((mRampReasons & RAMP_TEMPORARY) != 0) {
            return mTemporaryDurationMillis;
        }
        if (isTemporary) {
            // Slider without a duration of its own: it jumps, as in AOSP.
            return 0;
        }
        if ((mRampReasons & RAMP_OVERRIDE) != 0) {
            return mOverrideDurationMillis;
        }
        if ((mRampReasons & RAMP_HDR) != 0) {
            return mHdrDurationMillis;
        }
        if (bright && !slowChange && !hasCustomRate) {
            // Keys, quick settings, settings, slider release after its ramp ended.
            return mManualDurationMillis;
        }
        return 0;
    }

    /** Called when the brightness ramp ends: every duration type ends with its ramp. */
    void onAnimationEnd() {
        mRampReasons = 0;
    }

    @VisibleForTesting
    int getRampReasons() {
        return mRampReasons;
    }

    @Override
    public float toRampSpace(float brightness) {
        if (mMaxNits <= 0) {
            return brightness;
        }
        return brightnessToNits(brightness) / mMaxNits;
    }

    @Override
    public float fromRampSpace(float value) {
        if (mMaxNits <= 0) {
            return value;
        }
        final DisplayDeviceConfig ddc = mDisplayDeviceConfig;
        return ddc.getBrightnessFromBacklight(ddc.getBacklightFromNits(value * mMaxNits));
    }

    @Override
    public float valueAt(float start, float target, float fraction) {
        return interpolate(start, target, fraction);
    }

    /**
     * Returns the ramp value at {@code fraction} (0..1) of the way from start to target, both
     * in ramp space: brightening keeps e^value linear in time, darkening keeps value / start
     * exponential in time (constant ratio per frame). Same curves as stock HyperOS.
     */
    @VisibleForTesting
    static float interpolate(float start, float target, float fraction) {
        if (fraction <= 0) {
            return start;
        }
        if (fraction >= 1 || start == target) {
            return target;
        }
        if (target > start) {
            final double eStart = Math.exp(start);
            return (float) Math.log(eStart + (Math.exp(target) - eStart) * fraction);
        }
        if (start <= 0 || target <= 0) {
            return start + (target - start) * fraction;
        }
        return (float) (start * Math.pow(target / (double) start, fraction));
    }

    private float brightnessToNits(float brightness) {
        final DisplayDeviceConfig ddc = mDisplayDeviceConfig;
        return ddc.getNitsFromBacklight(ddc.getBacklightFromBrightness(brightness));
    }

    private static String rampReasonsToString(int reasons, boolean isTemporary, long duration) {
        final String name;
        if ((reasons & RAMP_DIM) != 0) {
            name = "dim";
        } else if ((reasons & RAMP_TEMPORARY) != 0) {
            name = "slider";
        } else if (isTemporary) {
            name = "slider (jump)";
        } else if ((reasons & RAMP_OVERRIDE) != 0) {
            name = "override";
        } else if ((reasons & RAMP_HDR) != 0) {
            name = "hdr";
        } else if (duration > 0) {
            name = "manual";
        } else {
            name = "rate";
        }
        return duration > 0 ? name + " " + duration + " ms" : name;
    }

    void dump(PrintWriter pw) {
        pw.println("BrightnessRampDurationController:");
        pw.println("  mDimDurationMillis=" + mDimDurationMillis);
        pw.println("  mTemporaryDurationMillis=" + mTemporaryDurationMillis);
        pw.println("  mOverrideDurationMillis=" + mOverrideDurationMillis);
        pw.println("  mHdrDurationMillis=" + mHdrDurationMillis);
        pw.println("  mManualDurationMillis=" + mManualDurationMillis);
        pw.println("  mMaxNits=" + mMaxNits);
        pw.println("  mRampReasons=0x" + Integer.toHexString(mRampReasons));
        pw.println("  mLastDurationMillis=" + mLastDurationMillis);
    }
}
