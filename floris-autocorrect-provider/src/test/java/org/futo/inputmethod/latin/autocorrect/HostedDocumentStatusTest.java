/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.futo.inputmethod.latin.autocorrect.FutoHostedSettingsKt
        .isHostedDocumentStatusVisibleOn;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostedDocumentStatusTest {
    @Test
    public void statusAppearsOnResourcesAndItsOwnResourcePageOnly() {
        assertTrue(isHostedDocumentStatusVisibleOn("modelImport", null));
        assertTrue(isHostedDocumentStatusVisibleOn("modelImport", "models"));
        assertTrue(isHostedDocumentStatusVisibleOn("modelExport", "models"));
        assertFalse(isHostedDocumentStatusVisibleOn("modelImport", "dictionaries"));
        assertTrue(isHostedDocumentStatusVisibleOn("dictionaryImport", null));
        assertTrue(isHostedDocumentStatusVisibleOn("dictionaryImport", "dictionaries"));
        assertFalse(isHostedDocumentStatusVisibleOn("dictionaryImport", "models"));
    }
}
