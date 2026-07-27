/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CommittedEmailTest {
    @Test
    public void acceptsCompleteAddressBeforeCursor() {
        assertEquals(
                "probe@codexunique.test",
                FutoAutocorrectEngineKt.committedEmailBeforeCursor(
                        "probe@codexunique.test", 22));
        assertEquals(
                "probe@codexunique.test",
                FutoAutocorrectEngineKt.committedEmailBeforeCursor(
                        "probe@codexunique.test suffix", 22));
    }

    @Test
    public void rejectsMalformedOrPartialAddress() {
        final String[] invalid = {
                "",
                "probe",
                "probe@codexunique",
                "@codexunique.test",
                "probe@",
                "probe@@codexunique.test",
                "probe@.test",
                "probe@codexunique.",
                "probe @codexunique.test",
        };
        for (final String text : invalid) {
            assertNull(
                    text,
                    FutoAutocorrectEngineKt.committedEmailBeforeCursor(text, text.length()));
        }
    }

    @Test
    public void enforcesUpstreamLengthLimit() {
        final String accepted = repeat('a', 34) + "@example.test";
        final String rejected = repeat('a', 35) + "@example.test";
        assertEquals(
                accepted,
                FutoAutocorrectEngineKt.committedEmailBeforeCursor(
                        accepted, accepted.length()));
        assertNull(
                FutoAutocorrectEngineKt.committedEmailBeforeCursor(
                        rejected, rejected.length()));
        assertNull(
                FutoAutocorrectEngineKt.committedEmailBeforeCursor(
                        "probe@codexunique.test", -1));
    }

    private static String repeat(char value, int count) {
        final StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
