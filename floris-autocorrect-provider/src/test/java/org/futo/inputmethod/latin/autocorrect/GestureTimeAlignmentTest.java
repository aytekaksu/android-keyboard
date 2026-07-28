/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.Collections;
import org.florisboard.autocorrect.api.AutocorrectGesturePoint;
import org.junit.Test;

public class GestureTimeAlignmentTest {
    @Test
    public void preservesAlreadyMonotonicTimes() {
        assertArrayEquals(
                new int[] {0, 12, 12, 40},
                FutoAutocorrectEngineKt.monotonicGestureTimes(
                        Arrays.asList(point(0), point(12), point(12), point(40))));
    }

    @Test
    public void clampsInvalidTimesWithoutReorderingTheTrace() {
        assertArrayEquals(
                new int[] {0, 20, 20, 30},
                FutoAutocorrectEngineKt.monotonicGestureTimes(
                        Arrays.asList(point(-5), point(20), point(10), point(30))));
    }

    @Test
    public void acceptsAnEmptyTrace() {
        assertArrayEquals(
                new int[0],
                FutoAutocorrectEngineKt.monotonicGestureTimes(Collections.emptyList()));
    }

    private static AutocorrectGesturePoint point(int elapsedTimeMillis) {
        return new AutocorrectGesturePoint(0.5f, 0.5f, elapsedTimeMillis);
    }
}
