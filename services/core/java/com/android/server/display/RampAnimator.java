/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import static com.android.server.display.BrightnessMappingStrategy.INVALID_LUX;

import android.animation.ValueAnimator;
import android.util.FloatProperty;
import android.util.MathUtils;
import android.util.Spline;
import android.view.Choreographer;
import android.view.Choreographer.VsyncCallback;

import androidx.annotation.Nullable;

import com.android.internal.display.BrightnessUtils;

/**
 * A custom animator that progressively updates a property value at
 * a given variable rate until it reaches a particular target value.
 * The ramping at the given rate is done in the perceptual space using
 * the HLG transfer functions.
 *
 * In timed mode ({@link #setAnimationTargetWithDuration}) the value instead follows a curve
 * from where it is to the target over a fixed duration, in the space and with the curve of a
 * {@link TimedRamp}.
 */
class RampAnimator<T> {
    private final T mObject;
    private final FloatProperty<T> mProperty;

    private final Clock mClock;

    // current brightness in HLG space
    private float mCurrentValue;

    // target in HLG space
    private float mTargetHlgValue;

    // target in linear space
    private float mTargetLinearValue;
    private float mRate;
    private float mAnimationIncreaseMaxTimeSecs;
    private float mAnimationDecreaseMaxTimeSecs;

    // Given a lux change, defines the max brightness animation duration
    @Nullable
    private Spline mLuxDeltaToRampIncreaseMaxMillis;
    @Nullable
    private Spline mLuxDeltaToRampDecreaseMaxMillis;

    // Values used to tune the degree of curvature of the brightness transition
    private float mBrighteningRampGamma = Float.NaN;
    private float mDarkeningRampGamma = Float.NaN;
    private float mCurrentRampGamma = Float.NaN;

    private boolean mAnimating;
    private float mAnimatedValue; // higher precision copy of mCurrentValue
    private long mLastFrameTimeNanos;

    private boolean mFirstTime = true;

    // linear value last given to the property
    private float mCurrentLinearValue;

    // timed mode, see setAnimationTargetWithDuration()
    private boolean mTimed;
    private TimedRamp mTimedRamp;
    private float mTimedStartRampValue;
    private float mTimedTargetRampValue;
    private float mTimedStartError;
    private float mTimedTargetError;
    private long mTimedStartTimeNanos;
    private long mTimedDurationNanos;

    RampAnimator(T object, FloatProperty<T> property) {
        this(object, property, System::nanoTime);
    }

    RampAnimator(T object, FloatProperty<T> property, Clock clock) {
        mObject = object;
        mProperty = property;
        mClock = clock;
    }

    /**
     * Sets the maximum time that a brightness animation can take.
     */
    void setAnimationTimeLimits(long animationRampIncreaseMaxTimeMillis,
            long animationRampDecreaseMaxTimeMillis, Spline luxDeltaToRampIncreaseMaxMillis,
            Spline luxDeltaToRampDecreaseMaxMillis) {
        mAnimationIncreaseMaxTimeSecs = (animationRampIncreaseMaxTimeMillis > 0)
                ? (animationRampIncreaseMaxTimeMillis / 1000.0f) : 0.0f;
        mAnimationDecreaseMaxTimeSecs = (animationRampDecreaseMaxTimeMillis > 0)
                ? (animationRampDecreaseMaxTimeMillis / 1000.0f) : 0.0f;
        mLuxDeltaToRampIncreaseMaxMillis = luxDeltaToRampIncreaseMaxMillis;
        mLuxDeltaToRampDecreaseMaxMillis = luxDeltaToRampDecreaseMaxMillis;
    }

    /**
     * Sets the gammas for the brightening and darkening ramp animations.
     */
    void setRampGammaValues(float brighteningRampGamma, float darkeningRampGamma) {
        mBrighteningRampGamma = brighteningRampGamma;
        mDarkeningRampGamma = darkeningRampGamma;
    }

    /**
     * Sets the animation target and the rate of this ramp animator.
     * Animation rate will be set ignoring maxTime animation limits
     * If this is the first time the property is being set or if the rate is 0,
     * the value jumps directly to the target.
     *
     * @param targetLinear The target value.
     * @param rate The convergence rate in units per second, or 0 to set the value immediately.
     * @param ignoreAnimationLimits if mAnimationIncreaseMaxTimeSecs and
     *                              mAnimationDecreaseMaxTimeSecs should be respected when adjusting
     *                              animation speed
     * @param luxDelta The change in ambient light that triggered this animation.
     * @return True if the target differs from the previous target.
     */
    boolean setAnimationTarget(float targetLinear, float rate, boolean ignoreAnimationLimits,
            float luxDelta) {
        float maxIncreaseTimeSecs = 0;
        float maxDecreaseTimeSecs = 0;
        if (!ignoreAnimationLimits) {
            if (mLuxDeltaToRampIncreaseMaxMillis != null && mLuxDeltaToRampDecreaseMaxMillis != null
                    && luxDelta != INVALID_LUX) {
                maxIncreaseTimeSecs = mLuxDeltaToRampIncreaseMaxMillis.interpolate(luxDelta)
                        / 1000.0f;
                maxDecreaseTimeSecs = mLuxDeltaToRampDecreaseMaxMillis.interpolate(luxDelta)
                        / 1000.0f;
            } else {
                maxIncreaseTimeSecs = mAnimationIncreaseMaxTimeSecs;
                maxDecreaseTimeSecs = mAnimationDecreaseMaxTimeSecs;
            }
        }
        return setAnimationTarget(targetLinear, rate, maxIncreaseTimeSecs, maxDecreaseTimeSecs);
    }

    private boolean setAnimationTarget(float targetLinear, float rate,
            float maxIncreaseTimeSecs, float maxDecreaseTimeSecs) {
        mCurrentRampGamma = targetLinear > BrightnessUtils.convertGammaToLinear(mCurrentValue)
                ? mBrighteningRampGamma : mDarkeningRampGamma;
        final float target;
        // Convert the target from the linear into the HLG space.
        if (!Float.isNaN(mCurrentRampGamma)) {
            target = BrightnessUtils.convertLinearToGamma(
                    (float) Math.pow(targetLinear, 1.0f / mCurrentRampGamma));
        } else {
            mCurrentRampGamma = 1.0f;
            target = BrightnessUtils.convertLinearToGamma(targetLinear);
        }

        if (mTimed) {
            // Leave the timed ramp: continue from where it is now at the given rate.
            mTimed = false;
            mAnimating = false;
        }

        // Immediately jump to the target the first time.
        if (mFirstTime || rate <= 0 || mCurrentValue == 0) {
            if (mFirstTime || target != mCurrentValue) {
                mFirstTime = false;
                mRate = 0;
                mTargetHlgValue = target;
                mTargetLinearValue = targetLinear;
                mCurrentValue = target;
                setPropertyValue(target);
                mAnimating = false;
                return true;
            }
            return false;
        }

        // Adjust the rate so that we do not exceed our maximum animation time.
        if (target > mCurrentValue && maxIncreaseTimeSecs > 0.0f
                && ((target - mCurrentValue) / rate) > maxIncreaseTimeSecs) {
            rate = (target - mCurrentValue) / maxIncreaseTimeSecs;
        } else if (target < mCurrentValue && maxDecreaseTimeSecs > 0.0f
                && ((mCurrentValue - target) / rate) > maxDecreaseTimeSecs) {
            rate = (mCurrentValue - target) / maxDecreaseTimeSecs;
        }

        // Adjust the rate based on the closest target.
        // If a faster rate is specified, then use the new rate so that we converge
        // more rapidly based on the new request.
        // If a slower rate is specified, then use the new rate only if the current
        // value is somewhere in between the new and the old target meaning that
        // we will be ramping in a different direction to get there.
        // Otherwise, continue at the previous rate.
        if (!mAnimating
                || rate > mRate
                || (target <= mCurrentValue && mCurrentValue <= mTargetHlgValue)
                || (mTargetHlgValue <= mCurrentValue && mCurrentValue <= target)) {
            mRate = rate;
        }

        final boolean changed = (mTargetHlgValue != target);
        mTargetHlgValue = target;
        mTargetLinearValue = targetLinear;

        // Start animating.
        if (!mAnimating && target != mCurrentValue) {
            mAnimating = true;
            mAnimatedValue = mCurrentValue;
            mLastFrameTimeNanos = mClock.nanoTime();
        }

        return changed;
    }

    /**
     * Sets the animation target and ramps to it in the given time, following the curve of
     * {@code timedRamp} from the current value. A new target restarts the full duration from
     * where the value is; the same target again keeps the ramp in progress.
     * If this is the first time the property is being set or if the duration is 0,
     * the value jumps directly to the target.
     *
     * @param targetLinear The target value.
     * @param durationMillis The ramp duration, scaled by the animator duration scale.
     * @param timedRamp The ramp space and curve.
     * @return True if the target differs from the previous target.
     */
    boolean setAnimationTargetWithDuration(float targetLinear, long durationMillis,
            TimedRamp timedRamp) {
        if (mFirstTime || durationMillis <= 0) {
            return setAnimationTarget(targetLinear, /* rate= */ 0,
                    /* ignoreAnimationLimits= */ true);
        }
        if (mTimed && mAnimating && targetLinear == mTargetLinearValue) {
            return false;
        }

        final float target = BrightnessUtils.convertLinearToGamma(targetLinear);
        final boolean changed = (mTargetHlgValue != target);
        mTargetHlgValue = target;
        mTargetLinearValue = targetLinear;

        if (targetLinear == mCurrentLinearValue) {
            // Already there.
            mTimed = false;
            mAnimating = false;
            mCurrentValue = target;
            return changed;
        }

        // The errors make the curve start and end exactly on the linear values even when
        // the ramp space conversion does not round-trip exactly.
        mTimed = true;
        mTimedRamp = timedRamp;
        mTimedStartRampValue = timedRamp.toRampSpace(mCurrentLinearValue);
        mTimedTargetRampValue = timedRamp.toRampSpace(targetLinear);
        mTimedStartError = mCurrentLinearValue - timedRamp.fromRampSpace(mTimedStartRampValue);
        mTimedTargetError = targetLinear - timedRamp.fromRampSpace(mTimedTargetRampValue);
        mTimedStartTimeNanos = mClock.nanoTime();
        mTimedDurationNanos = durationMillis * 1_000_000L;
        mAnimating = true;
        return changed;
    }

    /**
     * Returns true if the animation is running.
     */
    boolean isAnimating() {
        return mAnimating;
    }

    /**
     * Sets the brightness property by converting the given value from HLG space
     * into linear space.
     */
    private void setPropertyValue(float val) {
        float newValue = BrightnessUtils.convertGammaToLinear(val);
        if (!Float.isNaN(mCurrentRampGamma)) {
            newValue = (float) Math.pow(newValue, mCurrentRampGamma);
        }

        // To avoid linearVal inconsistency when converting to HLG and back to linear space
        // used original target linear value for final animation step
        float linearVal = val == mTargetHlgValue ? mTargetLinearValue : newValue;
        mCurrentLinearValue = linearVal;
        mProperty.setValue(mObject, linearVal);
    }

    void performNextAnimationStep(long frameTimeNanos) {
        if (mTimed) {
            performNextTimedStep(frameTimeNanos);
            return;
        }
        final float timeDelta = (frameTimeNanos - mLastFrameTimeNanos) * 0.000000001f;
        mLastFrameTimeNanos = frameTimeNanos;

        // Advance the animated value towards the target at the specified rate
        // and clamp to the target. This gives us the new current value but
        // we keep the animated value around to allow for fractional increments
        // towards the target.
        final float scale = ValueAnimator.getDurationScale();
        if (scale == 0) {
            // Animation off.
            mAnimatedValue = mTargetHlgValue;
        } else {
            final float amount = timeDelta * mRate / scale;
            if (mTargetHlgValue > mCurrentValue) {
                mAnimatedValue = Math.min(mAnimatedValue + amount, mTargetHlgValue);
            } else {
                mAnimatedValue = Math.max(mAnimatedValue - amount, mTargetHlgValue);
            }
        }
        final float oldCurrentValue = mCurrentValue;
        mCurrentValue = mAnimatedValue;
        if (oldCurrentValue != mCurrentValue) {
            setPropertyValue(mCurrentValue);
        }
        if (mTargetHlgValue == mCurrentValue) {
            mAnimating = false;
        }
    }

    private void performNextTimedStep(long frameTimeNanos) {
        final float scale = ValueAnimator.getDurationScale();
        final float fraction;
        if (scale == 0) {
            // Animation off.
            fraction = 1;
        } else {
            fraction = MathUtils.constrain((frameTimeNanos - mTimedStartTimeNanos)
                    / (mTimedDurationNanos * scale), 0, 1);
        }

        final float linearVal;
        if (fraction >= 1) {
            linearVal = mTargetLinearValue;
            mCurrentValue = mTargetHlgValue;
            mTimed = false;
            mAnimating = false;
        } else {
            final float rampVal = mTimedRamp.valueAt(mTimedStartRampValue,
                    mTimedTargetRampValue, fraction);
            linearVal = mTimedRamp.fromRampSpace(rampVal)
                    + (1 - fraction) * mTimedStartError + fraction * mTimedTargetError;
            mCurrentValue = BrightnessUtils.convertLinearToGamma(linearVal);
        }
        // Keep the HLG copy in step so a rate-based ramp can take over from here.
        mAnimatedValue = mCurrentValue;
        mLastFrameTimeNanos = frameTimeNanos;
        if (linearVal != mCurrentLinearValue) {
            mCurrentLinearValue = linearVal;
            mProperty.setValue(mObject, linearVal);
        }
    }

    public interface Listener {
        void onAnimationEnd();
    }

    /** The space and curve of a timed ramp. */
    interface TimedRamp {
        /** Converts a linear brightness value into the ramp space. */
        float toRampSpace(float linear);

        /** Converts a ramp space value back into a linear brightness value. */
        float fromRampSpace(float value);

        /**
         * Returns the ramp space value at {@code fraction} (0..1) of the way from start to
         * target.
         */
        float valueAt(float start, float target, float fraction);
    }

    interface Clock {
        /**
         * Returns current system time in nanoseconds.
         */
        long nanoTime();
    }

    static class DualRampAnimator<T> {
        private final Choreographer mChoreographer;
        private final RampAnimator<T> mFirst;
        private final RampAnimator<T> mSecond;

        private Listener mListener;
        private boolean mAwaitingCallback;

        DualRampAnimator(T object, FloatProperty<T> firstProperty,
                FloatProperty<T> secondProperty) {
            mChoreographer = Choreographer.getInstance();
            mFirst = new RampAnimator<>(object, firstProperty);
            mSecond = new RampAnimator<>(object, secondProperty);
        }

        /**
         * Sets the maximum time that a brightness animation can take.
         */
        public void setAnimationTimeLimits(long animationRampIncreaseMaxTimeMillis,
                long animationRampDecreaseMaxTimeMillis, Spline luxDeltaToRampIncreaseMaxMillis,
                Spline luxDeltaToRampDecreaseMaxMillis) {
            mFirst.setAnimationTimeLimits(animationRampIncreaseMaxTimeMillis,
                    animationRampDecreaseMaxTimeMillis, luxDeltaToRampIncreaseMaxMillis,
                    luxDeltaToRampDecreaseMaxMillis);
            mSecond.setAnimationTimeLimits(animationRampIncreaseMaxTimeMillis,
                    animationRampDecreaseMaxTimeMillis, luxDeltaToRampIncreaseMaxMillis,
                    luxDeltaToRampDecreaseMaxMillis);
        }

        /**
         * Sets the gammas for the brightening and darkening ramp animations.
         */
        public void setRampGammaValues(float brighteningRampGamma, float darkeningRampGamma) {
            mFirst.setRampGammaValues(brighteningRampGamma, darkeningRampGamma);
            mSecond.setRampGammaValues(brighteningRampGamma, darkeningRampGamma);
        }

        /**
         * Starts animating towards the specified values.
         *
         * If this is the first time the property is being set or if the rate is 0,
         * the value jumps directly to the target.
         *
         * @param linearFirstTarget The first target value in linear space.
         * @param linearSecondTarget The second target value in linear space.
         * @param rate The convergence rate in units per second, or 0 to set the value immediately.
         * @param ignoreAnimationLimits if mAnimationIncreaseMaxTimeSecs and
         *                              mAnimationDecreaseMaxTimeSecs should be respected
         *                              when adjusting animation speed
         * @param luxDelta The change in ambient light that triggered this animation.
         * @return True if either target differs from the previous target.
         */
        public boolean animateTo(float linearFirstTarget, float linearSecondTarget, float rate,
                boolean ignoreAnimationLimits, float luxDelta) {
            boolean animationTargetChanged = mFirst.setAnimationTarget(linearFirstTarget, rate,
                    ignoreAnimationLimits, luxDelta);
            animationTargetChanged |= mSecond.setAnimationTarget(linearSecondTarget, rate,
                    ignoreAnimationLimits, luxDelta);
            boolean shouldBeAnimating = isAnimating();

            if (shouldBeAnimating != mAwaitingCallback) {
                if (shouldBeAnimating) {
                    mAwaitingCallback = true;
                    postAnimationCallback();
                } else if (mAwaitingCallback) {
                    mChoreographer.removeVsyncCallback(mAnimationCallback);
                    mAwaitingCallback = false;
                }
            }
            return animationTargetChanged;
        }

        /**
         * Starts animating towards the specified values in the given time, see
         * {@link RampAnimator#setAnimationTargetWithDuration}.
         *
         * @param linearFirstTarget The first target value in linear space.
         * @param linearSecondTarget The second target value in linear space.
         * @param durationMillis The ramp duration, or 0 to set the values immediately.
         * @param timedRamp The ramp space and curve.
         * @return True if either target differs from the previous target.
         */
        public boolean animateToWithDuration(float linearFirstTarget, float linearSecondTarget,
                long durationMillis, TimedRamp timedRamp) {
            boolean animationTargetChanged = mFirst.setAnimationTargetWithDuration(
                    linearFirstTarget, durationMillis, timedRamp);
            animationTargetChanged |= mSecond.setAnimationTargetWithDuration(
                    linearSecondTarget, durationMillis, timedRamp);
            boolean shouldBeAnimating = isAnimating();

            if (shouldBeAnimating != mAwaitingCallback) {
                if (shouldBeAnimating) {
                    mAwaitingCallback = true;
                    postAnimationCallback();
                } else if (mAwaitingCallback) {
                    mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION,
                            mAnimationCallback, null);
                    mAwaitingCallback = false;
                }
            }
            return animationTargetChanged;
        }

        /**
        * Sets a listener to watch for animation events.
        */
        public void setListener(Listener listener) {
            mListener = listener;
        }

        /**
        * Returns true if the animation is running.
        */
        public boolean isAnimating() {
            return mFirst.isAnimating() || mSecond.isAnimating();
        }

        private void postAnimationCallback() {
            mChoreographer.postVsyncCallback(mAnimationCallback);
        }

        private final VsyncCallback mAnimationCallback = new VsyncCallback() {
            @Override // Choreographer callback
            public void onVsync(Choreographer.FrameData frameData) {
                long frameTimeNanos = frameData.getFrameTimeNanos();
                mFirst.performNextAnimationStep(frameTimeNanos);
                mSecond.performNextAnimationStep(frameTimeNanos);
                if (isAnimating()) {
                    postAnimationCallback();
                } else {
                    if (mListener != null) {
                        mListener.onAnimationEnd();
                    }
                    mAwaitingCallback = false;
                }
            }
        };
    }
}
