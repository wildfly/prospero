/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.cli.commands;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.assertj.core.groups.Tuple;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.MavenCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.cli.AbstractConsoleTest;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.test.MetadataTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;

public class ChannelCommandTest extends AbstractConsoleTest {

    private static final MavenCoordinate GA = new MavenCoordinate("g", "a", null);
    private static final MavenCoordinate GAV = new MavenCoordinate("g", "a", "v");
    private static final String URL = "file:/a:b";

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    private Path dir;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        this.dir = tempDir.newFolder().toPath();
        Channel gaChannel = createChannel("test1", ChannelManifestCoordinate.create(null, GA));
        Channel gavChannel = createChannel("test2", ChannelManifestCoordinate.create(null, GAV));
        Channel urlChannel = createChannel("test3", ChannelManifestCoordinate.create(URL, null));
        MetadataTestUtils.createInstallationMetadata(dir, new ChannelManifest(null, null, null, null),
                Arrays.asList(gaChannel, gavChannel, urlChannel));
        MetadataTestUtils.createGalleonProvisionedState(dir);
    }

    @Test
    public void testListInvalidInstallationDir() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.LIST);

        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertTrue(getErrorOutput().contains(CliMessages.MESSAGES.invalidInstallationDir(ChannelCommand.currentDir())
                .getMessage()));
    }

    @Test
    public void testVersionInvalidInstallationDir() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.VERSIONS);

        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertTrue(getErrorOutput().contains(CliMessages.MESSAGES.invalidInstallationDir(ChannelCommand.currentDir())
                .getMessage()));
    }

    @Test
    public void testAddEmptyRepository() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-0",
                CliConstants.CHANNEL_MANIFEST, "org.test:test",
                CliConstants.REPOSITORIES, "");

        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertTrue(getErrorOutput().contains(CliMessages.MESSAGES.invalidRepositoryDefinition("")
                .getMessage()));
    }

    @Test
    public void testAddInvalidRepositoryTooManyParts() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-0",
                CliConstants.CHANNEL_MANIFEST, "org.test:test",
                CliConstants.REPOSITORIES, "id::http://test.te::foo");

        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertTrue(getErrorOutput().contains(CliMessages.MESSAGES.invalidRepositoryDefinition("id::http://test.te::foo")
                .getMessage()));
    }

    @Test
    public void testAdd() throws MetadataException, MalformedURLException {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-0",
                CliConstants.CHANNEL_MANIFEST, "org.test:test",
                CliConstants.REPOSITORIES, "test_repo::http://test.te");
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);

        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-1",
                CliConstants.CHANNEL_MANIFEST, "g:a2",
                CliConstants.REPOSITORIES, "test_repo::http://test.te");
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);

        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-2",
                CliConstants.CHANNEL_MANIFEST, "file:/path",
                CliConstants.REPOSITORIES, "test_repo::http://test.te");
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);

        InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(dir);
        assertThat(installationMetadata.getProsperoConfig().getChannels())
                .flatMap(c->c.getRepositories())
                .map(r->Tuple.tuple(r.getId(), r.getUrl()))
                .containsExactly(
                        Tuple.tuple("test", "http://test.org"),
                        Tuple.tuple("test", "http://test.org"),
                        Tuple.tuple("test", "http://test.org"),
                        Tuple.tuple("test_repo", "http://test.te"),
                        Tuple.tuple("test_repo", "http://test.te"),
                        Tuple.tuple("test_repo", "http://test.te")
                );
        assertThat(installationMetadata.getProsperoConfig().getChannels())
                .map(c->c.getManifestCoordinate())
                .map(r->Tuple.tuple(r.getMaven(), r.getUrl()))
                .containsExactly(
                        Tuple.tuple(GA, null),
                        Tuple.tuple(GAV, null),
                        Tuple.tuple(null, new URL(URL)),
                        Tuple.tuple(new MavenCoordinate("org.test", "test", null), null),
                        Tuple.tuple(new MavenCoordinate("g", "a2", null), null),
                        Tuple.tuple(null, new URL("file:/path"))
                );
    }

    @Test
    public void testAddInvalidInstallationDir() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.CHANNEL_NAME, "channel-0",
                CliConstants.CHANNEL_MANIFEST, "org.test:test",
                CliConstants.REPOSITORIES, "test_repo::http://test.te");

        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertTrue(getErrorOutput().contains(CliMessages.MESSAGES.invalidInstallationDir(ChannelCommand.currentDir())
                .getMessage()));
    }

    @Test
    public void testRemoveInvalidInstallationDir() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.CHANNEL_NAME, "test2");

        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertTrue(getErrorOutput().contains(CliMessages.MESSAGES.invalidInstallationDir(ChannelCommand.currentDir())
                .getMessage()));
    }

    @Test
    public void testList() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.LIST,
                CliConstants.DIR, dir.toString());
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        assertThat(getStandardOutput().lines()
                .filter(line -> line.matches("\\s+\\d+\\.\\s+.*")))
                .containsExactly(
                        "   1. test1 g:a",
                        "   2. test2 g:a:v",
                        "   3. test3 file:/a:b"
                );
    }

    @Test
    public void testFullList() throws MetadataException, IOException {
        // Execute the command
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.LIST, CliConstants.FULL,
                CliConstants.DIR, dir.toString());
        InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(dir);

        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        assertThat(getStandardOutput()).contains(ChannelMapper.toYaml(installationMetadata.getProsperoConfig().getChannels()));

    }


    @Test
    public void testAddDuplicate() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(), toGav(GAV));
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);

        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(), toGav(GA));
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);

        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(), URL);
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
    }

    @Test
    public void testRemove() throws Exception {
        try (InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(dir)) {
            installationMetadata.getProsperoConfig().getChannels().forEach(System.out::println);
        }
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "test2");
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        try (InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(dir)) {
            assertThat(installationMetadata.getProsperoConfig().getChannels())
                    .map(c->c.getManifestCoordinate())
                    .map(r->Tuple.tuple(r.getMaven(), r.getUrl()))
                    .containsExactly(
                            Tuple.tuple(GA, null),
                            Tuple.tuple(null, new URL(URL))
                    );
        }

        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "test1");
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        try (InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(dir)) {
            assertThat(installationMetadata.getProsperoConfig().getChannels())
                    .map(c->c.getManifestCoordinate())
                    .map(r->Tuple.tuple(r.getMaven(), r.getUrl()))
                    .containsExactly(
                            Tuple.tuple(null, new URL(URL))
                    );
        }

        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "test3");
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        try (InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(dir)) {
            assertThat(installationMetadata.getProsperoConfig().getChannels())
                    .map(c->c.getManifestCoordinate())
                    .map(r->Tuple.tuple(r.getMaven(), r.getUrl()))
                    .isEmpty();
        }
    }

    @Test
    public void testRemoveNonExisting() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME,  "test4");
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);

        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "test-1");
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);

        // remove the channel twice
        commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "test1");
        exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "test1");
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
    }

    @Test
    public void testRemoveEmptyChannel() {
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.REMOVE,
                CliConstants.DIR, dir.toString());
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
    }
    @Test
    public void testChannelAdd_WithValidChannelDefinition() throws Exception {
        final Path path = MetadataTestUtils.prepareChannel("manifest.yaml");
        int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-0",
                CliConstants.CHANNEL, path.toString());
        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);

        try (InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(dir)) {
            final List<Channel> registeredChannels = installationMetadata.getProsperoConfig().getChannels();
            final Channel addedChannel = registeredChannels.get(3);
            Assert.assertEquals(addedChannel.getName(), "channel-0");
            assertThat(addedChannel.getRepositories().stream()
                    .map(r -> Tuple.tuple(r.getId(), r.getUrl()))
                    .collect(Collectors.toList())) // Convert the Stream to a List
                    .containsExactly(
                            Tuple.tuple("maven-central", "https://repo1.maven.org/maven2/"),
                            Tuple.tuple("nexus", "https://repository.jboss.org/nexus/content/groups/public"),
                            Tuple.tuple("maven-redhat-ga", "https://maven.repository.redhat.com/ga"));
        }
    }

    @Test
    public void testChannelAdd_ChannelLocationIsExclusiveWithManifestParameter() throws Exception {
        final Path path = MetadataTestUtils.prepareChannel("manifest.yaml");
        final int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-0",
                CliConstants.CHANNEL, path.toString(),
                CliConstants.CHANNEL_MANIFEST, "org.test:test",
                CliConstants.REPOSITORIES, "test_repo::http://test.te");
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains("mutually exclusive")
                .contains(CliConstants.CHANNEL, CliConstants.REPOSITORIES, CliConstants.CHANNEL_MANIFEST);
    }

    @Test
    public void testChannelAdd_ChannelLocationChannelDefinitionFileDoesNotExist() throws Exception {
        final Path nonExistingFilePath = Paths.get("non-existing-file.yaml").toAbsolutePath();
        final int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-1",
                CliConstants.CHANNEL, nonExistingFilePath.toString());
        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(CliMessages.MESSAGES.missingRequiresResource(nonExistingFilePath.toString(), new Exception()).getMessage());
    }

    @Test
    public void testChannelAdd_ChannelLocationChannelDefinitionIsInvalid() throws Exception {
        final File invalidChannelFile = tempDir.newFile("InvalidChannelFile.yaml");
        final List<String> yamlContent = List.of(
                "channels:",
                "  - name: invalid-channel",
                "    description: This is an invalid channel for testing purposes.",
                "    repositories:",
                "      - id: test-repo",
                "        url: http://invalid-url.org/repo"
        );
        Files.write(invalidChannelFile.toPath(), yamlContent);
        final int exitCode = commandLine.execute(CliConstants.Commands.CHANNEL, CliConstants.Commands.ADD,
                CliConstants.DIR, dir.toString(),
                CliConstants.CHANNEL_NAME, "channel-2",
                CliConstants.CHANNEL, invalidChannelFile.getAbsolutePath());
        Assert.assertEquals(ReturnCodes.PROCESSING_ERROR, exitCode);
        assertThat(getErrorOutput())
                .contains("Invalid channel definition");
    }

    private static Channel createChannel(String name, ChannelManifestCoordinate coord) throws MalformedURLException {
        return new Channel(name, "", null,
                List.of(new Repository("test", "http://test.org")),
                coord, null, null);
    }

    private static String toGav(MavenCoordinate coord) {
        final String ga = coord.getGroupId() + ":" + coord.getArtifactId();
        if (coord.getVersion() != null && !coord.getVersion().isEmpty()) {
            return ga + ":" + coord.getVersion();
        }
        return ga;
    }

}
