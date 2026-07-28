/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.autocorrect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.florisboard.autocorrect.api.AutocorrectTouchPoint;
import org.junit.Test;

public class TouchPointAlignmentTest {
    @Test
    public void repeatsPhysicalPointForEachUnicodeCodePoint() {
        final AutocorrectTouchPoint first = point("a", 0.1f);
        final AutocorrectTouchPoint combined = point("i\u0307", 0.2f);
        final AutocorrectTouchPoint last = point("b", 0.3f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "ai\u0307b", Arrays.asList(first, combined, last));

        assertEquals(4, aligned.size());
        assertSame(first, aligned.get(0));
        assertSame(combined, aligned.get(1));
        assertSame(combined, aligned.get(2));
        assertSame(last, aligned.get(3));
    }

    @Test
    public void countsSupplementaryCharactersAsSingleCodePoints() {
        final AutocorrectTouchPoint multi = point("\uD83D\uDE00x", 0.4f);
        final AutocorrectTouchPoint last = point("y", 0.5f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "\uD83D\uDE00xy", Arrays.asList(multi, last));

        assertEquals(3, aligned.size());
        assertSame(multi, aligned.get(0));
        assertSame(multi, aligned.get(1));
        assertSame(last, aligned.get(2));
    }

    @Test
    public void leavesMissingOrMismatchedPointsForGeometryFallback() {
        final AutocorrectTouchPoint first = point("a", 0.1f);
        final AutocorrectTouchPoint mismatched = point("x", 0.2f);
        final AutocorrectTouchPoint third = point("c", 0.3f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "abcd", Arrays.asList(first, mismatched, third));

        assertEquals(4, aligned.size());
        assertSame(first, aligned.get(0));
        assertNull(aligned.get(1));
        assertSame(third, aligned.get(2));
        assertNull(aligned.get(3));
    }

    @Test
    public void alignsCanonicallyEquivalentTextWithoutShiftingTheFollowingPoint() {
        final AutocorrectTouchPoint decomposed = point("e\u0301", 0.2f);
        final AutocorrectTouchPoint following = point("b", 0.3f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "\u00E9b", Arrays.asList(decomposed, following));

        assertEquals(2, aligned.size());
        assertSame(decomposed, aligned.get(0));
        assertSame(following, aligned.get(1));
    }

    @Test
    public void repeatsComposedPhysicalPointForDecomposedTypedText() {
        final AutocorrectTouchPoint composed = point("\u00E9", 0.2f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "e\u0301", Arrays.asList(composed));

        assertEquals(2, aligned.size());
        assertSame(composed, aligned.get(0));
        assertSame(composed, aligned.get(1));
    }

    @Test
    public void rejectsPartialCanonicalMatchAndStillFindsTheFollowingPoint() {
        final AutocorrectTouchPoint unaccented = point("e", 0.2f);
        final AutocorrectTouchPoint following = point("b", 0.3f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "\u00E9b", Arrays.asList(unaccented, following));

        assertNull(aligned.get(0));
        assertSame(following, aligned.get(1));
    }

    @Test
    public void usesSessionLocaleForCaseAlignment() {
        final AutocorrectTouchPoint dotted = point("i", 0.2f);
        final AutocorrectTouchPoint dotless = point("\u0131", 0.3f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "\u0130I",
                        Arrays.asList(dotted, dotless),
                        Locale.forLanguageTag("tr"));

        assertSame(dotted, aligned.get(0));
        assertSame(dotless, aligned.get(1));
    }

    @Test
    public void preservesPhysicalPointAcrossFullCaseExpansion() {
        final AutocorrectTouchPoint sharpS = point("\u00DF", 0.2f);

        final List<AutocorrectTouchPoint> aligned =
                FutoAutocorrectEngineKt.alignedTouchPoints(
                        "SS", Arrays.asList(sharpS), Locale.GERMAN);

        assertSame(sharpS, aligned.get(0));
        assertSame(sharpS, aligned.get(1));
    }

    private static AutocorrectTouchPoint point(String text, float x) {
        return new AutocorrectTouchPoint(text, x, 0.5f);
    }
}
