/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import org.florisboard.autocorrect.api.AutocorrectPluginContractKt;
import org.junit.Test;

public class TraceTextLimitTest {
    @Test
    public void traceTextLimitDoesNotSplitSupplementaryCodePoints() {
        final String input = "1234567\uD83D\uDE00tail";

        final String limited = AutocorrectPluginContractKt.takeWireChars(input, 8);

        assertEquals("1234567", limited);
        assertEquals(7, limited.length());
    }

    @Test
    public void traceTextLimitCountsSupplementaryCharactersOnce() {
        final String emoji = "\uD83D\uDE00";
        final String repeated = String.join("", Collections.nCopies(4, emoji));

        assertEquals(repeated, AutocorrectPluginContractKt.takeWireChars(repeated + "x", 8));
    }
}
