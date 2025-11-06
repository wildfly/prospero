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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.galleon.util.ZipUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.api.exceptions.InvalidRepositoryArchiveException;

import io.undertow.Undertow;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.util.MimeMappings;

import static io.undertow.Handlers.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class RepositoryUtilsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Parameters
    public static Iterable<BiConsumer<Path, Path>> zipHandlers() {
        return List.of((source, target) -> {
            try {
                ZipUtils.zip(source, target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, (source, target) -> zipDirNoFolderEntries(source, target));
    }

    private final BiConsumer<Path, Path> zipHandler;

    public RepositoryUtilsTest(BiConsumer<Path, Path> zipHandler) {
        this.zipHandler = zipHandler;
    }

    public void unzipArchiveBeforeUsingIt() throws Exception {
        final Path repoRoot = temp.newFolder("repo").toPath();
        final Path zipFile = createRepository(repoRoot);

        final List<Repository> repositories = applyOverride(List.of(repo("temp-0", zipFile.toUri().toString())));

        assertThat(Path.of(new URL(repositories.get(0).getUrl()).toURI()).resolve("test.txt"))
                .hasContent("test text");
    }

    @Test
    public void dontUnpackNonZipFile() throws Exception {
        final File notZipFile = temp.newFile("fake.zip");

        final List<Repository> repositories = applyOverride(List.of(repo("temp-0", notZipFile.toURI().toString())));

        assertEquals(notZipFile.toURI().toString(), repositories.get(0).getUrl());
    }

    @Test
    public void dontUnpackNonZipLocalFileUrl() throws Exception {
        final File notZipFile = new File("fake.zip");

        final String url = "file:" + Path.of(".").normalize().toAbsolutePath().relativize(notZipFile.toPath().toAbsolutePath())
                .toString()
                .replace(File.separatorChar, '/');
        final List<Repository> repositories = applyOverride(List.of(repo("temp-0", url)));

        assertEquals(url, repositories.get(0).getUrl());
    }

    @Test
    public void dontUnpackLocalFolderUrl() throws Exception {
        final File notZipFile = new File("fake");

        final String url = "file:" + Path.of(".").normalize().toAbsolutePath().relativize(notZipFile.toPath().toAbsolutePath())
                .toString()
                .replace(File.separatorChar, '/');
        final List<Repository> repositories = applyOverride(List.of(repo("temp-0", url)));

        assertEquals(url, repositories.get(0).getUrl());
    }

    @Test
    public void downloadAndUnzipRemoteArchive() throws Exception {
        final Path webRoot = temp.newFolder("web-root").toPath();
        Files.move(createRepository(webRoot), webRoot.resolve("test.zip"));
        final Undertow server = Undertow.builder()
                .addHttpListener(8888, "localhost")
                .setHandler(resource(new PathResourceManager(webRoot))
                        .setMimeMappings(MimeMappings.DEFAULT)
                        .setDirectoryListingEnabled(true))
                .build();
        try {
            server.start();

            final List<Repository> repositories = applyOverride(List.of(repo("temp-0", "http://localhost:8888/test.zip")));

            assertThat(Path.of(new URL(repositories.get(0).getUrl()).toURI()).resolve("test.txt"))
                    .hasContent("test text");
        } finally {
            server.stop();
        }
    }

    @Test
    public void failIfTheArchiveHasNoFiles() throws Exception {
        final Path repoRoot = temp.newFolder("repo-root").toPath();
        final Path repoZip = temp.newFile("repo.zip").toPath();
        Files.delete(repoZip);
        zip(repoRoot, repoZip);

        assertThatThrownBy(()->applyOverride(List.of(repo("temp-0", repoZip.toUri().toString()))))
                .isInstanceOf(InvalidRepositoryArchiveException.class);
    }

    @Test
    public void failIfTheArchiveHasNoDirectories() throws Exception {
        final Path repoRoot = temp.newFolder("repo-root").toPath();
        Files.writeString(repoRoot.resolve("test.txt"), "test");
        final Path repoZip = temp.newFile("repo.zip").toPath();
        Files.delete(repoZip);
        zip(repoRoot, repoZip);

        assertThatThrownBy(()->applyOverride(List.of(repo("temp-0", repoZip.toUri().toString()))))
                .isInstanceOf(InvalidRepositoryArchiveException.class);
    }

    @Test
    public void failIfTheArchiveHasMultipleRootDirectories() throws Exception {
        final Path repoRoot = temp.newFolder("repo-root").toPath();
        Files.createDirectory(repoRoot.resolve("test"));
        Files.createDirectory(repoRoot.resolve("test2"));
        final Path repoZip = temp.newFile("repo.zip").toPath();
        Files.delete(repoZip);
        zip(repoRoot, repoZip);

        assertThatThrownBy(()->applyOverride(List.of(repo("temp-0", repoZip.toUri().toString()))))
                .isInstanceOf(InvalidRepositoryArchiveException.class);
    }

    @Test
    public void failIfTheArchivesRootDirectoriesHasNoMavenRepositoryDirectory() throws Exception {
        final Path repoRoot = temp.newFolder("repo-root").toPath();
        Files.createDirectory(repoRoot.resolve("test"));
        final Path repoZip = temp.newFile("repo.zip").toPath();
        Files.delete(repoZip);
        zip(repoRoot, repoZip);

        assertThatThrownBy(()->applyOverride(List.of(repo("temp-0", repoZip.toUri().toString()))))
                .isInstanceOf(InvalidRepositoryArchiveException.class);
    }

    private Path createRepository(Path repoRoot) throws IOException {
        Files.createDirectory(repoRoot.resolve("test-repository"));
        Files.createDirectory(repoRoot.resolve("test-repository").resolve("maven-repository"));
        Files.writeString(repoRoot.resolve("test-repository").resolve("maven-repository").resolve("test.txt"), "test text");
        final Path zipFile = temp.newFile("repo.zip").toPath();
        Files.delete(zipFile);
        zip(repoRoot, zipFile);
        return zipFile;
    }

    private static Repository repo(String id, String url) {
        return new Repository(id, url);
    }

    private static List<Repository> applyOverride(List<Repository> overrideRepositories) throws InvalidRepositoryArchiveException {
        return RepositoryUtils.unzipArchives(overrideRepositories, TemporaryFilesManager.newInstance());
    }

    public void zip(Path sourcePath, Path target) {
        zipHandler.accept(sourcePath, target);
    }

    public static void zipDirNoFolderEntries(Path sourcePath, Path target) {
        try (FileOutputStream fos = new FileOutputStream(target.toFile()); ZipOutputStream zos = new ZipOutputStream(fos)) {
            Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                    zos.putNextEntry(new ZipEntry(sourcePath.relativize(file).toString()));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}