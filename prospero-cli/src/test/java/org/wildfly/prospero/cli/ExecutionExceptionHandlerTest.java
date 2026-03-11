/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.cli;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelMetadataCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.api.exceptions.UnresolvedChannelMetadataException;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ExecutionExceptionHandlerTest {

    private ExecutionExceptionHandler handler;
    private ByteArrayOutputStream errorStream;
    private PrintStream originalErr;
    private CommandLine commandLine;
    private CommandLine.ParseResult parseResult;

    @Before
    public void setUp() {
        CliConsole console = new CliConsole();
        handler = new ExecutionExceptionHandler(console, false);
        errorStream = new ByteArrayOutputStream();
        originalErr = System.err;
        System.setErr(new PrintStream(errorStream));
        commandLine = mock(CommandLine.class);
        parseResult = mock(CommandLine.ParseResult.class);
    }

    @After
    public void tearDown() {
        System.setErr(originalErr);
    }

    @Test
    public void testRepositoryUrlWithPercentEncodedCharactersDoesNotThrowException() throws Exception {
        // Given: A repository URL with percent-encoded characters (e.g., %20 for space)
        Set<Repository> repositories = new HashSet<>();
        repositories.add(new Repository("test-repo", "file:/home/user/eap%20artifacts/repository"));

        ChannelMetadataCoordinate coordinate = new ChannelMetadataCoordinate("org.test", "test-artifact", "1.0.0",
                ChannelManifest.CLASSIFIER, ChannelManifest.EXTENSION);
        UnresolvedChannelMetadataException exception = new UnresolvedChannelMetadataException(
                "Unable to resolve metadata",
                null,
                Collections.singleton(coordinate),
                repositories,
                false
        );

        // When: The exception is handled
        int exitCode = handler.handleExecutionException(exception, commandLine, parseResult);

        // Then: No MissingFormatArgumentException is thrown and the URL is displayed correctly
        String output = errorStream.toString();
        assertThat(output).contains("file:/home/user/eap%20artifacts/repository");
        assertThat(output).contains("test-repo");
        assertThat(exitCode).isEqualTo(ReturnCodes.PROCESSING_ERROR);
    }

    @Test
    public void testRepositoryUrlWithMultiplePercentEncodedCharacters() throws Exception {
        // Given: A repository URL with multiple percent-encoded characters
        Set<Repository> repositories = new HashSet<>();
        repositories.add(new Repository("complex-repo", "file:/path/with%20spaces%20and%2Bplus"));

        ChannelMetadataCoordinate coordinate = new ChannelMetadataCoordinate("org.test", "test-artifact", "1.0.0",
                ChannelManifest.CLASSIFIER, ChannelManifest.EXTENSION);
        UnresolvedChannelMetadataException exception = new UnresolvedChannelMetadataException(
                "Unable to resolve metadata",
                null,
                Collections.singleton(coordinate),
                repositories,
                false
        );

        // When: The exception is handled
        int exitCode = handler.handleExecutionException(exception, commandLine, parseResult);

        // Then: All percent-encoded characters are displayed correctly
        String output = errorStream.toString();
        assertThat(output).contains("file:/path/with%20spaces%20and%2Bplus");
        assertThat(output).contains("complex-repo");
        assertThat(exitCode).isEqualTo(ReturnCodes.PROCESSING_ERROR);
    }

    @Test
    public void testRepositoryUrlWithoutPercentCharacters() throws Exception {
        // Given: A normal repository URL without percent characters
        Set<Repository> repositories = new HashSet<>();
        repositories.add(new Repository("normal-repo", "https://repo.maven.org/maven2/"));

        ChannelMetadataCoordinate coordinate = new ChannelMetadataCoordinate("org.test", "test-artifact", "1.0.0",
                ChannelManifest.CLASSIFIER, ChannelManifest.EXTENSION);
        UnresolvedChannelMetadataException exception = new UnresolvedChannelMetadataException(
                "Unable to resolve metadata",
                null,
                Collections.singleton(coordinate),
                repositories,
                false
        );

        // When: The exception is handled
        int exitCode = handler.handleExecutionException(exception, commandLine, parseResult);

        // Then: The URL is displayed correctly
        String output = errorStream.toString();
        assertThat(output).contains("https://repo.maven.org/maven2/");
        assertThat(output).contains("normal-repo");
        assertThat(exitCode).isEqualTo(ReturnCodes.PROCESSING_ERROR);
    }

    @Test
    public void testMultipleRepositoriesWithMixedUrls() throws Exception {
        // Given: Multiple repositories with mixed URL formats
        Set<Repository> repositories = new HashSet<>();
        repositories.add(new Repository("repo-with-space", "file:/home/user/my%20repo/"));
        repositories.add(new Repository("normal-repo", "https://repo.maven.org/maven2/"));
        repositories.add(new Repository("local-repo", "file:/tmp/local-repository"));

        ChannelMetadataCoordinate coordinate = new ChannelMetadataCoordinate("org.test", "test-artifact", "1.0.0",
                ChannelManifest.CLASSIFIER, ChannelManifest.EXTENSION);
        UnresolvedChannelMetadataException exception = new UnresolvedChannelMetadataException(
                "Unable to resolve metadata",
                null,
                Collections.singleton(coordinate),
                repositories,
                false
        );

        // When: The exception is handled
        int exitCode = handler.handleExecutionException(exception, commandLine, parseResult);

        // Then: All repositories are displayed correctly
        String output = errorStream.toString();
        assertThat(output).contains("file:/home/user/my%20repo/");
        assertThat(output).contains("https://repo.maven.org/maven2/");
        assertThat(output).contains("file:/tmp/local-repository");
        assertThat(exitCode).isEqualTo(ReturnCodes.PROCESSING_ERROR);
    }
}