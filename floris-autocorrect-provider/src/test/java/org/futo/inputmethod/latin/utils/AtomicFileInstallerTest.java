/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AtomicFileInstallerTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validFileIsReusedWithoutOpeningSource() throws Exception {
        final File target = temporaryFolder.newFile("asset.bin");
        Files.write(target.toPath(), new byte[] {1, 2, 3});
        final boolean[] sourceOpened = {false};

        AtomicFileInstaller.INSTANCE.install(
                target,
                3L,
                () -> {
                    sourceOpened[0] = true;
                    return new ByteArrayInputStream(new byte[] {4, 5, 6});
                },
                file -> file.length() == 3L);

        assertFalse(sourceOpened[0]);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target.toPath()));
    }

    @Test
    public void failedCopyNeverReplacesTheTarget() throws Exception {
        final File directory = temporaryFolder.newFolder();
        final File target = new File(directory, "asset.bin");
        Files.write(target.toPath(), new byte[] {1});

        assertThrows(
                IOException.class,
                () -> AtomicFileInstaller.INSTANCE.install(
                        target,
                        3L,
                        () -> new ByteArrayInputStream(new byte[] {2, 3}),
                        file -> file.length() == 3L));

        assertArrayEquals(new byte[] {1}, Files.readAllBytes(target.toPath()));
        final File[] staging = directory.listFiles(file -> file.getName().endsWith(".staging"));
        assertFalse(staging != null && staging.length > 0);
    }

    @Test
    public void validatedStagingReplacesAnInvalidTarget() throws Exception {
        final File directory = temporaryFolder.newFolder();
        final File target = new File(directory, "asset.bin");
        Files.write(target.toPath(), new byte[] {1});
        final File stale = new File(directory, ".asset.bin-orphan.staging");
        Files.write(stale.toPath(), new byte[] {9});
        final byte[] replacement = {2, 3, 4};

        AtomicFileInstaller.INSTANCE.install(
                target,
                (long) replacement.length,
                () -> new ByteArrayInputStream(replacement),
                file -> file.length() == replacement.length);

        assertArrayEquals(replacement, Files.readAllBytes(target.toPath()));
        assertFalse(stale.exists());
    }

    @Test
    public void failedValidationNeverReplacesTheTarget() throws Exception {
        final File target = temporaryFolder.newFile("asset.bin");
        Files.write(target.toPath(), new byte[] {1});

        assertThrows(
                IOException.class,
                () -> AtomicFileInstaller.INSTANCE.install(
                        target,
                        3L,
                        () -> new ByteArrayInputStream(new byte[] {2, 3, 4}),
                        file -> false));

        assertArrayEquals(new byte[] {1}, Files.readAllBytes(target.toPath()));
    }
}
