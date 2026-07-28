/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PersonalizationPolicyTest {
    @Test
    public void writesRequireAllThreeLearningConditions() {
        for (boolean userEnabled : new boolean[]{false, true}) {
            for (boolean sessionAllowsLearning : new boolean[]{false, true}) {
                for (boolean editorAllowsLearning : new boolean[]{false, true}) {
                    final PersonalizationPolicy policy =
                            FutoAutocorrectEngineKt.personalizationPolicy(
                                    userEnabled,
                                    sessionAllowsLearning,
                                    editorAllowsLearning);
                    assertEquals(userEnabled, policy.getAllowReads());
                    assertEquals(
                            userEnabled && sessionAllowsLearning && editorAllowsLearning,
                            policy.getAllowWrites());
                }
            }
        }
    }
}
