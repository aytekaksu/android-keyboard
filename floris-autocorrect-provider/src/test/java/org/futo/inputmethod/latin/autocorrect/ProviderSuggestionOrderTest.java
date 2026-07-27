/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.futo.inputmethod.latin.SuggestedWords;
import org.futo.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import org.futo.inputmethod.latin.uix.SuggestionLayout;
import org.futo.inputmethod.latin.uix.SuggestionLayoutKt;
import org.junit.Test;

public class ProviderSuggestionOrderTest {
    @Test
    public void autocorrectWithEmojiUsesFutoClassicSlots() {
        final SuggestedWordInfo typed = word("helo", SuggestedWordInfo.KIND_TYPED);
        final SuggestedWordInfo correction = word("hello");
        final SuggestedWordInfo emoji = word("👋", SuggestedWordInfo.KIND_EMOJI_SUGGESTION);
        assertEquals(
                Arrays.asList("👋", "hello", "helo"),
                words(SuggestionLayoutKt.classicSuggestions(
                        layout(correction, words("help"), list(emoji), typed, false))));
    }

    @Test
    public void autocorrectWithoutEmojiUsesTopAlternativeThenTypedWord() {
        final SuggestedWordInfo typed = word("helo", SuggestedWordInfo.KIND_TYPED);
        final SuggestedWordInfo correction = word("hello");
        assertEquals(
                Arrays.asList("help", "hello", "helo"),
                words(SuggestionLayoutKt.classicSuggestions(
                        layout(correction, words("help", "helm"), empty(), typed, false))));
    }

    @Test
    public void selfAutocorrectKeepsTheTypedWordCenteredWithoutDuplicatingIt() {
        final SuggestedWordInfo typed = word("hello", SuggestedWordInfo.KIND_TYPED);
        final SuggestedWordInfo correction = word("hello");
        final SuggestedWordInfo first = word("help");
        final SuggestedWordInfo second = word("helm");
        final SuggestedWords source = new SuggestedWords(
                new ArrayList<>(Arrays.asList(typed, correction, first, second)),
                null,
                typed,
                true,
                true,
                false,
                SuggestedWords.INPUT_STYLE_TYPING,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER);
        final List<SuggestedWordInfo> classic = SuggestionLayoutKt.classicSuggestions(
                SuggestionLayoutKt.makeSuggestionLayout(source, null, false));
        assertEquals(
                Arrays.asList("help", "hello", "helm"),
                words(FutoAutocorrectEngineKt.orderProviderSuggestions(
                        classic,
                        Arrays.asList(typed, correction, first, second),
                        3)));
    }

    @Test
    public void noAutocorrectUsesFutoAlternativeOrder() {
        final List<SuggestedWordInfo> matches = words("best", "second", "third");
        assertEquals(
                Arrays.asList("second", "best", "third"),
                words(SuggestionLayoutKt.classicSuggestions(
                        layout(
                                null,
                                matches,
                                empty(),
                                word("typed", SuggestedWordInfo.KIND_TYPED),
                                false))));
        assertEquals(
                Arrays.asList("🙂", "best", "second"),
                words(SuggestionLayoutKt.classicSuggestions(
                        layout(
                                null,
                                matches,
                                list(word("🙂", SuggestedWordInfo.KIND_EMOJI_SUGGESTION)),
                                null,
                                false))));
    }

    @Test
    public void gestureKeepsBestFirstAndAppendsRemainingResults() {
        final List<SuggestedWordInfo> eligible = words("hello", "help", "held");
        final SuggestionLayout layout = layout(null, eligible, empty(), null, true);
        final List<SuggestedWordInfo> classic =
                SuggestionLayoutKt.classicSuggestions(layout);
        assertEquals(Collections.singletonList("hello"), words(classic));
        assertEquals(
                Arrays.asList("hello", "help", "held"),
                words(FutoAutocorrectEngineKt.orderProviderSuggestions(
                        classic, eligible, 3)));
    }

    @Test
    public void appendsExtrasThenDeduplicatesAndLimits() {
        final SuggestedWordInfo typed = word("helo", SuggestedWordInfo.KIND_TYPED);
        final SuggestedWordInfo correction = word("hello");
        final SuggestedWordInfo first = word("help");
        final SuggestedWordInfo second = word("helm");
        final SuggestedWordInfo extra = word("held");
        final List<SuggestedWordInfo> classic = SuggestionLayoutKt.classicSuggestions(
                layout(
                        correction,
                        Arrays.asList(first, second, extra),
                        empty(),
                        typed,
                        false));
        assertEquals(
                Arrays.asList("help", "hello", "helo", "helm", "held"),
                words(FutoAutocorrectEngineKt.orderProviderSuggestions(
                        classic,
                        Arrays.asList(
                                typed, correction, first, second, extra, word("hello")),
                        5)));
    }

    @Test
    public void doesNotInventOrDuplicateMissingSlots() {
        final SuggestedWordInfo only = word("only");
        final List<SuggestedWordInfo> classic = SuggestionLayoutKt.classicSuggestions(
                layout(null, list(only), empty(), null, false));
        assertEquals(Collections.singletonList("only"), words(classic));
        assertEquals(
                Collections.singletonList("only"),
                words(FutoAutocorrectEngineKt.orderProviderSuggestions(
                        classic, list(only), 3)));
    }

    private static SuggestionLayout layout(
            SuggestedWordInfo autocorrect,
            List<SuggestedWordInfo> matches,
            List<SuggestedWordInfo> emojis,
            SuggestedWordInfo typed,
            boolean gesture) {
        final List<SuggestedWordInfo> presentable = new ArrayList<>();
        if (typed != null) presentable.add(typed);
        if (autocorrect != null) presentable.add(autocorrect);
        presentable.addAll(matches);
        return new SuggestionLayout(
                autocorrect,
                matches,
                emojis,
                typed,
                false,
                gesture,
                null,
                presentable);
    }

    private static SuggestedWordInfo word(String text) {
        return word(text, SuggestedWordInfo.KIND_CORRECTION);
    }

    private static SuggestedWordInfo word(String text, int kind) {
        return new SuggestedWordInfo(text, "", 1, kind, null, 0, 0);
    }

    private static List<SuggestedWordInfo> words(String... values) {
        return Arrays.stream(values).map(ProviderSuggestionOrderTest::word)
                .collect(Collectors.toList());
    }

    private static List<String> words(List<SuggestedWordInfo> values) {
        return values.stream().filter(value -> value != null).map(value -> value.mWord)
                .collect(Collectors.toList());
    }

    @SafeVarargs
    private static <T> List<T> list(T... values) {
        return Arrays.asList(values);
    }

    private static <T> List<T> empty() {
        return Collections.emptyList();
    }
}
