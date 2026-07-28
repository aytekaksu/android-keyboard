/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TraceCoordinateTest {
    @Test
    public void mapsNormalizedEndpointsInsideTheKeyboard() {
        assertEquals(0, FutoAutocorrectEngineKt.normalizedCoordinate(0.0f, 1000));
        assertEquals(500, FutoAutocorrectEngineKt.normalizedCoordinate(0.5f, 1000));
        assertEquals(999, FutoAutocorrectEngineKt.normalizedCoordinate(1.0f, 1000));
    }

    @Test
    public void containsMalformedOrDegenerateCoordinates() {
        assertEquals(0, FutoAutocorrectEngineKt.normalizedCoordinate(-1.0f, 1000));
        assertEquals(999, FutoAutocorrectEngineKt.normalizedCoordinate(2.0f, 1000));
        assertEquals(0, FutoAutocorrectEngineKt.normalizedCoordinate(Float.NaN, 1000));
        assertEquals(0, FutoAutocorrectEngineKt.normalizedCoordinate(0.5f, 0));
    }
}
