/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.actions;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.deployment.DeploymentException;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.creator.FeaturePackCreator;
import org.jboss.galleon.repo.RepositoryArtifactResolver;
import org.jboss.galleon.spec.ConfigLayerSpec;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.repo.SimplisticMavenRepoManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.channel.Stream;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.FeaturePackLocationParser;
import org.wildfly.prospero.licenses.License;
import org.wildfly.prospero.licenses.LicenseManager;
import org.wildfly.prospero.model.FeaturePackTemplateManager;
import org.wildfly.prospero.model.FeaturePackTemplate;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.test.MetadataTestUtils;
import org.wildfly.prospero.utils.MavenUtils;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonConfigurationWithLayersBuilder;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.METADATA_DIR;

@RunWith(MockitoJUnitRunner.class)
public class FeaturesAddActionTest {
    private static final ConfigId NO_CONFIG = null;
    protected static final Set<ConfigId> NO_DEFAULT_CONFIGS = Collections.emptySet();
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private Path candidatePath;
    @Mock
    private ApplyCandidateAction applyCandidateAction;
    @Mock
    private PrepareCandidateAction prepareCandidateAction;
    private MavenUtils mavenUtils;
    private Path repository;
    private URL repositoryUrl;
    private Path installDir;
    private RepositoryArtifactResolver repo;
    private FeaturesAddAction.CandidateActionsFactory candidateActionsFactory;
    @Mock
    private FeaturePackTemplateManager featurePackTemplateManager;
    @Mock
    private LicenseManager licenseManager;

    @Before
    public void setUp() throws Exception {
        mavenUtils = new MavenUtils(MavenOptions.OFFLINE_NO_CACHE);
        installDir = temp.newFolder("test-server").toPath();
        repository = temp.newFolder("repository").toPath();
        repositoryUrl = repository.toUri().toURL();
        candidatePath = temp.newFolder("candidate-test").toPath();

        repo = SimplisticMavenRepoManager.getInstance(repository);

        candidateActionsFactory = new FeaturesAddAction.CandidateActionsFactory() {

            @Override
            public PrepareCandidateAction newPrepareCandidateActionInstance(MavenSessionManager mavenSessionManager, ProsperoConfig prosperoConfig) throws OperationException {
                return prepareCandidateAction;
            }

            @Override
            public ApplyCandidateAction newApplyCandidateActionInstance(Path candidateDir) throws ProvisioningException, OperationException {
                return applyCandidateAction;
            }
        };
    }

    @Test
    public void isFeaturePackAvailable_featurePackNotInChannel() throws Exception {
        // setup
        // create local repository with empty manifest
        final ChannelManifest manifest = MetadataTestUtils.createManifest(null);
        mavenUtils.deploy(manifest, "org.test", "test", "1.0.0", repositoryUrl);

        // add channel to the local configuration
        final Channel channel = new Channel("test-channel", "", null,
                List.of(new Repository("test", repositoryUrl.toExternalForm())),
                new ChannelManifestCoordinate("org.test", "test"),
                null, null);
        MetadataTestUtils.createInstallationMetadata(installDir, manifest,
                List.of(channel));

        MetadataTestUtils.createGalleonProvisionedState(installDir, "org.wildfly:wildfly-ee-galleon-pack");

        assertFalse(getFeaturesAddAction().isFeaturePackAvailable("idont:exist"));
    }

    @Test
    public void isFeaturePackAvailable_featurePackNotPresentInRepository() throws Exception {
        // setup
        // create local repository with empty manifest
        final ChannelManifest manifest = MetadataTestUtils.createManifest(List.of(
                new Stream("idont", "exist", "1.0.0")
        ));
        mavenUtils.deploy(manifest, "org.test", "test", "1.0.0", repositoryUrl);

        // add channel to the local configuration
        final Channel channel = new Channel("test-channel", "", null,
                List.of(new Repository("test", repositoryUrl.toExternalForm())),
                new ChannelManifestCoordinate("org.test", "test"),
                null, null);
        MetadataTestUtils.createInstallationMetadata(installDir, manifest,
                List.of(channel));

        MetadataTestUtils.createGalleonProvisionedState(installDir, "org.wildfly:wildfly-ee-galleon-pack");

        assertThrows(ArtifactResolutionException.class,
                ()-> getFeaturesAddAction().isFeaturePackAvailable("idont:exist"));
    }

    @Test
    public void isFeaturePackAvailable_featurePackPresent() throws Exception {
        // setup
        // create local repository with empty manifest
        final ChannelManifest manifest = MetadataTestUtils.createManifest(List.of(
                new Stream("org.test", "test-fp", "1.0.0")
        ));
        mavenUtils.deploy(manifest, "org.test", "test", "1.0.0", repositoryUrl);
        // add channel to the local configuration
        final Channel channel = new Channel("test-channel", "", null,
                List.of(new Repository("test", repositoryUrl.toExternalForm())),
                new ChannelManifestCoordinate("org.test", "test"),
                null, null);
        MetadataTestUtils.createInstallationMetadata(installDir, manifest,
                List.of(channel));
        // deploy
        mavenUtils.deployEmptyArtifact("org.test", "test-fp", "1.0.0", null, "zip", repositoryUrl);

        MetadataTestUtils.createGalleonProvisionedState(installDir, "org.wildfly:wildfly-ee-galleon-pack");

        assertTrue(getFeaturesAddAction().isFeaturePackAvailable("org.test:test-fp"));
    }

    @Test
    public void layerNotAvailable_throwsException() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
            .getCreator()
            .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("standalone")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        assertThatThrownBy(()-> getFeaturesAddAction().addFeaturePackWithLayers("org.test:added-pack",
                Set.of("idontexist"), NO_CONFIG, candidatePath))
                .isInstanceOf(FeaturesAddAction.LayerNotFoundException.class)
                .hasFieldOrPropertyWithValue("layers", Set.of("idontexist"))
                .hasFieldOrPropertyWithValue("supportedLayers", Set.of("layer1"));

        verifyNoInteractions(prepareCandidateAction);
        verifyNoInteractions(applyCandidateAction);
    }

    @Test
    public void noLayersInTheFeaturePacks_provisionsNoConfigs() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                    .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
                        ;
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePack("org.test:added-pack", NO_DEFAULT_CONFIGS, candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs()).isEmpty();
        assertIncludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertIncludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void noLayersInTheFeaturePackWithRequriedLayer_throwsException() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                    .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
        ;
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        assertThatThrownBy(()-> getFeaturesAddAction().addFeaturePackWithLayers("org.test:added-pack",
                Set.of("idontexist"), NO_CONFIG, candidatePath))
                .isInstanceOf(FeaturesAddAction.LayerNotFoundException.class)
                .hasFieldOrPropertyWithValue("layers", Set.of("idontexist"));

        verifyNoInteractions(prepareCandidateAction);
        verifyNoInteractions(applyCandidateAction);
    }

    @Test
    public void requestedLayerAddsItsConfig() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                    .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
                    .addConfigLayer(ConfigLayerSpec.builder()
                            .setModel("model")
                            .setName("layer1")
                            .build())
                    .addConfig(ConfigModel.builder()
                            .setModel("model")
                            .setName("model.xml")
                            .build());

        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePackWithLayers("org.test:added-pack",
                Set.of("layer1"), NO_CONFIG, candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .contains(GalleonConfigurationWithLayersBuilder.builder("model", "model.xml")
                        .includeLayer("layer1")
                        .build());
        assertExcludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertExcludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void selectedConfigOverridesDefaultWithRequestedLayer() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
                .addConfig(ConfigModel.builder()
                        .setModel("model")
                        .setName("test.xml")
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model")
                        .setName("layer1")
                        .build());

        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePackWithLayers("org.test:added-pack", Set.of("layer1"),
                new ConfigId(null, "test.xml"), candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .contains(GalleonConfigurationWithLayersBuilder.builder("model", "test.xml")
                        .includeLayer("layer1")
                        .build());
        assertExcludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertExcludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void addFeaturePackAlreadyInstalledAsDependency() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                    .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                    .addConfig(ConfigModel.builder()
                        .setModel("model")
                        .setName("test.xml")
                        .build())
                    .addConfigLayer(ConfigLayerSpec.builder()
                            .setModel("model")
                            .setName("layer1")
                            .build())
                    .addConfigLayer(ConfigLayerSpec.builder()
                            .setModel("model")
                            .setName("layer2")
                            .build());
        deployFeaturePacks(creator);

        // install
        try (Provisioning p = getPm(installDir)) {
                p.provision(GalleonProvisioningConfig.builder()
                    .addFeaturePackDep(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
                    .addFeaturePackDep(FeaturePackLocation.fromString("org.test:added-pack:1.0.0"))
                    .addConfig(ConfigModel.builder("model", "test.xml")
                            .includeLayer("layer1")
                            .build()).build());
        }
        mockInstallationData(installDir, "org.test:base-pack:1.0.0", "org.test:added-pack:1.0.0");

        getFeaturesAddAction().addFeaturePackWithLayers("org.test:added-pack", Set.of("layer2"),
                new ConfigId(null, "test.xml"), candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .contains(ConfigModel.builder("model", "test.xml")
                        .includeLayer("layer1")
                        .includeLayer("layer2")
                        .build());
        assertThat(config.getFeaturePackDeps())
                .map(GalleonFeaturePackConfig::getLocation)
                .contains(
                        FeaturePackLocation.fromString("org.test:base-pack:zip"),
                        FeaturePackLocation.fromString("org.test:added-pack:zip"));
        assertExcludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack:zip"));
        assertExcludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack:zip"));
    }

    @Test
    public void installingLayerOverridesExcludesLayer() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .addConfig(ConfigModel.builder()
                        .setModel("model")
                        .setName("test.xml")
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model")
                        .setName("layer1")
                        .addLayerDep("layer2", true)
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model")
                        .setName("layer2")
                        .build())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                        .addConfigLayer(ConfigLayerSpec.builder()
                                .setModel("model")
                                .setName("layer2")
                                .build());
        deployFeaturePacks(creator);

        // install
        try (Provisioning p = getPm(installDir)) {
            p.provision(GalleonProvisioningConfig.builder()
                    .addFeaturePackDep(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
                    .addConfig(ConfigModel.builder("model", "test.xml")
                            .includeLayer("layer1")
                            .excludeLayer("layer2")
                            .build()).build());
        }

        mockInstallationData(installDir, "org.test:base-pack:1.0.0");

        getFeaturesAddAction().addFeaturePackWithLayers("org.test:added-pack", Set.of("layer2"),
                new ConfigId(null, "test.xml"), candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .contains(ConfigModel.builder("model", "test.xml")
                        .includeLayer("layer1")
                        .includeLayer("layer2")
                        .build());
        assertThat(config.getDefinedConfig(new ConfigId("model", "test.xml")).getExcludedLayers())
                .isEmpty();
        assertThat(config.getFeaturePackDeps())
                .map(GalleonFeaturePackConfig::getLocation)
                .contains(
                        FeaturePackLocation.fromString("org.test:base-pack:zip"),
                        FeaturePackLocation.fromString("org.test:added-pack::zip@maven"));
        assertExcludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertExcludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void multipleModelsDefinedInLayersWithoutSelectedModel_throwError() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("mode2")
                        .setName("layer1")
                        .build())
        ;
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePack(
                "org.test:added-pack",  Set.of(new ConfigId(null, "test")), candidatePath))
                .isInstanceOf(FeaturesAddAction.ModelNotDefinedException.class);
    }

    @Test
    public void selectedModelIsNotDefinedInFeaturePack_throwError() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePack(
                "org.test:added-pack", Set.of(new ConfigId("model2", null)), candidatePath))
                .isInstanceOf(FeaturesAddAction.ModelNotDefinedException.class)
                .hasFieldOrPropertyWithValue("model", "model2");

    }

    @Test
    public void selectedModelOverridesTheDefault() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfig(ConfigModel.builder()
                        .setModel("model2")
                        .setName("model2.xml")
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model2")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePackWithLayers(
                "org.test:added-pack", Set.of("layer1"), new ConfigId("model2", null),
                candidatePath);
        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .contains(GalleonConfigurationWithLayersBuilder.builder("model2", "model2.xml")
                        .includeLayer("layer1")
                        .build());
        assertExcludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertExcludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void featurePacksWithoutLayersCanBeInstalled() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePackWithLayers(
                "org.test:added-pack", Set.of(), NO_CONFIG, candidatePath);
        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .isEmpty();
        assertIncludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertIncludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void invalidFeatureNameThrowsException() throws Exception {
        mockInstallationData(installDir);
        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePack(null, NO_DEFAULT_CONFIGS, candidatePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The feature-pack coordinate cannot be null");
        assertThatThrownBy(()->getFeaturesAddAction().isFeaturePackAvailable(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The feature-pack coordinate cannot be null");

        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePack("only_group", NO_DEFAULT_CONFIGS, candidatePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The feature-pack coordinate has to consist of <groupId>:<artifactId>");
        assertThatThrownBy(()->getFeaturesAddAction().isFeaturePackAvailable("only_group"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The feature-pack coordinate has to consist of <groupId>:<artifactId>");

        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePack("too:many:parts", NO_DEFAULT_CONFIGS, candidatePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The feature-pack coordinate has to consist of <groupId>:<artifactId>");
        assertThatThrownBy(()->getFeaturesAddAction().isFeaturePackAvailable("too:many:parts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The feature-pack coordinate has to consist of <groupId>:<artifactId>");
    }

    @Test
    public void existingFeaturePackCannotBeInstalled() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePack(
                "org.test:base-pack", NO_DEFAULT_CONFIGS, candidatePath))
                .isInstanceOf(FeaturesAddAction.FeaturePackAlreadyInstalledException.class);
    }

    @Test
    public void installDefaultPackagesIfNoLayersAreSpecified() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .newPackage("p2", true)
                .writeContent("prod2/p2.txt", "prod2/p2 1.0.0")
                .getFeaturePack();
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePack(
                "org.test:added-pack", NO_DEFAULT_CONFIGS, candidatePath);
        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .isEmpty();
        assertIncludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertIncludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void installSelectedConfigsIfNoLayersAreSpecified() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .newPackage("p1", false)
                    .writeContent("p1.txt", "foobar")
                    .getFeaturePack()
                .newPackage("p2", false)
                    .writeContent("p2.txt", "foobar")
                    .getFeaturePack()
                .newPackage("p3", true)
                    .writeContent("p3.txt", "foobar")
                    .getFeaturePack()

                .addConfig(ConfigModel.builder()
                        .setModel("model1")
                        .setName("config2")
                        .includeLayer("layer1")
                        .addPackageDep("p1")
                        .build(), true)
                .addConfig(ConfigModel.builder()
                        .setName("config1")
                        .addPackageDep("p2")
                        .build(), true)
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePack(
                "org.test:added-pack", Set.of(new ConfigId("model1", "config2")), candidatePath);
        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .isEmpty();
        final GalleonFeaturePackConfig addedPackageConfig = getFeaturePackConfig(config, "org.test:added-pack::zip@maven");
        assertIncludeDefaultPackages(addedPackageConfig);
        assertExcludeDefaultConfigs(addedPackageConfig);
        assertThat(addedPackageConfig.getIncludedConfigs())
                .contains(new ConfigId("model1", "config2"));
    }

    @Test
    public void installSelectedConfigsIfLayersAreSpecified() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .newPackage("p1", false)
                .writeContent("p1.txt", "foobar")
                .getFeaturePack()
                .newPackage("p2", false)
                .writeContent("p2.txt", "foobar")
                .getFeaturePack()
                .newPackage("p3", true)
                .writeContent("p3.txt", "foobar")
                .getFeaturePack()

                .addConfig(ConfigModel.builder()
                        .setModel("model1")
                        .setName("model1.xml")
                        .build())
                .addConfig(ConfigModel.builder()
                        .setName("config2")
                        .includeLayer("layer1")
                        .addPackageDep("p1")
                        .build(), true)
                .addConfig(ConfigModel.builder()
                        .setName("config1")
                        .addPackageDep("p2")
                        .build(), true)
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePackWithLayers(
                "org.test:added-pack", Set.of("layer1"), NO_CONFIG, candidatePath);
        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs())
                .contains(GalleonConfigurationWithLayersBuilder.builder("model1", "model1.xml")
                        .includeLayer("layer1")
                        .build());
        final GalleonFeaturePackConfig addedPackageConfig = getFeaturePackConfig(config, "org.test:added-pack::zip@maven");
        assertExcludeDefaultPackages(addedPackageConfig);
        assertExcludeDefaultConfigs(addedPackageConfig);
        assertThat(addedPackageConfig.getIncludedConfigs())
                .isEmpty();
    }

    @Test
    public void nonExistingConfigNameThrowsException() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())

                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfig(ConfigModel.builder()
                        .setModel("model1")
                        .setName("config3")
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePackWithLayers(
                "org.test:added-pack", Set.of("layer1"), new ConfigId("model1", "idontexist"), candidatePath))
                .isInstanceOf(FeaturesAddAction.ConfigurationNotFoundException.class)
                .hasFieldOrPropertyWithValue("model", "model1")
                .hasFieldOrPropertyWithValue("name", "idontexist");

    }

    @Test
    public void nonExistingConfigNameWithoutLayersThrowsException() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())

                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfig(ConfigModel.builder()
                        .setModel("model1")
                        .setName("config3")
                        .build())
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("model1")
                        .setName("layer1")
                        .build());
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        assertThatThrownBy(()->getFeaturesAddAction().addFeaturePack(
                "org.test:added-pack", Set.of(new ConfigId("model1", "idontexist")), candidatePath))
                .isInstanceOf(FeaturesAddAction.ConfigurationNotFoundException.class)
                .hasFieldOrPropertyWithValue("model", "model1")
                .hasFieldOrPropertyWithValue("name", "idontexist");

    }

    @Test
    public void noSelectedLayersOrConfigs_IncludesDefaultConfigs() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfig(ConfigModel.builder()
                        .setModel("standalone")
                        .setName("standalone.xml")
                        .build(), true)
                .addConfig(ConfigModel.builder()
                        .setModel("standalone")
                        .setName("test.xml")
                        .build(), true)
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("standalone")
                        .setName("layer1")
                        .build())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
        ;
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePack("org.test:added-pack", NO_DEFAULT_CONFIGS, candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        System.out.println(config);
        assertThat(config.getDefinedConfigs()).isEmpty();
        assertIncludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertIncludeDefaultPackages(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
    }

    @Test
    public void findAndApplyTemplateWithReplaceDependency() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
        ;
        deployFeaturePacks(creator);
        when(featurePackTemplateManager.find(any(), any(), any()))
                .thenReturn(new FeaturePackTemplate(
                        "", "", "",
                        Collections.emptyList(),
                        null,
                        "org.test:base-pack::zip",
                        false, false
                ));
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePack("org.test:added-pack", NO_DEFAULT_CONFIGS, candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        System.out.println(config);
        assertThat(config.getDefinedConfigs()).isEmpty();
        assertIncludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertFalse(config.hasFeaturePackDep(FeaturePackLocationParser.resolveFpl("org.test:base-pack:zip").getProducer()));
    }

    @Test
    public void findAndApplyTemplateWithTransitiveDependency() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
        ;
        deployFeaturePacks(creator);
        when(featurePackTemplateManager.find(any(), any(), any()))
                .thenReturn(new FeaturePackTemplate(
                        "", "", "",
                        Collections.emptyList(),
                        "org.test:base-pack::zip",
                        null,
                        false, false
                ));
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePack("org.test:added-pack", NO_DEFAULT_CONFIGS, candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs()).isEmpty();
        assertIncludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertIncludeDefaultPackages(getTransitiveFeaturePackConfig(config, "org.test:base-pack:zip"));
    }

    @Test
    public void findAndApplyTemplateWithLayersWithTransitiveDependency() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addConfig(ConfigModel.builder()
                        .setModel("standalone")
                        .setName("standalone.xml")
                        .build(), true)
                .addConfigLayer(ConfigLayerSpec.builder()
                        .setModel("standalone")
                        .setName("layer1")
                        .build())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
        ;
        deployFeaturePacks(creator);
        when(featurePackTemplateManager.find(any(), any(), any()))
                .thenReturn(new FeaturePackTemplate(
                        "", "", "",
                        Collections.emptyList(),
                        "org.test:base-pack::zip",
                        null,
                        false, false
                ));
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePackWithLayers("org.test:added-pack", Set.of("layer1"), null, candidatePath);



        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertNotNull(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        assertNotNull(getTransitiveFeaturePackConfig(config, "org.test:base-pack:zip"));
    }

    @Test
    public void findAndApplyTemplateWithAdditionalPackages() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
        ;
        deployFeaturePacks(creator);
        when(featurePackTemplateManager.find(any(), any(), any()))
                .thenReturn(new FeaturePackTemplate(
                        "", "", "",
                        List.of("additional.package"),
                        null,
                        null,
                        false, false
                ));
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        getFeaturesAddAction().addFeaturePack("org.test:added-pack", NO_DEFAULT_CONFIGS, candidatePath);

        final ArgumentCaptor<GalleonProvisioningConfig> provisioningConfigArgumentCaptor = ArgumentCaptor.forClass(GalleonProvisioningConfig.class);
        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                provisioningConfigArgumentCaptor.capture());

        final GalleonProvisioningConfig config = provisioningConfigArgumentCaptor.getValue();
        assertThat(config.getDefinedConfigs()).isEmpty();
        assertIncludeDefaultConfigs(getFeaturePackConfig(config, "org.test:added-pack::zip@maven"));
        final FeaturePackLocation.ProducerSpec fpName = FeaturePackLocationParser
                .resolveFpl("org.test:added-pack::zip")
                .getProducer();
        assertThat(config.getFeaturePackDep(fpName).getIncludedPackages())
                .contains("additional.package");
    }

    @Test
    public void testAddLicenses() throws Exception {
        // install base feature-pack
        final FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:base-pack:1.0.0:zip").getFPID())
                .getCreator()
                .newFeaturePack(FeaturePackLocation.fromString("org.test:added-pack:1.0.0:zip").getFPID())
                .addDependency(FeaturePackLocation.fromString("org.test:base-pack:1.0.0"))
        ;
        deployFeaturePacks(creator);
        // install
        installFeaturePack(installDir, "org.test:base-pack:1.0.0:zip");

        final Path licenses = installDir.resolve(METADATA_DIR).resolve(LicenseManager.LICENSES_FOLDER);
        Files.createDirectories(licenses);

        // create licenses - base-license is accepted in the original server and added-license is accepted during
        // addition of a feature-pack
        final License addedLicense = new License("added-license", "org.test:added-pack", "Added License", "Added license text");
        final License baseLicense = new License("base-license", "org.test:base-pack", "Base license", "Base license text");
        when(licenseManager.getLicenses(any())).thenReturn(List.of(addedLicense));
        // use real method to actually write out the content of licenses - this way we can check that both old and new licenses are saved
        Mockito.doCallRealMethod().when(licenseManager).recordAgreements(any(), any());
        licenseManager.recordAgreements(List.of(baseLicense), installDir);

        getFeaturesAddAction().addFeaturePack("org.test:added-pack", NO_DEFAULT_CONFIGS, candidatePath);

        verify(prepareCandidateAction).buildCandidate(any(), any(), eq(ApplyCandidateAction.Type.FEATURE_ADD),
                any());


        // verify candidate has licenses from both base server and newly added ones
        final Properties properties = new Properties();
        final Path licensesDir = candidatePath.resolve(METADATA_DIR).resolve(LicenseManager.LICENSES_FOLDER);
        properties.load(new FileInputStream(licensesDir.resolve("license_accepted.properties").toFile()));
        assertThat(properties.stringPropertyNames())
                .contains("license.0.name", "license.1.name");
        assertThat(properties.getProperty("license.0.name"))
                .isEqualTo("base-license");
        assertThat(properties.getProperty("license.1.name"))
                .isEqualTo("added-license");
        assertThat(licensesDir.resolve("base-license.txt"))
                .hasContent("Base license text");
        assertThat(licensesDir.resolve("added-license.txt"))
                .hasContent("Added license text");
    }

    private static GalleonFeaturePackConfig getFeaturePackConfig(GalleonProvisioningConfig config, String fpl) {
        return config.getFeaturePackDeps().stream().filter(f -> f.getLocation().toString().equals(fpl)).findFirst().get();
    }

    private static GalleonFeaturePackConfig getTransitiveFeaturePackConfig(GalleonProvisioningConfig config, String fpl) {
        return config.getTransitiveDeps().stream().filter(f -> f.getLocation().toString().equals(fpl)).findFirst().get();
    }

    private static void assertIncludeDefaultPackages(GalleonFeaturePackConfig cfg) {
        assertNull("The inherit configs should be included", cfg.getInheritPackages());
    }

    private static void assertIncludeDefaultConfigs(GalleonFeaturePackConfig cfg) {
        assertNull("The inherit configs should be included", cfg.getInheritConfigs());
    }

    private static void assertExcludeDefaultPackages(GalleonFeaturePackConfig cfg) {
        assertFalse("The inherit configs should be included", cfg.getInheritPackages());
    }

    private static void assertExcludeDefaultConfigs(GalleonFeaturePackConfig cfg) {
        assertFalse("The inherit configs should be included", cfg.getInheritConfigs());
    }

    private FeaturesAddAction getFeaturesAddAction() throws MetadataException, ProvisioningException {

        final FeaturesAddAction featuresAddAction = new FeaturesAddAction(MavenOptions.OFFLINE_NO_CACHE, installDir,
                List.of(new Repository("test", repositoryUrl.toExternalForm())), null,
                candidateActionsFactory, featurePackTemplateManager, licenseManager);
        return featuresAddAction;
    }

    private void deployFeaturePacks(FeaturePackCreator creator) throws ProvisioningException, IOException, DeploymentException {
        creator.install();
        deployFeaturePack("org.test", "base-pack", "1.0.0");
        deployFeaturePack("org.test", "added-pack", "1.0.0");

        final ChannelManifest manifest = MetadataTestUtils.createManifest(List.of(
                new Stream("org.test", "base-pack", "1.0.0"),
                new Stream("org.test", "added-pack", "1.0.0")
        ));
        mavenUtils.deploy(manifest, "org.test", "test", "1.0.0", repositoryUrl);
    }

    private void deployFeaturePack(String groupId, String artifactId, String version) throws IOException, DeploymentException {
        final String fileName = artifactId + "-zip-" + version + ".zip";
        Path file = repository;
        for (String part : groupId.split("\\.")) {
            file = file.resolve(part);
        }
        file = file.resolve(artifactId).resolve("zip");
        file = file.resolve(fileName);

        mavenUtils.deployFile(groupId, artifactId, version, null, "zip",
                file.toFile(),
                repositoryUrl);
    }

    private void installFeaturePack(Path path, String fpl) throws Exception {
        try (Provisioning p = getPm(path)) {
            p.install(FeaturePackLocation.fromString(fpl));
        }
        // mock the installation metadata
        mockInstallationData(path, fpl);
    }

    private void mockInstallationData(Path path, String... fpls) throws IOException {
        final Path metadataPath = path.resolve(METADATA_DIR);
        Files.createDirectory(metadataPath);
        MetadataTestUtils.createManifest("manifest", Collections.emptyList(), metadataPath);
        MetadataTestUtils.createChannel("channel", "org.test:test", List.of(repositoryUrl.toExternalForm()),
                metadataPath);

        final Path provisioningFile = path.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONING_XML);
        if (Files.exists(provisioningFile)) {
            java.util.stream.Stream<String> lines = FileUtils.readLines(
                    provisioningFile.toFile(),
                    StandardCharsets.UTF_8).stream();
            for (String fpl : fpls) {
                lines = lines.map(l -> l.replace(fpl, fpl.replace("1.0.0", "")));
            }
            FileUtils.writeLines(provisioningFile.toFile(),
                    lines.collect(Collectors.toList()));
        }
    }

    protected Provisioning getPm(Path path) throws Exception {
        GalleonBuilder provider = new GalleonBuilder();
        provider.addArtifactResolver(repo);
        return provider.newProvisioningBuilder()
                .setInstallationHome(path)
                .setRecordState(true)
                .build();
    }
}