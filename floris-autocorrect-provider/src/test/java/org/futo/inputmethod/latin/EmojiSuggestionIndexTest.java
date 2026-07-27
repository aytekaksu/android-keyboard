/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class EmojiSuggestionIndexTest {
    private final Set<String> toneable =
            new HashSet<>(Arrays.asList("👋", "👩"));

    @Test
    public void appliesToneToSimpleAndZwjEmoji() {
        assertEquals(
                "👋🏻",
                EmojiSuggestionIndexKt.applyPreferredSkinTone("👋", 0x1F3FB, toneable));
        assertEquals(
                "👩🏽‍💻",
                EmojiSuggestionIndexKt.applyPreferredSkinTone("👩‍💻", 0x1F3FD, toneable));
    }

    @Test
    public void defaultAndInvalidModifiersLeaveEmojiUnchanged() {
        assertEquals("👋", EmojiSuggestionIndexKt.applyPreferredSkinTone("👋", 0, toneable));
        assertEquals(
                "👋",
                EmojiSuggestionIndexKt.applyPreferredSkinTone("👋", 0x1F400, toneable));
        assertEquals(
                "🙂",
                EmojiSuggestionIndexKt.applyPreferredSkinTone("🙂", 0x1F3FB, toneable));
    }

    @Test
    public void replacesAnExistingToneInsteadOfAddingAnother() {
        assertEquals(
                "👋🏿",
                EmojiSuggestionIndexKt.applyPreferredSkinTone("👋🏻", 0x1F3FF, toneable));
        assertEquals(
                "👩🏼‍💻",
                EmojiSuggestionIndexKt.applyPreferredSkinTone(
                        "👩🏽‍💻", 0x1F3FC, toneable));
    }
}
