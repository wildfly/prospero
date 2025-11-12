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

package org.wildfly.prospero.api;

import org.jboss.galleon.Constants;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.MavenCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.installation.git.GitStorage;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.ProsperoConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.CURRENT_VERSION_FILE;
import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.METADATA_DIR;

@RunWith(MockitoJUnitRunner.class)
public class InstallationMetadataTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    @Mock
    private GitStorage gitStorage;
    private InstallationMetadata installationMetadata;
    private Path base;

    @Before
    public void setUp() throws Exception {
        base = temp.newFolder().toPath();
        installationMetadata = mockServer(base);
    }

    @Test
    public void testUpdateProsperoConfig() throws Exception {
        final ProsperoConfig config = installationMetadata.getProsperoConfig();
        Channel channel = createChannel(ArtifactUtils.manifestCoordFromString("new:channel"));
        config.getChannels().add(channel);

        installationMetadata.updateProsperoConfig(config);

        // verify new Channel and Repo in there
        final ProsperoConfig updatedConfig = installationMetadata.getProsperoConfig();
        assertEquals(2, updatedConfig.getChannels().size());
        assertEquals("new channel should be added in first place",
                new MavenCoordinate("new", "channel", null),
                updatedConfig.getChannels().get(1).getManifestCoordinate().getMaven());
        assertEquals("test", updatedConfig.getChannels().get(1).getRepositories().get(0).getId());
        assertEquals("file://foo.bar", updatedConfig.getChannels().get(1).getRepositories().get(0).getUrl());
        verify(gitStorage).recordConfigChange();
    }

    @Test
    public void dontOverrideProsperoConfigIfItExist() throws Exception {
        final ProsperoConfig config = installationMetadata.getProsperoConfig();
        Channel channel = createChannel(ArtifactUtils.manifestCoordFromString("new:channel"));
        config.getChannels().add(channel);

        installationMetadata.recordProvision(false);

        try (final InstallationMetadata im = InstallationMetadata.loadInstallation(base)) {
            final ProsperoConfig newConfig = im.getProsperoConfig();
            assertThat(newConfig.getChannels())
                .map(channel1 -> channel1.getManifestCoordinate().getMaven())
                .doesNotContain(
                    new MavenCoordinate("new", "channel", null)
            );
        }
    }

    @Test
    public void writeProsperoConfigIfItDoesNotExist() throws Exception {
        // throw away mocked installation from setup
        base = temp.newFolder().toPath();
        final Channel channel = createChannel(new ChannelManifestCoordinate("new", "channel"));
        installationMetadata = InstallationMetadata.newInstallation(base,
                new ChannelManifest(null, null, null, null),
                new ProsperoConfig(List.of(channel)),
                Optional.empty()
        );

        installationMetadata.recordProvision(false);

        try (final InstallationMetadata im = InstallationMetadata.loadInstallation(base)) {
            final ProsperoConfig newConfig = im.getProsperoConfig();
            assertEquals(1, newConfig.getChannels().size());
            assertEquals(new MavenCoordinate("new", "channel", null), newConfig.getChannels().get(0).getManifestCoordinate().getMaven());
            assertEquals("file://foo.bar", newConfig.getChannels().get(0).getRepositories().get(0).getUrl());
            assertEquals("test", newConfig.getChannels().get(0).getRepositories().get(0).getId());
        }
    }

    @Test
    public void initStorageIfItDoesNotExist() throws Exception {
        base = temp.newFolder().toPath();
        final Path metadataDir = base.resolve(ProsperoMetadataUtils.METADATA_DIR);

        Files.createDirectory(metadataDir);
        final ChannelManifest manifest = new ChannelManifest(null, null, null, Collections.emptyList());
        Files.writeString(metadataDir.resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME),
                ChannelManifestMapper.toYaml(manifest),
                StandardOpenOption.CREATE_NEW);
        final Channel channel = createChannel(new ChannelManifestCoordinate("foo","bar"));
        final Path configFilePath = ProsperoMetadataUtils.configurationPath(base);
        ProsperoMetadataUtils.writeChannelsConfiguration(configFilePath, List.of(channel));

        assertThat(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(".git")).doesNotExist();

        InstallationMetadata.loadInstallation(base);

        assertThat(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(".git")).exists();
    }

    @Test
    public void testWriteReadmeFile() throws Exception {
        // throw away mocked installation from setup
        base = temp.newFolder().toPath();
        final Channel channel = createChannel(new ChannelManifestCoordinate("new", "channel"));
        installationMetadata = InstallationMetadata.newInstallation(base,
                new ChannelManifest(null, null, null, null),
                new ProsperoConfig(List.of(channel)),
                Optional.empty()
        );

        installationMetadata.recordProvision(false);

        assertTrue("README.txt file should exist.", Files.exists(base.resolve(ProsperoMetadataUtils.METADATA_DIR)));
    }

    @Test
    public void testReadMavenOptions() throws Exception {
        final Channel channel = createChannel(new ChannelManifestCoordinate("new", "channel"));
        base = temp.newFolder().toPath();
        ChannelManifest manifest = new ChannelManifest(null, null, null, null);
        installationMetadata = InstallationMetadata.newInstallation(base, manifest, new ProsperoConfig(List.of(channel)),
                Optional.empty());
        installationMetadata.recordProvision(false);

        final MavenOptions opts = MavenOptions.builder()
                .setLocalCachePath(Path.of("foo"))
                .build();

        opts.write(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.MAVEN_OPTS_FILE));

        final InstallationMetadata readMetadata = InstallationMetadata.loadInstallation(base);
        assertEquals(Path.of("foo").toAbsolutePath(), readMetadata.getProsperoConfig().getMavenOptions().getLocalCache());
    }

    @Test
    public void testReadNoMavenOptions() throws Exception {
        final Channel channel = createChannel(new ChannelManifestCoordinate("new", "channel"));
        base = temp.newFolder().toPath();
        installationMetadata = InstallationMetadata.newInstallation(base,
                new ChannelManifest(null, null, null, null),
                new ProsperoConfig(List.of(channel), MavenOptions.builder()
                .setLocalCachePath(Path.of("foo"))
                .build()),
                Optional.empty()
        );

        installationMetadata.recordProvision(false);

        Files.deleteIfExists(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.MAVEN_OPTS_FILE));

        final InstallationMetadata readMetadata = InstallationMetadata.loadInstallation(base);
        assertNull(readMetadata.getProsperoConfig().getMavenOptions().getLocalCache());
    }

    @Test
    public void testLoadMetadataWithoutRecord() throws Exception {
        try (final InstallationMetadata metadata = InstallationMetadata.loadInstallation(base)) {
            assertTrue("Version record should be empty", metadata.getManifestVersions().isEmpty());
        }
    }

    @Test
    public void testLoadMetadataWithRecord() throws Exception {
        final Channel channel = createChannel(new ChannelManifestCoordinate("new", "channel"));
        base = temp.newFolder().toPath();
        final ManifestVersionRecord record = new ManifestVersionRecord();
        record.addManifest(new ManifestVersionRecord.MavenManifest("foo", "bar", "1.0.0"));
        installationMetadata = InstallationMetadata.newInstallation(base,
                new ChannelManifest(null, null, null, null),
                new ProsperoConfig(List.of(channel), MavenOptions.builder()
                        .setLocalCachePath(Path.of("foo"))
                        .build()),
                Optional.of(record)
        );

        installationMetadata.recordProvision(true);
        installationMetadata.close();

        installationMetadata = InstallationMetadata.loadInstallation(base);
        final Optional<ManifestVersionRecord> loadedRecord = installationMetadata.getManifestVersions();

        assertTrue("Manifest version record should be present", loadedRecord.isPresent());
        assertEquals(record.getSummary(), loadedRecord.get().getSummary());
    }

    @Test
    public void loadMetadataFromMissingArchive() throws Exception {
        assertThatThrownBy(()->InstallationMetadata.fromMetadataBundle(Paths.get("idontexist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRSP000252");
    }

    @Test
    public void loadMetadataFromDirectory() throws Exception {
        assertThatThrownBy(()->InstallationMetadata.fromMetadataBundle(temp.newFolder().toPath()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRSP000252");
    }

    @Test
    public void loadMetadataFromEmptyArchive() throws Exception {
        assertThatThrownBy(()->InstallationMetadata.fromMetadataBundle(temp.newFile().toPath()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRSP000220");
    }

    @Test
    public void testLoadMetadataWithProvisioningRecord() throws Exception {
        Files.writeString(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML),
                "<installation xmlns=\"urn:jboss:galleon:provisioning:3.0\"><feature-pack location=\"org.wildfly:wildfly-galleon-pack:zip\"/></installation>");
        try (final InstallationMetadata metadata = InstallationMetadata.loadInstallation(base)) {
            final GalleonProvisioningConfig recordedProvisioningConfig = metadata.getRecordedProvisioningConfig();
            assertNotNull(recordedProvisioningConfig);
            assertEquals("org.wildfly:wildfly-galleon-pack:zip",
                    recordedProvisioningConfig.getFeaturePackDeps().stream().findFirst().get()
                            .getLocation().toString());
        }
    }

    @Test
    public void updateProvisioningConfiguration_PersistIfFileDoesntExist() throws Exception {
        Files.createDirectory(base.resolve(Constants.PROVISIONED_STATE_DIR));
        Files.writeString(base.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONING_XML),
                "<installation xmlns=\"urn:jboss:galleon:provisioning:3.0\"><feature-pack location=\"org.wildfly:wildfly-galleon-pack:zip\"/></installation>");
        installationMetadata.updateProvisioningConfiguration();

        assertThat(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML))
                .exists();

        verify(gitStorage).recordChange(SavedState.Type.INTERNAL_UPDATE, ProsperoMetadataUtils.PROVISIONING_RECORD_XML);
    }

    @Test
    public void updateProvisioningConfiguration_DoNothingIfFileExist() throws Exception {
        Files.writeString(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML),
                "<installation xmlns=\"urn:jboss:galleon:provisioning:3.0\"><feature-pack location=\"org.wildfly:wildfly-galleon-pack:zip\"/></installation>");
        Files.createDirectory(base.resolve(Constants.PROVISIONED_STATE_DIR));
        Files.writeString(base.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONING_XML),
                "<installation xmlns=\"urn:jboss:galleon:provisioning:3.0\"><feature-pack location=\"org.wildfly:changed:zip\"/></installation>");
        try (final InstallationMetadata metadata = InstallationMetadata.loadInstallation(base)) {
            metadata.updateProvisioningConfiguration();

            assertThat(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML))
                    .exists()
                    .hasContent("<installation xmlns=\"urn:jboss:galleon:provisioning:3.0\"><feature-pack location=\"org.wildfly:wildfly-galleon-pack:zip\"/></installation>");
        }
    }

    @Test
    public void getUnknownChannelVersionIfNoVersionIsAvailable() throws Exception {
        try (final InstallationMetadata metadata = InstallationMetadata.loadInstallation(base)) {
            assertThat(metadata.getChannelVersions())
                    .contains(new ChannelVersion.Builder()
                            .setChannelName("test")
                            .setType(ChannelVersion.Type.MAVEN)
                            .setLocation("foo:bar")
                            .build());
        }
    }

    @Test
    public void getFullChannelVersionIfVersionIsAvailable() throws Exception {
        final ManifestVersionRecord manifestVersionRecord = new ManifestVersionRecord("1.0.0",
            List.of(new ManifestVersionRecord.MavenManifest("foo", "bar", "1.0.0", "Update 0")),
            Collections.emptyList(),
            Collections.emptyList()
        );
        ProsperoMetadataUtils.writeVersionRecord(base.resolve(METADATA_DIR).resolve(CURRENT_VERSION_FILE), manifestVersionRecord);

        try (final InstallationMetadata metadata = InstallationMetadata.loadInstallation(base)) {
            assertThat(metadata.getChannelVersions())
                    .contains(new ChannelVersion.Builder()
                            .setChannelName("test")
                            .setType(ChannelVersion.Type.MAVEN)
                            .setLocation("foo:bar")
                            .setPhysicalVersion("1.0.0")
                            .setLogicalVersion("Update 0")
                            .build());
        }
    }

    private static Channel createChannel(ChannelManifestCoordinate manifestCoordinate) {
        Channel channel = new Channel("test", null, null,
                List.of(new Repository("test", "file://foo.bar")),
                manifestCoordinate, null, null);
        return channel;
    }

    private InstallationMetadata mockServer(Path base) throws IOException, MetadataException {
        final Path metadataDir = base.resolve(ProsperoMetadataUtils.METADATA_DIR);

        Files.createDirectory(metadataDir);
        final ChannelManifest manifest = new ChannelManifest(null, null, null, Collections.emptyList());
        final Channel channel = createChannel(new ChannelManifestCoordinate("foo","bar"));
        final ProsperoConfig prosperoConfig = new ProsperoConfig(List.of(channel));
        final InstallationMetadata metadata = new InstallationMetadata(base, manifest, prosperoConfig, gitStorage,
                Optional.empty(), null);
        metadata.recordProvision(true);
        return metadata;
    }

}