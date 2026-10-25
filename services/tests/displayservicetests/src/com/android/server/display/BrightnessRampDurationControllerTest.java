/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.test.filters.SmallTest;

import com.android.server.display.brightness.BrightnessReason;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class BrightnessRampDurationControllerTest {

    private static final float TOLERANCE = 0.00001f;

    private static final long DIM = 1200;
    private static final long TEMPORARY = 500;
    private static final long OVERRIDE = 800;
    private static final long HDR = 4000;
    private static final long MANUAL = 800;

    private BrightnessRampDurationController mController;
    private long mNow = 10_000;

    @Before
    public void setUp() {
        mController = new BrightnessRampDurationController(DIM, TEMPORARY, OVERRIDE, HDR, MANUAL,
                /* displayDeviceConfig= */ null);
    }

    private static BrightnessReason reason(int reason, int modifier) {
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(reason);
        brightnessReason.addModifier(modifier);
        return brightnessReason;
    }

    private static BrightnessReason manual() {
        return reason(BrightnessReason.REASON_MANUAL, 0);
    }

    private static BrightnessReason manualHdr() {
        return reason(BrightnessReason.REASON_MANUAL, BrightnessReason.MODIFIER_HDR);
    }

    // A change from current to target (SDR and HDR equal unless given), screen on, policy bright,
    // not temporary, not slow, nothing animating.
    private long update(BrightnessReason reason, float target, float current) {
        return update(POLICY_BRIGHT, reason, false, target, target, current, current);
    }

    private long update(int policy, BrightnessReason reason, boolean isTemporary,
            float target, float sdrTarget, float current, float currentSdr) {
        return mController.update(policy, reason, isTemporary, /* slowChange= */ false,
                /* hasCustomRate= */ false, target, sdrTarget, current, currentSdr,
                /* isAnimating= */ false, mNow);
    }

    @Test
    public void testInterpolate_brighteningKeepsExpLinearInTime() {
        final float start = 0.2f;
        final float target = 0.8f;
        final float half = (float) Math.log((Math.exp(start) + Math.exp(target)) / 2);

        assertEquals(start, BrightnessRampDurationController.interpolate(start, target, 0), 0);
        assertEquals(half, BrightnessRampDurationController.interpolate(start, target, 0.5f),
                TOLERANCE);
        assertEquals(target, BrightnessRampDurationController.interpolate(start, target, 1), 0);
    }

    @Test
    public void testInterpolate_darkeningIsExponential() {
        assertEquals(0.8f, BrightnessRampDurationController.interpolate(0.8f, 0.2f, 0), 0);
        // 0.8 * (0.2 / 0.8)^0.5
        assertEquals(0.4f, BrightnessRampDurationController.interpolate(0.8f, 0.2f, 0.5f),
                TOLERANCE);
        assertEquals(0.2f, BrightnessRampDurationController.interpolate(0.8f, 0.2f, 1), 0);
    }

    @Test
    public void testInterpolate_darkeningToZeroIsLinear() {
        assertEquals(0.4f, BrightnessRampDurationController.interpolate(0.8f, 0, 0.5f),
                TOLERANCE);
    }

    @Test
    public void testRampSpace_identityWithoutNitsMapping() {
        assertEquals(0.3f, mController.toRampSpace(0.3f), 0);
        assertEquals(0.3f, mController.fromRampSpace(0.3f), 0);
    }

    @Test
    public void testRampSpace_linearInNits() {
        // nits = 1200 * brightness^2
        DisplayDeviceConfig ddc = mock(DisplayDeviceConfig.class);
        when(ddc.getBacklightFromBrightness(anyFloat())).thenAnswer(i -> i.getArgument(0));
        when(ddc.getBrightnessFromBacklight(anyFloat())).thenAnswer(i -> i.getArgument(0));
        when(ddc.getNitsFromBacklight(anyFloat())).thenAnswer(i -> {
            float backlight = i.getArgument(0);
            return 1200 * backlight * backlight;
        });
        when(ddc.getBacklightFromNits(anyFloat())).thenAnswer(i -> {
            float nits = i.getArgument(0);
            return (float) Math.sqrt(nits / 1200);
        });
        mController.setDisplayDeviceConfig(ddc);

        assertEquals(0.25f, mController.toRampSpace(0.5f), TOLERANCE);
        assertEquals(0.5f, mController.fromRampSpace(0.25f), TOLERANCE);
        assertEquals(1f, mController.toRampSpace(1f), TOLERANCE);
    }

    @Test
    public void testManualChange() {
        assertEquals(MANUAL, update(manual(), 0.5f, 0.3f));
        assertEquals(0, mController.getRampReasons());
    }

    @Test
    public void testSlowChange_keepsRate() {
        final long duration = mController.update(POLICY_BRIGHT,
                reason(BrightnessReason.REASON_AUTOMATIC, 0), false, /* slowChange= */ true,
                false, 0.5f, 0.5f, 0.3f, 0.3f, false, mNow);

        assertEquals(0, duration);
    }

    @Test
    public void testCustomRate_keepsRate() {
        final long duration = mController.update(POLICY_BRIGHT, manual(), false, false,
                /* hasCustomRate= */ true, 0.5f, 0.5f, 0.3f, 0.3f, false, mNow);

        assertEquals(0, duration);
    }

    @Test
    public void testNoDurations_keepsRate() {
        mController = new BrightnessRampDurationController(0, 0, 0, 0, 0, null);

        assertEquals(0, update(manual(), 0.5f, 0.3f));
        assertEquals(0, update(POLICY_BRIGHT, manualHdr(), false, 0.9f, 0.5f, 0.5f, 0.5f));
        assertEquals(0, update(POLICY_DIM, manual(), false, 0.1f, 0.1f, 0.5f, 0.5f));
    }

    @Test
    public void testHdrEnterAndExit() {
        update(manual(), 0.3f, 0.3f);

        // HDR appears: only the HDR target moves.
        assertEquals(HDR, update(POLICY_BRIGHT, manualHdr(), false, 0.9f, 0.3f, 0.3f, 0.3f));
        assertEquals(BrightnessRampDurationController.RAMP_HDR, mController.getRampReasons());

        // Ramp still running, same targets: still the HDR duration.
        assertEquals(HDR, mController.update(POLICY_BRIGHT, manualHdr(), false, false, false,
                0.9f, 0.3f, 0.5f, 0.3f, /* isAnimating= */ true, mNow));

        mController.onAnimationEnd();
        assertEquals(0, mController.getRampReasons());

        // HDR goes away.
        assertEquals(HDR, update(POLICY_BRIGHT, manual(), false, 0.3f, 0.3f, 0.9f, 0.3f));
    }

    @Test
    public void testHdrOn_sdrChangeUsesManualDuration() {
        update(manual(), 0.3f, 0.3f);
        update(POLICY_BRIGHT, manualHdr(), false, 0.9f, 0.3f, 0.3f, 0.3f);
        mController.onAnimationEnd();

        // Brightness key while HDR stays on: SDR and HDR both move.
        assertEquals(MANUAL, update(POLICY_BRIGHT, manualHdr(), false, 1.0f, 0.4f, 0.9f, 0.3f));
    }

    @Test
    public void testHdrOn_hdrOnlyChangeUsesHdrDuration() {
        update(manual(), 0.3f, 0.3f);
        update(POLICY_BRIGHT, manualHdr(), false, 0.9f, 0.3f, 0.3f, 0.3f);
        mController.onAnimationEnd();

        assertEquals(HDR, update(POLICY_BRIGHT, manualHdr(), false, 0.7f, 0.3f, 0.9f, 0.3f));
    }

    @Test
    public void testSliderDragAndRelease() {
        final BrightnessReason temporary = reason(BrightnessReason.REASON_TEMPORARY, 0);

        assertEquals(TEMPORARY, update(POLICY_BRIGHT, temporary, true, 0.4f, 0.4f, 0.3f, 0.3f));
        mNow += 100;
        assertEquals(TEMPORARY, update(POLICY_BRIGHT, temporary, true, 0.5f, 0.5f, 0.4f, 0.4f));

        // Released 200 ms after the last move while the ramp still runs.
        mNow += 200;
        assertEquals(TEMPORARY, mController.update(POLICY_BRIGHT, manual(), false, false, false,
                0.55f, 0.55f, 0.48f, 0.48f, /* isAnimating= */ true, mNow));
    }

    @Test
    public void testSliderReleasedLate_usesManualDuration() {
        final BrightnessReason temporary = reason(BrightnessReason.REASON_TEMPORARY, 0);
        update(POLICY_BRIGHT, temporary, true, 0.4f, 0.4f, 0.3f, 0.3f);

        mNow += TEMPORARY + 1;
        assertEquals(MANUAL, mController.update(POLICY_BRIGHT, manual(), false, false, false,
                0.55f, 0.55f, 0.48f, 0.48f, /* isAnimating= */ true, mNow));
        assertEquals(0, mController.getRampReasons());
    }

    @Test
    public void testSliderWithoutDuration_jumps() {
        mController = new BrightnessRampDurationController(DIM, 0, OVERRIDE, HDR, MANUAL, null);

        assertEquals(0, update(POLICY_BRIGHT, reason(BrightnessReason.REASON_TEMPORARY, 0), true,
                0.4f, 0.4f, 0.3f, 0.3f));
    }

    @Test
    public void testSlider_winsOverHdr() {
        update(manual(), 0.3f, 0.3f);
        update(POLICY_BRIGHT, manualHdr(), false, 0.9f, 0.3f, 0.3f, 0.3f);

        assertEquals(TEMPORARY, update(POLICY_BRIGHT,
                reason(BrightnessReason.REASON_TEMPORARY, BrightnessReason.MODIFIER_HDR), true,
                1.0f, 0.4f, 0.5f, 0.3f));
        assertEquals(BrightnessRampDurationController.RAMP_TEMPORARY,
                mController.getRampReasons());
    }

    @Test
    public void testOverrideAppliedAndRemoved() {
        final BrightnessReason override = reason(BrightnessReason.REASON_OVERRIDE, 0);
        update(manual(), 0.3f, 0.3f);

        assertEquals(OVERRIDE, update(override, 0.8f, 0.3f));
        mController.onAnimationEnd();

        // Same override value again: nothing to ramp.
        update(override, 0.8f, 0.8f);
        assertEquals(0, mController.getRampReasons());

        assertEquals(OVERRIDE, update(manual(), 0.3f, 0.8f));
    }

    @Test
    public void testDimAndUndim() {
        update(manual(), 0.5f, 0.5f);

        assertEquals(DIM, update(POLICY_DIM, reason(BrightnessReason.REASON_MANUAL,
                BrightnessReason.MODIFIER_DIMMED), false, 0.1f, 0.1f, 0.5f, 0.5f));
        mController.onAnimationEnd();

        assertEquals(DIM, update(manual(), 0.5f, 0.1f));
        mController.onAnimationEnd();

        // Next change in bright: back to manual.
        assertEquals(MANUAL, update(manual(), 0.6f, 0.5f));
    }

    @Test
    public void testDoze_keepsRateAndClearsReasons() {
        update(manual(), 0.3f, 0.3f);
        update(POLICY_BRIGHT, manualHdr(), false, 0.9f, 0.3f, 0.3f, 0.3f);

        assertEquals(0, update(POLICY_DOZE, reason(BrightnessReason.REASON_DOZE, 0), false,
                0.1f, 0.1f, 0.5f, 0.3f));
        assertEquals(0, mController.getRampReasons());
    }

    @Test
    public void testScreenNotOnYet_clearsReasons() {
        update(manual(), 0.3f, 0.3f);
        update(POLICY_BRIGHT, manualHdr(), false, 0.9f, 0.3f, -1f, -1f);

        assertEquals(0, mController.getRampReasons());
    }
}
