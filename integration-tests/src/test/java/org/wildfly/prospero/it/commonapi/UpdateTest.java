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

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.jboss.galleon.ProvisioningException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.Repository;
import org.wildfly.channel.Stream;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.it.AcceptingConsole;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.ManifestYamlSupport;
import org.wildfly.prospero.test.MetadataTestUtils;
import org.wildfly.prospero.updates.CandidateProperties;
import org.wildfly.prospero.updates.CandidatePropertiesParser;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.wildfly.prospero.updates.MarkerFile.UPDATE_MARKER_FILE;

public class UpdateTest extends WfCoreTestBase {

    private File mockRepo;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mockRepo = temp.newFolder("repo");
    }

    @After
    public void tearDown() throws Exception {
        if (mockRepo.exists()) {
            FileUtils.deleteQuietly(mockRepo);
        }
    }

    @Test
    public void updateWildflyCoreWithNewChannel() throws Exception {
        // deploy manifest file
        File manifestFile = new File(MetadataTestUtils.class.getClassLoader().getResource(CHANNEL_BASE_CORE_19).toURI());
        deployManifestFile(mockRepo.toURI().toURL(), manifestFile, "1.0.0");

        // provision using channel gav
        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(buildConfigWithMockRepo().toPath().toString())
                .setOverrideRepositories(Collections.emptyList()) // reset overrides from defaultWfCoreDefinition()
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-cli");
        assertEquals(BASE_VERSION, wildflyCliArtifact.get().getVersion());

        // update manifest file
        final File updatedManifest = upgradeTestArtifactIn(manifestFile);
        deployManifestFile(mockRepo.toURI().toURL(), updatedManifest, "1.0.1");

        // update installation
        new UpdateAction(outputPath, Collections.emptyList(), mavenOptions, new AcceptingConsole())
                .performUpdate();

        wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-cli");
        assertEquals(UPGRADE_VERSION, wildflyCliArtifact.get().getVersion());
    }

    @Test
    public void updateWildflyCoreIgnoreChangesInProsperoConfig() throws Exception {
        final Path channelsFile = outputPath.resolve(ProsperoMetadataUtils.METADATA_DIR)
                .resolve(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME);

        // deploy manifest file
        File manifestFile = new File(MetadataTestUtils.class.getClassLoader().getResource(CHANNEL_BASE_CORE_19).toURI());
        deployManifestFile(mockRepo.toURI().toURL(), manifestFile, "1.0.0");

        // provision using manifest gav
        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(buildConfigWithMockRepo().toPath().toString())
                .setOverrideRepositories(Collections.emptyList()) // reset overrides from defaultWfCoreDefinition()
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        Files.writeString(channelsFile, "# test comment", StandardOpenOption.APPEND);

        // update manifest file
        final File updatedChannel = upgradeTestArtifactIn(manifestFile);
        deployManifestFile(mockRepo.toURI().toURL(), updatedChannel, "1.0.1");

        // update installation
        new UpdateAction(outputPath, Collections.emptyList(), mavenOptions, new AcceptingConsole())
                .performUpdate();

        assertTrue(Files.readString(channelsFile).contains("# test comment"));
    }

    @Test
    public void prepareUpdateCreatesMarkerFile() throws Exception {
        // deploy manifest file
        File manifestFile = new File(MetadataTestUtils.class.getClassLoader().getResource(CHANNEL_BASE_CORE_19).toURI());
        deployManifestFile(mockRepo.toURI().toURL(), manifestFile, "1.0.0");

        // provision using manifest gav
        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(buildConfigWithMockRepo().toPath().toString())
                .setOverrideRepositories(Collections.emptyList()) // reset overrides from defaultWfCoreDefinition()
                .build();
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY));

        // update manifest file
        final File updatedChannel = upgradeTestArtifactIn(manifestFile);
        deployManifestFile(mockRepo.toURI().toURL(), updatedChannel, "1.0.1");

        // update installation
        final Path preparedUpdatePath = temp.newFolder().toPath();
        new UpdateAction(outputPath, Collections.emptyList(), mavenOptions, new AcceptingConsole())
                .buildUpdate(preparedUpdatePath);

        final Path markerFile = preparedUpdatePath.resolve(UPDATE_MARKER_FILE);
        assertTrue(Files.exists(markerFile));

        // check that the generated manifest version record contains updated version
        final Path manifestVersionsFile = preparedUpdatePath.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.CURRENT_VERSION_FILE);
        final Optional<ManifestVersionRecord> record = ManifestVersionRecord.read(manifestVersionsFile);
        assertTrue("Manifest version record should be present", record.isPresent());
        assertThat(record.get().getMavenManifests())
                .map(ManifestVersionRecord.MavenManifest::getVersion)
                .containsExactly("1.0.1");

        final Path channelNamesFile = preparedUpdatePath.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ApplyCandidateAction.CANDIDATE_CHANNEL_NAME_LIST);
        assertTrue(Files.exists(channelNamesFile));

        final CandidateProperties candidateProperties = CandidatePropertiesParser.read(channelNamesFile);
        assertEquals("test", candidateProperties.getUpdateChannel("org.wildfly.core:wildfly-cli"));
    }

    @Test
    public void updateRequiresOnlyChangedArtifacts() throws Exception {
        // deploy manifest file
        File manifestFile = new File(MetadataTestUtils.class.getClassLoader().getResource(CHANNEL_BASE_CORE_19).toURI());
        deployManifestFile(mockRepo.toURI().toURL(), manifestFile, "1.0.0");

        // provision using manifest gav
        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(buildConfigWithMockRepo().toPath().toString())
                .setOverrideRepositories(Collections.emptyList()) // reset overrides from defaultWfCoreDefinition()
                .build();
        final List<Channel> channels = provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY);
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                channels);

        // update manifest file
        final URL tempRepo = mockTemporaryRepo(true);
        final File updatedChannel = upgradeTestArtifactIn(manifestFile);
        deployManifestFile(tempRepo, updatedChannel, "1.0.1");

        // offline MSM will disable http(s) repositories and local maven cache
        final MavenOptions offlineOptions = MavenOptions.OFFLINE_NO_CACHE;

        // update installation
        final List<Channel> overrideChannels = channels.stream()
                .map(c -> new Channel.Builder(c)
                        .setRepositories(List.of(new Repository("temp", tempRepo.toExternalForm())))
                        .build())
                .collect(Collectors.toList());
        try (UpdateAction ua = new UpdateAction(outputPath, overrideChannels, offlineOptions, new AcceptingConsole())) {
            ua.performUpdate();
        }

        Optional<Artifact> wildflyCliArtifact = readArtifactFromManifest("org.wildfly.core", "wildfly-cli");
        assertEquals(UPGRADE_VERSION, wildflyCliArtifact.get().getVersion());
    }

    @Test
    public void candidateFolderHasToBeEmpty() throws Exception {
        // deploy manifest file
        File manifestFile = new File(MetadataTestUtils.class.getClassLoader().getResource(CHANNEL_BASE_CORE_19).toURI());
        deployManifestFile(mockRepo.toURI().toURL(), manifestFile, "1.0.0");

        // provision using manifest gav
        final ProvisioningDefinition provisioningDefinition = defaultWfCoreDefinition()
                .setChannelCoordinates(buildConfigWithMockRepo().toPath().toString())
                .setOverrideRepositories(Collections.emptyList()) // reset overrides from defaultWfCoreDefinition()
                .build();
        final List<Channel> channels = provisioningDefinition.resolveChannels(CHANNELS_RESOLVER_FACTORY);
        installation.provision(provisioningDefinition.toProvisioningConfig(),
                channels);

        // update manifest file
        final URL tempRepo = mockTemporaryRepo(true);
        final File updatedManifest = upgradeTestArtifactIn(manifestFile);
        deployManifestFile(tempRepo, updatedManifest, "1.0.1");

        // offline MSM will disable http(s) repositories and local maven cache
        final MavenOptions offlineOptions = MavenOptions.OFFLINE_NO_CACHE;

        // update installation
        final Path candidateFolder = temp.newFolder("candidate").toPath();
        Files.writeString(candidateFolder.resolve("dirty.txt"), "foobar");

        final List<Channel> overrideChannels = channels.stream()
                .map(c -> new Channel.Builder(c)
                        .setRepositories(List.of(new Repository("temp", tempRepo.toExternalForm())))
                        .build())
                .collect(Collectors.toList());

        assertThatThrownBy(
                () -> {
                    try (UpdateAction ua = new UpdateAction(outputPath, overrideChannels, offlineOptions, new AcceptingConsole())) {
                        ua.buildUpdate(candidateFolder);
                    }
                })
                .isInstanceOf(IllegalArgumentException.class)
                .message().contains("Can't install the server into a non empty directory");
    }

    private File upgradeTestArtifactIn(File manifestFile) throws IOException, MetadataException {
        final ChannelManifest manifest = ManifestYamlSupport.parse(manifestFile);
        final List<Stream> streams = manifest.getStreams().stream().map(s -> {
            if (s.getGroupId().equals("org.wildfly.core") && s.getArtifactId().equals("wildfly-cli")) {
                return new Stream(s.getGroupId(), s.getArtifactId(), UPGRADE_VERSION);
            }
            return s;
        }).collect(Collectors.toList());

        final File file = temp.newFile("test-channel.yaml");
        ChannelManifest manifest1 = new ChannelManifest(manifest.getSchemaVersion(), manifest.getName(), null, streams);
        Path manifestPath1 = file.toPath();
        ProsperoMetadataUtils.writeManifest(manifestPath1, manifest1);
        return file;
    }

    private File buildConfigWithMockRepo() throws IOException {
        final List<Repository> repositories = new ArrayList<>(defaultRemoteRepositories());
        final File channelsFile = temp.newFile(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME);
        repositories.add(new Repository("test-repo", mockRepo.toURI().toURL().toString()));
        Channel channel = new Channel("test", "", null, repositories,
                new ChannelManifestCoordinate("test", "channel"), null, null);
        Files.writeString(channelsFile.toPath(), ChannelMapper.toYaml(List.of(channel)), StandardCharsets.UTF_8);
        return channelsFile;
    }

    private void deployManifestFile(URL repoUrl, File channelFile, String version) throws ProvisioningException, MalformedURLException, DeploymentException {
        final MavenSessionManager msm = new MavenSessionManager(MavenOptions.OFFLINE_NO_CACHE);
        final RepositorySystem system = msm.newRepositorySystem();
        final DefaultRepositorySystemSession session = msm.newRepositorySystemSession(system);
        final DeployRequest request = new DeployRequest();
        request.setRepository(RepositoryUtils.toRemoteRepository("test-repo", repoUrl.toExternalForm()));
        request.setArtifacts(Arrays.asList(new DefaultArtifact("test", "channel",
                ChannelManifest.CLASSIFIER, ChannelManifest.EXTENSION, version,
                null, channelFile)));
        system.deploy(session, request);
    }

    private Optional<Artifact> readArtifactFromManifest(String groupId, String artifactId) throws IOException, MetadataException {
        final File manifestFile = manifestPath.toFile();
        return ManifestYamlSupport.parse(manifestFile).getStreams().stream()
                .filter((a) -> a.getGroupId().equals(groupId) && a.getArtifactId().equals(artifactId))
                .findFirst()
                .map(s->new DefaultArtifact(s.getGroupId(), s.getArtifactId(), "jar", s.getVersion()));
    }

}
