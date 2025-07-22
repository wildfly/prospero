/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.api;

import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks created temporary files and removes them when {@code close()} is closed.
 */
public class TemporaryFilesManager implements AutoCloseable {

    private final Set<Path> temporaryFiles = new HashSet<>();

    TemporaryFilesManager() {

    }

    public static TemporaryFilesManager newInstance() {
        return new TemporaryFilesManager();
    }

    public Path createTempDirectory(String prefix) throws IOException {
        final Path tempDirectory = Files.createTempDirectory(prefix);
        tempDirectory.toFile().deleteOnExit();
        temporaryFiles.add(tempDirectory);
        return tempDirectory;
    }

    public Path createTempFile(String prefix, String suffix) throws IOException {
        final Path tempFile = Files.createTempFile(prefix, suffix);
        tempFile.toFile().deleteOnExit();
        temporaryFiles.add(tempFile);
        return tempFile;
    }

    @Override
    public void close() {
        temporaryFiles.stream()
                .map(Path::toFile)
                .forEach(FileUtils::deleteQuietly);
    }
}
