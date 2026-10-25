/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.FloatProperty;

import androidx.test.filters.SmallTest;

import com.android.internal.display.BrightnessUtils;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class RampAnimatorTest {

    private static final float FLOAT_TOLERANCE = 0.0000001f;

    private RampAnimator<TestObject> mRampAnimator;

    private final TestObject mTestObject = new TestObject();

    private final FloatProperty<TestObject> mTestProperty = new FloatProperty<>("mValue") {
        @Override
        public void setValue(TestObject object, float value) {
            object.mValue = value;
        }

        @Override
        public Float get(TestObject object) {
            return object.mValue;
        }
    };

    @Before
    public void setUp() {
        mRampAnimator = new RampAnimator<>(mTestObject, mTestProperty, () -> 0);
    }

    @Test
    public void testInitialValueUsedInLastAnimationStep() {
        mRampAnimator.setAnimationTarget(0.67f, 0.1f, false);

        assertEquals(0.67f, mTestObject.mValue, 0);
    }

    @Test
    public void testAnimationStep_respectTimeLimits() {
        // animation is limited to 2s
        mRampAnimator.setAnimationTimeLimits(2_000, 2_000);
        // initial brightness value, applied immediately, in HLG = 0.8716434
        mRampAnimator.setAnimationTarget(0.5f, 0.1f, false);
        // expected brightness, in HLG = 0.9057269
        // delta = 0.0340835, duration = 3.40835s > 2s
        // new rate = delta/2 = 0.01704175 u/s
        mRampAnimator.setAnimationTarget(0.6f, 0.01f, false);
        // animation step = 1s, new HGL = 0.88868515
        mRampAnimator.performNextAnimationStep(1_000_000_000);
        // converted back to Linear
        assertEquals(0.54761934f, mTestObject.mValue, FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimationStep_ignoreTimeLimits() {
        // animation is limited to 2s
        mRampAnimator.setAnimationTimeLimits(2_000, 2_000);
        // initial brightness value, applied immediately, in HLG = 0.8716434
        mRampAnimator.setAnimationTarget(0.5f, 0.1f, false);
        // rate = 0.01f, time limits are ignored
        mRampAnimator.setAnimationTarget(0.6f, 0.01f, true);
        // animation step = 1s, new HGL = 0.8816434
        mRampAnimator.performNextAnimationStep(1_000_000_000);
        // converted back to Linear
        assertEquals(0.52739114f, mTestObject.mValue, FLOAT_TOLERANCE);
    }

    private static final float TIMED_TOLERANCE = 0.000001f;
    private static final long MILLIS = 1_000_000L;

    // Linear in the brightness space itself.
    private static final RampAnimator.TimedRamp LINEAR_RAMP = new RampAnimator.TimedRamp() {
        @Override
        public float toRampSpace(float linear) {
            return linear;
        }

        @Override
        public float fromRampSpace(float value) {
            return value;
        }

        @Override
        public float valueAt(float start, float target, float fraction) {
            return start + (target - start) * fraction;
        }
    };

    private long mNowNanos;

    private RampAnimator<TestObject> createTimedAnimator() {
        return new RampAnimator<>(mTestObject, mTestProperty, () -> mNowNanos);
    }

    @Test
    public void testTimed_firstTimeJumps() {
        RampAnimator<TestObject> animator = createTimedAnimator();

        assertTrue(animator.setAnimationTargetWithDuration(0.4f, 1_000, LINEAR_RAMP));

        assertEquals(0.4f, mTestObject.mValue, 0);
        assertFalse(animator.isAnimating());
    }

    @Test
    public void testTimed_zeroDurationJumps() {
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);

        animator.setAnimationTargetWithDuration(0.6f, 0, LINEAR_RAMP);

        assertEquals(0.6f, mTestObject.mValue, 0);
        assertFalse(animator.isAnimating());
    }

    @Test
    public void testTimed_reachesTargetExactlyAfterDuration() {
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);

        assertTrue(animator.setAnimationTargetWithDuration(0.6f, 1_000, LINEAR_RAMP));
        assertTrue(animator.isAnimating());

        animator.performNextAnimationStep(250 * MILLIS);
        assertEquals(0.3f, mTestObject.mValue, TIMED_TOLERANCE);
        animator.performNextAnimationStep(500 * MILLIS);
        assertEquals(0.4f, mTestObject.mValue, TIMED_TOLERANCE);
        assertTrue(animator.isAnimating());

        animator.performNextAnimationStep(1_000 * MILLIS);
        assertEquals(0.6f, mTestObject.mValue, 0);
        assertFalse(animator.isAnimating());
    }

    @Test
    public void testTimed_sameTargetKeepsRampInProgress() {
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);
        animator.setAnimationTargetWithDuration(0.6f, 1_000, LINEAR_RAMP);
        animator.performNextAnimationStep(500 * MILLIS);

        mNowNanos = 500 * MILLIS;
        assertFalse(animator.setAnimationTargetWithDuration(0.6f, 1_000, LINEAR_RAMP));

        animator.performNextAnimationStep(1_000 * MILLIS);
        assertEquals(0.6f, mTestObject.mValue, 0);
        assertFalse(animator.isAnimating());
    }

    @Test
    public void testTimed_newTargetRestartsFromCurrentValue() {
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);
        animator.setAnimationTargetWithDuration(0.6f, 1_000, LINEAR_RAMP);
        animator.performNextAnimationStep(500 * MILLIS);
        assertEquals(0.4f, mTestObject.mValue, TIMED_TOLERANCE);

        mNowNanos = 500 * MILLIS;
        assertTrue(animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP));

        animator.performNextAnimationStep(1_000 * MILLIS);
        assertEquals(0.3f, mTestObject.mValue, TIMED_TOLERANCE);
        animator.performNextAnimationStep(1_500 * MILLIS);
        assertEquals(0.2f, mTestObject.mValue, 0);
        assertFalse(animator.isAnimating());
    }

    @Test
    public void testTimed_targetEqualToCurrentValueStops() {
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);

        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);

        assertFalse(animator.isAnimating());
        assertEquals(0.2f, mTestObject.mValue, 0);
    }

    @Test
    public void testTimed_rateRampContinuesFromCurrentValue() {
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);
        animator.setAnimationTargetWithDuration(0.6f, 1_000, LINEAR_RAMP);
        animator.performNextAnimationStep(500 * MILLIS);
        final float current = mTestObject.mValue;

        mNowNanos = 500 * MILLIS;
        animator.setAnimationTarget(0.6f, 0.01f, /* ignoreAnimationLimits= */ true);
        assertTrue(animator.isAnimating());

        // 1 s at 0.01/s in HLG space from where the timed ramp was
        animator.performNextAnimationStep(1_500 * MILLIS);
        final float expected = BrightnessUtils.convertGammaToLinear(
                BrightnessUtils.convertLinearToGamma(current) + 0.01f);
        assertEquals(expected, mTestObject.mValue, TIMED_TOLERANCE);
    }

    @Test
    public void testTimed_rateZeroJumpsDuringTimedRamp() {
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, LINEAR_RAMP);
        animator.setAnimationTargetWithDuration(0.6f, 1_000, LINEAR_RAMP);
        animator.performNextAnimationStep(500 * MILLIS);

        animator.setAnimationTarget(0.8f, 0, /* ignoreAnimationLimits= */ true);

        assertEquals(0.8f, mTestObject.mValue, 0);
        assertFalse(animator.isAnimating());
    }

    @Test
    public void testTimed_curveStartsAndEndsOnValuesThatDoNotRoundTrip() {
        // fromRampSpace(toRampSpace(x)) == x + 0.01
        final RampAnimator.TimedRamp lossyRamp = new RampAnimator.TimedRamp() {
            @Override
            public float toRampSpace(float linear) {
                return linear * 2;
            }

            @Override
            public float fromRampSpace(float value) {
                return value / 2 + 0.01f;
            }

            @Override
            public float valueAt(float start, float target, float fraction) {
                return start + (target - start) * fraction;
            }
        };
        RampAnimator<TestObject> animator = createTimedAnimator();
        animator.setAnimationTargetWithDuration(0.2f, 1_000, lossyRamp);
        animator.setAnimationTargetWithDuration(0.6f, 1_000, lossyRamp);

        animator.performNextAnimationStep(0);
        assertEquals(0.2f, mTestObject.mValue, TIMED_TOLERANCE);
        animator.performNextAnimationStep(500 * MILLIS);
        assertEquals(0.4f, mTestObject.mValue, TIMED_TOLERANCE);
        animator.performNextAnimationStep(999 * MILLIS);
        assertEquals(0.5996f, mTestObject.mValue, TIMED_TOLERANCE);
    }

    private static class TestObject {
        private float mValue;
    }
}
