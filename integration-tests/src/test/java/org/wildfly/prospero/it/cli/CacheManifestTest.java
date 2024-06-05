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

package org.wildfly.prospero.it.cli;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeploymentException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ProvisioningConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ChannelMetadataCoordinate;
import org.wildfly.channel.Stream;
import org.wildfly.prospero.actions.InstallationHistoryAction;
import org.wildfly.prospero.actions.MetadataAction;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.api.exceptions.UnresolvedChannelMetadataException;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.galleon.ArtifactCache;
import org.wildfly.prospero.it.commonapi.WfCoreTestBase;
import org.wildfly.prospero.it.utils.TestRepositoryUtils;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.test.MetadataTestUtils;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CacheManifestTest extends WfCoreTestBase {

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();
    private ProvisioningConfig baseProvCfg;
    private Channel baseChannel;
    private File baseManifest;
    private TestRepositoryUtils repositoryUtils;
    private ChannelManifest secondManifest;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        final ProvisioningDefinition.Builder provDef = defaultWfCoreDefinition();
        provDef.setManifest("org.test.channels:wf-core-base");
        baseProvCfg = provDef.build().toProvisioningConfig();

        // create channel with default repos and manifest GA
        final Channel.Builder builder = new Channel.Builder();
        defaultRemoteRepositories().forEach(r->builder.addRepository(r.getId(), r.getUrl()));
        builder.setManifestCoordinate(new ChannelManifestCoordinate("org.test.channels", "wf-core-base"));
        baseChannel = builder.build();
        baseManifest = Path.of(MetadataTestUtils.class.getClassLoader().getResource("manifests/wfcore-base.yaml").toURI()).toFile();

        // deploy base manifest
        repositoryUtils = new TestRepositoryUtils(new URI(updateRepository.getUrl()).toURL());
        repositoryUtils.deployArtifact(new DefaultArtifact(
                "org.test.channels",
                "wf-core-base",
                ChannelManifest.CLASSIFIER,
                ChannelManifest.EXTENSION,
                "1.0.0",
                null,
                baseManifest
        ));

        // create and deploy second manifest
        secondManifest = new ChannelManifest(null, null, null, null,
                List.of(new Stream("org.wildfly.core", "wildfly-controller", UPGRADE_VERSION)));
        repositoryUtils.deployArtifact(new DefaultArtifact(
                "org.test.channels",
                "wf-core-second",
                ChannelManifest.CLASSIFIER,
                ChannelManifest.EXTENSION,
                "1.0.0",
                null,
                Files.writeString(temp.newFile("second-manifest-1.0.0").toPath(), ChannelManifestMapper.toYaml(secondManifest)).toFile()
        ));

        // deploy additional test artifact
        final RepositorySystem system = mavenSessionManager.newRepositorySystem();
        deployIfMissing(system, mavenSessionManager.newRepositorySystemSession(system), "org.wildfly.core", "wildfly-controller", null, "jar");
    }

    @After
    public void tearDown() throws Exception {
        repositoryUtils.removeArtifact("org.test.channels", "wf-core-base");
        repositoryUtils.removeArtifact("org.test.channels", "wf-core-second");
    }

    @Test
    public void updateWithTwoManifestAvailable() throws Exception {
        // install server
        installBaseServer();

        // update server
        addSecondChannel();
        final ChannelManifest updatedManifest = updateBaseChannel();
        performUpdate();

        // verify both manifests are in the cache
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.1-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(updatedManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-second-1.0.0-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(secondManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                    .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.1")
                    .contains("org.test.channels:wf-core-second:yaml:manifest:1.0.0")
                    .doesNotContain("org.test.channels:wf-core-base:yaml:manifest:1.0.0");
    }

    @Test
    public void updateWithOneManifestAvailable() throws Exception {
        // install server
        installBaseServer();

        // update server with the base manifest read from cache
        addSecondChannel();
        repositoryUtils.removeArtifact("org.test.channels", "wf-core-base");
        performUpdate();

        // verify both manifests are in the cache
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .exists()
                .hasSameTextualContentAs(baseManifest.toPath());
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-second-1.0.0-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(secondManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.0")
                .contains("org.test.channels:wf-core-second:yaml:manifest:1.0.0");
    }

    @Test
    public void revertWithOriginalManifestAvailable() throws Exception {
        installBaseServer();

        // update server with the base manifest read from cache
        addSecondChannel();
        final ChannelManifest updatedManifest = updateBaseChannel();
        performUpdate();

        // verify both manifests are in the cache
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.1-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(updatedManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-second-1.0.0-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(secondManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.1")
                .contains("org.test.channels:wf-core-second:yaml:manifest:1.0.0")
                .doesNotContain("org.test.channels:wf-core-base:yaml:manifest:1.0.0");

        // revert to base
        final InstallationHistoryAction historyAction = new InstallationHistoryAction(outputPath, new CliConsole());
        final List<SavedState> revisions = historyAction.getRevisions();
        final SavedState savedState = revisions.get(revisions.size() - 1);
        historyAction.rollback(savedState, mavenOptions, Collections.emptyList());

        // verify the base manifest is available and at 1.0.0 version
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .exists()
                .hasSameTextualContentAs(baseManifest.toPath());
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.0");
        assertThat(outputPath.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME))
                .content()
                .doesNotContain("wf-core-base");
    }

    @Test
    public void revertWithOriginalManifestNotChanging() throws Exception {
        installBaseServer();

        // update server with the base manifest read from cache
        addSecondChannel();
        performUpdate();

        // verify both manifests are in the cache
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .exists();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-second-1.0.0-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(secondManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.0")
                .contains("org.test.channels:wf-core-second:yaml:manifest:1.0.0");

        // revert to base
        final InstallationHistoryAction historyAction = new InstallationHistoryAction(outputPath, new CliConsole());
        final List<SavedState> revisions = historyAction.getRevisions();
        final SavedState savedState = revisions.get(revisions.size() - 1);
        historyAction.rollback(savedState, mavenOptions, Collections.emptyList());

        // verify the base manifest is available and at 1.0.0 version
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .exists()
                .hasSameTextualContentAs(baseManifest.toPath());
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.0");
    }

    @Test
    public void revertWithOriginalManifestNotAvailable() throws Exception {
        emptyLocalCache();
        installBaseServer();

        // update server with the base manifest read from cache
        addSecondChannel();
        final ChannelManifest updatedManifest = updateBaseChannel();
        performUpdate();

        // verify both manifests are in the cache
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.1-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(updatedManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-second-1.0.0-manifest.yaml"))
                .exists()
                .hasContent(ChannelManifestMapper.toYaml(secondManifest));
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.1")
                .contains("org.test.channels:wf-core-second:yaml:manifest:1.0.0")
                .doesNotContain("org.test.channels:wf-core-base:yaml:manifest:1.0.0");

        // delete the base manifest
        repositoryUtils.removeArtifact("org.test.channels", "wf-core-base");

        // revert to base
        final InstallationHistoryAction historyAction = new InstallationHistoryAction(outputPath, new CliConsole());
        final List<SavedState> revisions = historyAction.getRevisions();
        final SavedState savedState = revisions.get(revisions.size() - 1);
        historyAction.rollback(savedState, mavenOptions, Collections.emptyList());

        // verify the base manifest is available and at 1.0.0 version
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-second-1.0.0-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.1-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.1-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .doesNotContain("org.test.channels:wf-core-second:yaml:manifest:1.0.0")
                .doesNotContain("org.test.channels:wf-core-base:yaml:manifest:1.0.0")
                .doesNotContain("org.test.channels:wf-core-base:yaml:manifest:1.0.1");
    }

    private void performUpdate() throws OperationException, ProvisioningException {
        try (UpdateAction updateAction = new UpdateAction(outputPath, mavenOptions, new CliConsole(), Collections.emptyList())) {
            updateAction.performUpdate();
            FileUtils.deleteQuietly(mavenSessionManager.getProvisioningRepo().toFile());
        }
    }

    @Test
    public void updateWithManifestNotInCacheAndNotAvailableFails() throws Exception {
        // install server
        emptyLocalCache();
        installBaseServer();

        // remove the manifest from repository and cache
        repositoryUtils.removeArtifact("org.test.channels", "wf-core-base");
        Files.delete(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"));

        // update server
        assertThatThrownBy(()->performUpdate())
                .isInstanceOf(UnresolvedChannelMetadataException.class)
                .hasFieldOrPropertyWithValue("missingArtifacts",
                        Set.of(new ChannelMetadataCoordinate("org.test.channels", "wf-core-base", "",
                                ChannelManifest.CLASSIFIER, ChannelManifest.EXTENSION)));
    }

    private void installBaseServer() throws MalformedURLException, ProvisioningException, OperationException {
        // install server
        installation.provision(baseProvCfg, List.of(baseChannel));
        FileUtils.deleteQuietly(mavenSessionManager.getProvisioningRepo().toFile());

        // verify the manifest is in cache
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-second-1.0.0-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.1-manifest.yaml"))
                .doesNotExist();
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("wf-core-base-1.0.0-manifest.yaml"))
                .exists()
                .hasSameTextualContentAs(baseManifest.toPath());
        assertThat(outputPath.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"))
                .content()
                .doesNotContain("org.test.channels:wf-core-second:yaml:manifest:1.0.0")
                .contains("org.test.channels:wf-core-base:yaml:manifest:1.0.0");
    }

    private void addSecondChannel() throws MetadataException {
        try (MetadataAction metadataAction = new MetadataAction(outputPath)) {
            metadataAction
                    .addChannel(new Channel.Builder()
                            .setName("second-channel")
                            .setManifestCoordinate(new ChannelManifestCoordinate("org.test.channels", "wf-core-second"))
                            .addRepository("test-dev-repo", updateRepository.getUrl())
                            .build());
        }
    }

    private ChannelManifest updateBaseChannel() throws DeploymentException, IOException {
        final ChannelManifest updateManifest = updateWildflyController();
        repositoryUtils.deployArtifact(new DefaultArtifact(
                "org.test.channels",
                "wf-core-base",
                ChannelManifest.CLASSIFIER,
                ChannelManifest.EXTENSION,
                "1.0.1",
                null,
                Files.writeString(temp.newFile("base-manifest-1.0.1").toPath(), ChannelManifestMapper.toYaml(updateManifest)).toFile()
        ));
        return updateManifest;
    }

    private ChannelManifest updateWildflyController() throws MalformedURLException {
        final ChannelManifest sourceManifest = ChannelManifestMapper.from(baseManifest.toURI().toURL());
        final Collection<Stream> streams = sourceManifest.getStreams().stream()
                .map(s-> {
                    if (s.getGroupId().equals("org.wildfly.core") && s.getArtifactId().equals("wildfly-cli")) {
                        return new Stream(s.getGroupId(), s.getArtifactId(), UPGRADE_VERSION);
                    } else {
                        return s;
                    }
                })
                .collect(Collectors.toList());
        return new ChannelManifest(
                sourceManifest.getSchemaVersion(),
                sourceManifest.getName(),
                sourceManifest.getId(),
                sourceManifest.getDescription(),
                sourceManifest.getManifestRequirements(),
                streams);
    }
}
