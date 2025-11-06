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

package org.wildfly.prospero.updates;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.galleon.util.HashUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.wildfly.channel.ArtifactTransferException;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.RuntimeChannel;
import org.wildfly.channel.VersionResult;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.ChannelVersionChange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

@SuppressWarnings("OptionalGetWithoutIsPresent")
@RunWith(MockitoJUnitRunner.class)
public class UpdateFinderTest {

    @Mock
    ChannelSession channelSession;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testDowngradeIsPossible() throws Exception {
        when(channelSession.findLatestMavenArtifactVersion("org.foo", "bar", "jar", "", null))
                .thenReturn(new VersionResult("1.0.0", null));

        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Arrays.asList(
                new DefaultArtifact("org.foo", "bar", "jar", "1.0.1")
                );
        final UpdateSet updates = finder.findUpdates(artifacts, Collections.emptyList());

        assertEquals(1, updates.getArtifactUpdates().size());
        final ArtifactChange actualChange = updates.getArtifactUpdates().get(0);
        assertEquals("org.foo:bar", actualChange.getArtifactName());
        assertEquals("1.0.0", actualChange.getNewVersion().get());
        assertEquals("1.0.1", actualChange.getOldVersion().get());
        assertEquals(Optional.empty(), actualChange.getChannelName());
    }

    @Test
    public void testExcludeSameVersion() throws Exception {
        when(channelSession.findLatestMavenArtifactVersion("org.foo", "bar", "jar", "", null))
                .thenReturn(new VersionResult("1.0.0", null));

        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Arrays.asList(
                new DefaultArtifact("org.foo", "bar", "jar", "1.0.0")
        );
        final UpdateSet updates = finder.findUpdates(artifacts, Collections.emptyList());

        assertEquals(0, updates.getArtifactUpdates().size());
    }

    @Test
    public void testIncludeUpgradeVersion() throws Exception {
        when(channelSession.findLatestMavenArtifactVersion("org.foo", "bar", "jar", "", null))
                .thenReturn(new VersionResult("1.0.1", null));

        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Arrays.asList(
                new DefaultArtifact("org.foo", "bar", "jar", "1.0.0")
        );
        final UpdateSet updates = finder.findUpdates(artifacts, Collections.emptyList());

        assertEquals(1, updates.getArtifactUpdates().size());
        assertEquals("org.foo:bar", updates.getArtifactUpdates().get(0).getArtifactName());
        assertEquals("1.0.1", updates.getArtifactUpdates().get(0).getNewVersion().get());
        assertEquals("1.0.0", updates.getArtifactUpdates().get(0).getOldVersion().get());
    }

    @Test
    public void testRemoval() throws Exception {
        when(channelSession.findLatestMavenArtifactVersion("org.foo", "bar", "jar", "", null))
                .thenThrow(new ArtifactTransferException("Exception", Collections.emptySet(), Collections.emptySet()));

        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Arrays.asList(
                new DefaultArtifact("org.foo", "bar", "jar", "1.0.0")
        );
        final UpdateSet updates = finder.findUpdates(artifacts, Collections.emptyList());

        assertEquals(1, updates.getArtifactUpdates().size());
        assertEquals("org.foo:bar", updates.getArtifactUpdates().get(0).getArtifactName());
        assertEquals(Optional.empty(), updates.getArtifactUpdates().get(0).getNewVersion());
        assertEquals("1.0.0", updates.getArtifactUpdates().get(0).getOldVersion().get());
    }

    @Test
    public void findUpdatesIncludesChannelNames() throws Exception {
        when(channelSession.findLatestMavenArtifactVersion("org.foo", "bar", "jar", "", null))
                .thenReturn(new VersionResult("1.0.0", "test-channel"));

        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Arrays.asList(
                new DefaultArtifact("org.foo", "bar", "jar", "1.0.1")
        );
        final UpdateSet updates = finder.findUpdates(artifacts, Collections.emptyList());

        assertEquals(1, updates.getArtifactUpdates().size());
        final ArtifactChange actualUpdate = updates.getArtifactUpdates().get(0);
        assertEquals("org.foo:bar", actualUpdate.getArtifactName());
        assertEquals("1.0.0", actualUpdate.getNewVersion().get());
        assertEquals("1.0.1", actualUpdate.getOldVersion().get());
        assertEquals("test-channel", actualUpdate.getChannelName().orElse(null));
    }

    @Test
    public void listChannelVersionChangesWithMavenChannel() throws Exception {
        final ChannelManifest.Builder manifestBuilder = new ChannelManifest.Builder();
        manifestBuilder.setLogicalVersion("Update 1");
        when(channelSession.getRuntimeChannels())
                .thenReturn(List.of(new RuntimeChannel(
                        new Channel.Builder()
                                .setName("test-channel")
                                .setManifestCoordinate("t", "c", "1.0.1")
                                .build(),
                        manifestBuilder
                                .build(),
                        null
                )));
        List<ChannelVersion> currentVersions = List.of(new ChannelVersion.Builder()
                .setChannelName("test-channel")
                .setPhysicalVersion("1.0.0")
                .setType(ChannelVersion.Type.MAVEN)
                .setLocation("t:c")
                .setLogicalVersion("Update 0")
                .build());
        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Collections.emptyList();
        final UpdateSet updates = finder.findUpdates(artifacts, currentVersions);

        assertThat(updates.getChannelVersionChanges())
                .contains(new ChannelVersionChange("test-channel",
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setLocation("t:c")
                                .setPhysicalVersion("1.0.0")
                                .setLogicalVersion( "Update 0")
                                .setType(ChannelVersion.Type.MAVEN)
                                .build(),
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setLocation("t:c")
                                .setPhysicalVersion("1.0.1")
                                .setLogicalVersion( "Update 1")
                                .setType(ChannelVersion.Type.MAVEN)
                                .build()
                ));
    }

    @Test
    public void listChannelVersionChangesWithUrlChannel() throws Exception {
        final ChannelManifest.Builder manifestBuilder = new ChannelManifest.Builder();
        manifestBuilder.setLogicalVersion("Update 1");

        final Path manifestTwo = tempFolder.newFile("test-manifest").toPath();
        final String manifestUrl = manifestTwo.toUri().toURL().toExternalForm();
        Files.writeString(manifestTwo, ChannelManifestMapper.toYaml(manifestBuilder.build()));
        final String hash = HashUtils.hash(Files.readString(manifestTwo));

        when(channelSession.getRuntimeChannels())
                .thenReturn(List.of(new RuntimeChannel(
                        new Channel.Builder()
                                .setName("test-channel")
                                .setManifestUrl(manifestTwo.toUri().toURL())
                                .build(),
                        manifestBuilder
                                .build(),
                        null
                )));
        List<ChannelVersion> currentVersions = List.of(new ChannelVersion.Builder()
                        .setChannelName("test-channel")
                        .setPhysicalVersion("abcd")
                        .setType(ChannelVersion.Type.URL)
                        .setLocation(manifestUrl)
                        .setLogicalVersion("Update 0")
                .build());
        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Collections.emptyList();
        final UpdateSet updates = finder.findUpdates(artifacts, currentVersions);

        assertThat(updates.getChannelVersionChanges())
                .contains(new ChannelVersionChange("test-channel",
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setLocation(manifestUrl)
                                .setPhysicalVersion("abcd")
                                .setLogicalVersion("Update 0")
                                .setType(ChannelVersion.Type.URL)
                                .build(),
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setLocation(manifestUrl)
                                .setPhysicalVersion(hash)
                                .setLogicalVersion("Update 1")
                                .setType(ChannelVersion.Type.URL)
                                .build())
                );
    }

    @Test
    public void listChannelVersionChangesOpenChannel() throws Exception {
        when(channelSession.getRuntimeChannels())
                .thenReturn(List.of(new RuntimeChannel(
                        new Channel.Builder()
                                .setName("test-channel")
                                .setResolveStrategy(Channel.NoStreamStrategy.LATEST)
                                .addRepository("central", "http://repo")
                                .build(),
                        new ChannelManifest.Builder()
                                .build(),
                        null
                )));
        List<ChannelVersion> currentVersions = List.of(new ChannelVersion.Builder()
                .setChannelName("test-channel")
                .setType(ChannelVersion.Type.OPEN)
                .setLocation("latest@[central]")
                .build());
        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Collections.emptyList();
        final UpdateSet updates = finder.findUpdates(artifacts, currentVersions);

        assertThat(updates.getChannelVersionChanges())
                .contains(new ChannelVersionChange("test-channel",
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setType(ChannelVersion.Type.OPEN)
                                .setLocation("latest@[central]")
                                .build(),
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setType(ChannelVersion.Type.OPEN)
                                .setLocation("latest@[central]")
                                .build())
                );
    }

    @Test
    public void listChannelVersionChangesWithMavenChannelNoHistory() throws Exception {
        final ChannelManifest.Builder manifestBuilder = new ChannelManifest.Builder();
        manifestBuilder.setLogicalVersion("Update 1");
        when(channelSession.getRuntimeChannels())
                .thenReturn(List.of(new RuntimeChannel(
                        new Channel.Builder()
                                .setName("test-channel")
                                .setManifestCoordinate("t", "c", "1.0.1")
                                .build(),
                        manifestBuilder
                                .build(),
                        null
                )));
        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Collections.emptyList();
        final UpdateSet updates = finder.findUpdates(artifacts, Collections.emptyList());

        assertThat(updates.getChannelVersionChanges())
                .contains(new ChannelVersionChange("test-channel",
                        null,
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setLocation("t:c")
                                .setPhysicalVersion("1.0.1")
                                .setLogicalVersion( "Update 1")
                                .setType(ChannelVersion.Type.MAVEN)
                                .build()
                ));
    }

    @Test
    public void listChannelVersionChangesWithMavenChannelRemoved() throws Exception {
        final ChannelManifest.Builder manifestBuilder = new ChannelManifest.Builder();
        manifestBuilder.setLogicalVersion("Update 1");
        when(channelSession.getRuntimeChannels())
                .thenReturn(Collections.emptyList());
        UpdateFinder finder = new UpdateFinder(channelSession);
        final List<Artifact> artifacts = Collections.emptyList();
        List<ChannelVersion> currentVersions = List.of(new ChannelVersion.Builder()
                .setChannelName("test-channel")
                .setPhysicalVersion("1.0.0")
                .setType(ChannelVersion.Type.MAVEN)
                .setLocation("t:c")
                .setLogicalVersion("Update 0")
                .build());
        final UpdateSet updates = finder.findUpdates(artifacts, currentVersions);

        assertThat(updates.getChannelVersionChanges())
                .contains(new ChannelVersionChange("test-channel",
                        new ChannelVersion.Builder()
                                .setChannelName("test-channel")
                                .setLocation("t:c")
                                .setPhysicalVersion("1.0.0")
                                .setLogicalVersion( "Update 0")
                                .setType(ChannelVersion.Type.MAVEN)
                                .build(),
                        null
                ));
    }
}