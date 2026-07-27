/*
 * Copyright (C) 2026 FUTO Holdings, Inc.
 *
 * Licensed under the FUTO Source First License 1.1-kb.
 * See LICENSE.md in the repository root.
 */

package org.futo.inputmethod.latin.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Installs an immutable file only after its durable staging copy passes validation. */
internal object AtomicFileInstaller {
    @Synchronized
    fun install(
        target: File,
        expectedSize: Long? = null,
        source: () -> InputStream,
        isValid: (File) -> Boolean,
    ): File {
        if (isValid(target)) return target

        val directory = target.parentFile
            ?: throw IOException("Target has no parent directory: $target")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create directory: $directory")
        }

        val stagingPrefix = ".${target.name}-"
        directory.listFiles { file ->
            file.isFile &&
                file.name.startsWith(stagingPrefix) &&
                file.name.endsWith(".staging")
        }?.forEach { it.delete() }
        val staging = File.createTempFile(stagingPrefix, ".staging", directory)
        try {
            val copied = source().use { input ->
                FileOutputStream(staging).use { output ->
                    val count = input.copyTo(output, COPY_BUFFER_SIZE)
                    output.flush()
                    output.fd.sync()
                    count
                }
            }
            if (expectedSize != null && copied != expectedSize) {
                throw IOException(
                    "Expected $expectedSize bytes for ${target.name}, copied $copied",
                )
            }
            if (!isValid(staging)) {
                throw IOException("Staged file failed validation: ${target.name}")
            }

            if (!staging.renameTo(target)) {
                throw IOException("Could not install file: $target")
            }
            if (!isValid(target)) {
                target.delete()
                throw IOException("Installed file failed validation: ${target.name}")
            }
            return target
        } finally {
            staging.delete()
        }
    }

    private const val COPY_BUFFER_SIZE = 64 * 1024
}
