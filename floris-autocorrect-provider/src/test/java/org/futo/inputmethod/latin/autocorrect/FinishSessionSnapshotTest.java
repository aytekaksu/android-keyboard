/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.florisboard.autocorrect.api.AutocorrectCapsMode;
import org.florisboard.autocorrect.api.AutocorrectInputTrace;
import org.florisboard.autocorrect.api.AutocorrectRequest;
import org.junit.Test;

public class FinishSessionSnapshotTest {
    @Test
    public void freshFinalSnapshotWinsOverTheLastSuggestionRequest() {
        assertEquals(
                "fresh@example.test",
                FutoAutocorrectEngineKt.committedEmailForFinish(
                        7L,
                        request(7L, "fresh@example.test"),
                        request(7L, "stale@example.test")));
    }

    @Test
    public void staleSessionSnapshotDoesNotFallBack() {
        assertNull(
                FutoAutocorrectEngineKt.committedEmailForFinish(
                        7L,
                        request(8L, "stale@example.test"),
                        request(7L, "current@example.test")));
    }

    @Test
    public void validEmptySnapshotDoesNotFallBackToStaleText() {
        assertNull(
                FutoAutocorrectEngineKt.committedEmailForFinish(
                        7L,
                        request(7L, ""),
                        request(7L, "stale@example.test")));
    }

    @Test
    public void selectedSnapshotDoesNotFallBackToStaleText() {
        assertNull(
                FutoAutocorrectEngineKt.committedEmailForFinish(
                        7L,
                        request(7L, "fresh@example.test", 0, 5),
                        request(7L, "stale@example.test")));
    }

    private static AutocorrectRequest request(long sessionId, String text) {
        return request(sessionId, text, text.length(), text.length());
    }

    private static AutocorrectRequest request(
            long sessionId, String text, int selectionStart, int selectionEnd) {
        return new AutocorrectRequest(
                sessionId,
                1L,
                text,
                selectionStart,
                selectionEnd,
                -1,
                -1,
                -1,
                -1,
                3,
                false,
                AutocorrectInputTrace.Companion.getEmpty(),
                AutocorrectCapsMode.UNSPECIFIED);
    }
}
