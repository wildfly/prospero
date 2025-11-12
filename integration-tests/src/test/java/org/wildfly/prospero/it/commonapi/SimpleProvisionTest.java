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

package org.wildfly.prospero.it.commonapi;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.util.PathsUtils;
import org.junit.Test;
import org.wildfly.channel.Channel;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.ArtifactCache;
import org.wildfly.prospero.it.AcceptingConsole;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.ManifestYamlSupport;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.test.MetadataTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.galleon.Constants.HASHES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class SimpleProvisionTest extends WfCoreTestBase {

    @Test
    public void installWildflyCore() throws Exception {
        final Path channelsFile = MetadataTestUtils.prepareChannel(CHANNEL_BASE_CORE_19);

        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        // verify installation with manifest file is present
        assertTrue(manifestPath.toFile().exists());
        // verify manifest contains versions 17.0.1
        final Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-cli");
        assertEquals(BASE_VERSION, wildflyCliArtifact.get().getVersion());
        // verify the galleon pack has been added
        assertThat(Files.readAllLines(hashesPath()))
                .contains("wildfly-core-galleon-pack-" + BASE_VERSION + "-channel.zip");

        // verify the URL of the manifest was recorded
        final Path manifestVersionsFile = outputPath.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.CURRENT_VERSION_FILE);
        final Optional<ManifestVersionRecord> record = ManifestVersionRecord.read(manifestVersionsFile);
        assertTrue("Manifest version record should be present", record.isPresent());
        assertThat(record.get().getUrlManifests())
                .map(ManifestVersionRecord.UrlManifest::getUrl)
                .containsExactly(MetadataTestUtils.class.getClassLoader().getResource(CHANNEL_BASE_CORE_19).toExternalForm());

        // verify the provisioning.xml was recorded in the .installation folder
        final Path provisioningRecordFile = outputPath.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML);
        assertThat(provisioningRecordFile)
                .hasSameTextualContentAs(PathsUtils.getProvisioningXml(outputPath));
        // verify the cache dir always uses linux file separator
        assertThat(Files.readString(provisioningRecordFile))
                .contains(String.format("<option name=\"jboss-resolved-artifacts-cache\" value=\"%s/%s\"/>", ProsperoMetadataUtils.METADATA_DIR, ".cache"));
    }

    @Test
    public void installWildflyCore_ChannelsWithEmptyNamesAreNamed() throws Exception {
        final Path channelsFile = MetadataTestUtils.prepareChannel(CHANNEL_BASE_CORE_19);

        // make sure the channel names are empty
        List<Channel> emptyNameChannels = MetadataTestUtils.readChannels(channelsFile).stream()
                .map(c -> new Channel(c.getSchemaVersion(), null, null, null, c.getRepositories(),
                        c.getManifestCoordinate(), null, null)).collect(Collectors.toList());
        MetadataTestUtils.writeChannels(channelsFile, emptyNameChannels);

        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();

        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        final ProsperoConfig persistedConfig = ProsperoConfig.readConfig(outputPath.resolve(ProsperoMetadataUtils.METADATA_DIR));
        assertThat(persistedConfig.getChannels())
                .map(Channel::getName)
                .noneMatch(StringUtils::isEmpty)
                .doesNotHaveDuplicates();
    }

    @Test
    public void updateWildflyCore() throws Exception {
        final Path channelsFile = MetadataTestUtils.prepareChannel(CHANNEL_BASE_CORE_19);

        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        MetadataTestUtils.prepareChannel(outputPath.resolve(MetadataTestUtils.INSTALLER_CHANNELS_FILE_PATH),
                defaultRemoteRepositories(),
                CHANNEL_COMPONENT_UPDATES, CHANNEL_BASE_CORE_19);
        getUpdateAction().performUpdate();

        // verify manifest contains versions 17.0.1
        final Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-cli");
        assertEquals(UPGRADE_VERSION, wildflyCliArtifact.get().getVersion());
    }

    @Test
    public void updateWildflyCoreFp() throws Exception {
        final Path channelsFile = MetadataTestUtils.prepareChannel(CHANNEL_BASE_CORE_19);

        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        MetadataTestUtils.prepareChannel(outputPath.resolve(MetadataTestUtils.INSTALLER_CHANNELS_FILE_PATH),
                defaultRemoteRepositories(),
                CHANNEL_FP_UPDATES, CHANNEL_BASE_CORE_19);
        getUpdateAction().performUpdate();

        // verify manifest contains versions 17.0.1
        final Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-core-galleon-pack");
        assertEquals(UPGRADE_VERSION, wildflyCliArtifact.get().getVersion());
        // verify the galleon pack has been added
        assertThat(Files.readAllLines(hashesPath()))
                .contains("wildfly-core-galleon-pack-" + UPGRADE_VERSION + ".zip");
    }

    @Test
    public void updateWildflyCoreFp_InstalledWithGAV() throws Exception {
        final Path channelsFile = MetadataTestUtils.prepareChannel(CHANNEL_BASE_CORE_19);

        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setFpl("org.wildfly.core:wildfly-core-galleon-pack")
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        MetadataTestUtils.prepareChannel(outputPath.resolve(MetadataTestUtils.INSTALLER_CHANNELS_FILE_PATH),
                defaultRemoteRepositories(),
                CHANNEL_FP_UPDATES, CHANNEL_BASE_CORE_19);
        getUpdateAction().performUpdate();

        // verify manifest contains versions 17.0.1
        final Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-core-galleon-pack");
        assertEquals(UPGRADE_VERSION, wildflyCliArtifact.get().getVersion());
    }

    @Test
    public void updateWildflyCoreDryRun() throws Exception {
        final Path channelsFile = MetadataTestUtils.prepareChannel(CHANNEL_BASE_CORE_19);

        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        MetadataTestUtils.prepareChannel(outputPath.resolve(MetadataTestUtils.INSTALLER_CHANNELS_FILE_PATH), CHANNEL_COMPONENT_UPDATES, CHANNEL_BASE_CORE_19);
        final Set<String> updates = new UpdateAction(outputPath, Collections.emptyList(), mavenOptions, new AcceptingConsole())
                .findUpdates().getArtifactUpdates().stream()
                .map(ArtifactChange::getArtifactName)
                .collect(Collectors.toSet());

        // verify manifest contains versions 17.0.1
        final Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-cli");
        assertEquals(BASE_VERSION, wildflyCliArtifact.get().getVersion());
        assertEquals(1, updates.size());
        assertEquals("org.wildfly.core:wildfly-cli", updates.stream().findFirst().get());
    }

    @Test
    public void installWithShadowRepositories() throws Exception {
        // verify that using override repositories changes the source during provisioning, but is not recorded
        final Path channelsFile = MetadataTestUtils.prepareChannel(CHANNEL_BASE_CORE_19);

        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();
        final List<Channel> channels = provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY).stream()
                .map((c)-> {
                    Channel.Builder builder = new Channel.Builder()
                            .setName(c.getName())
                            .setManifestUrl(c.getManifestCoordinate().getUrl());
                    for (Repository r : c.getRepositories()) {
                        builder.addRepository(r.getId(), "https://idontexist.com");
                    }
                    return builder.build();
                })
                .collect(Collectors.toList());

        installation.provision(provisioningDefinition.toProvisioningConfig(),
                channels, defaultRemoteRepositories().stream()
                        .map(r->new Repository(r.getId(), r.getUrl())).collect(Collectors.toList()));

        assertThat(InstallationMetadata.loadInstallation(outputPath).getProsperoConfig().getChannels())
                .flatMap(Channel::getRepositories)
                .map(Repository::getUrl)
                .contains("https://idontexist.com");
    }

    private Path hashesPath() {
        return outputPath.resolve(Constants.PROVISIONED_STATE_DIR).resolve(HASHES).resolve(ArtifactCache.CACHE_FOLDER).resolve(HASHES);
    }

    @Test
    public void installWildflyCoreFromInstallationFile() throws Exception {
        final Path channelsFile = temp.newFile("channels.yaml").toPath();
        MetadataTestUtils.prepareChannel(channelsFile, defaultRemoteRepositories(), CHANNEL_BASE_CORE_19);
        final URI installationFile = this.getClass().getClassLoader().getResource("provisioning.xml").toURI();

        ProvisioningDefinition definition = ProvisioningDefinition.builder()
                .setDefinitionFile(installationFile)
                .setChannelCoordinates(List.of(channelsFile.toString()))
                .build();

        installation.provision(definition.toProvisioningConfig(), definition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        final Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-cli");
        assertEquals(BASE_VERSION, wildflyCliArtifact.get().getVersion());
    }

    private UpdateAction getUpdateAction() throws ProvisioningException, OperationException {
        return new UpdateAction(outputPath, Collections.emptyList(), mavenOptions, new AcceptingConsole());
    }

    private Optional<Artifact> readArtifactFromManifest(String groupId, String artifactId) throws IOException, MetadataException {
        final File manifestFile = manifestPath.toFile();
        return ManifestYamlSupport.parse(manifestFile).getStreams().stream()
                .filter((a) -> a.getGroupId().equals(groupId) && a.getArtifactId().equals(artifactId))
                .findFirst()
                .map(s->new DefaultArtifact(s.getGroupId(), s.getArtifactId(), "jar", s.getVersion()));
    }

}
