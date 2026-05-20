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

import static org.wildfly.prospero.licenses.LicenseManager.LICENSES_FOLDER;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.channel.ArtifactTransferException;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.NoStreamFoundException;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.TemporaryRepositoriesHandler;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.InvalidUpdateCandidateException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.FeaturePackLocationParser;
import org.wildfly.prospero.galleon.GalleonEnvironment;
import org.wildfly.prospero.licenses.License;
import org.wildfly.prospero.licenses.LicenseManager;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.FeaturePackTemplateManager;
import org.wildfly.prospero.model.FeaturePackTemplate;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.GalleonFeaturePackLayout;
import org.jboss.galleon.api.GalleonProvisioningLayout;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonConfigurationWithLayers;
import org.jboss.galleon.api.config.GalleonConfigurationWithLayersBuilder;
import org.jboss.galleon.api.config.GalleonConfigurationWithLayersBuilderItf;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;

/**
 * Installs a feature-pack onto an existing server.
 */
public class FeaturesAddAction {

    private final MavenSessionManager mavenSessionManager;
    private final Path installDir;
    private final InstallationMetadata metadata;
    private final ProsperoConfig prosperoConfig;
    private final Console console;
    private final CandidateActionsFactory candidateActionsFactory;
    private final FeaturePackTemplateManager featurePackTemplateManager;
    private LicenseManager licenseManager;

    public FeaturesAddAction(MavenOptions mavenOptions, Path installDir, List<Repository> repositories, Console console) throws MetadataException, ProvisioningException {
        this(mavenOptions, installDir, repositories, console,
                new DefaultCandidateActionsFactory(installDir),
                new FeaturePackTemplateManager(), new LicenseManager());
    }

    // used for testing
    FeaturesAddAction(MavenOptions mavenOptions, Path installDir,
                      List<Repository> repositories, Console console, CandidateActionsFactory candidateActionsFactory,
                      FeaturePackTemplateManager featurePackTemplateManager, LicenseManager licenseManager)
            throws MetadataException, ProvisioningException {
        this.installDir = InstallFolderUtils.toRealPath(installDir);

        this.console = console;
        this.metadata = InstallationMetadata.loadInstallation(this.installDir);
        this.prosperoConfig = addTemporaryRepositories(repositories);

        final MavenOptions mergedOptions = prosperoConfig.getMavenOptions().merge(mavenOptions);
        this.mavenSessionManager = new MavenSessionManager(mergedOptions);

        this.candidateActionsFactory = candidateActionsFactory;

        this.featurePackTemplateManager = featurePackTemplateManager;

        this.licenseManager = licenseManager;
    }

    /**
     * performs feature-pack installation as a new candidate server. The added feature-pack can be customized by specifying layers and configuration model name.
     * In order to install a feature-pack, a server is re-provisioned and changes are applied to existing server.
     * <p>
     * The candidate server is created in a temp directory. To apply the changes and complete the installation,
     * {@link ApplyCandidateAction#applyUpdate(ApplyCandidateAction.Type)} should be used.
     *
     * @param featurePackCoord   - maven {@code groupId:artifactId} coordinates of the feature-pack to install
     * @param defaultConfigNames - set of {@code ConfigId} of the default configurations to include
     * @param candidatePath      - folder where the candidate server should be created
     * @throws ProvisioningException                - if unable to provision the server
     * @throws ModelNotDefinedException             - if requested model is not provided by the feature-pack
     * @throws LayerNotFoundException               - if one of the requested layers is not provided by the feature-pack
     * @throws FeaturePackAlreadyInstalledException - if the requested feature-pack configuration wouldn't change the server state
     * @throws InvalidUpdateCandidateException      - if the folder at {@code updateDir} is not a valid update
     * @throws MetadataException                    - if unable to read or write the installation of update metadata
     */
    public void addFeaturePack(String featurePackCoord, Set<ConfigId> defaultConfigNames, Path candidatePath)
            throws ProvisioningException, OperationException {
        verifyFeaturePackCoord(featurePackCoord);
        Objects.requireNonNull(defaultConfigNames);

        candidatePath = InstallFolderUtils.toRealPath(candidatePath);

        FeaturePackLocation fpl = FeaturePackLocationParser.resolveFpl(featurePackCoord);

        if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
            ProsperoLogger.ROOT_LOGGER.trace("Adding feature-pack " + fpl);
        }

        final Map<String, Set<String>> allLayers = getAllLayers(fpl);

        if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
            ProsperoLogger.ROOT_LOGGER.trace("Found layers");
            for (String key : allLayers.keySet()) {
                ProsperoLogger.ROOT_LOGGER.trace(key + ": " + StringUtils.join(allLayers.get(key)));
            }
        }

        final Set<ConfigId> selectedConfigs = new HashSet<>();
        for (ConfigId defaultConfigName : defaultConfigNames) {
            final String selectedModel = getSelectedModel(defaultConfigName==null?null:defaultConfigName.getModel(), allLayers);

            final String selectedConfig = getSelectedConfig(defaultConfigName, selectedModel);
            if (selectedConfig != null) {
                selectedConfigs.add(new ConfigId(selectedModel, selectedConfig));
            }
        }

        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.addingFeaturePack(fpl, StringUtils.join(selectedConfigs, ","), "");
        }

        final GalleonProvisioningConfig newConfig = buildProvisioningConfig(Collections.emptySet(), fpl, selectedConfigs);

        install(featurePackCoord, newConfig, candidatePath);
    }


    /**
     * performs feature-pack installation as a new candidate server. The added feature-pack can be customized by specifying layers and configuration model name.
     * In order to install a feature-pack, a server is re-provisioned and changes are applied to existing server.
     *<p>
     * The candidate server is created in a temp directory. To apply the changes and complete the installation,
     * {@link ApplyCandidateAction#applyUpdate(ApplyCandidateAction.Type)} should be used.
     *
     * @param featurePackCoord - maven {@code groupId:artifactId} coordinates of the feature-pack to install
     * @param layers           - set of layer names to be provisioned
     * @param configName       - {@code ConfigId} of the configuration file to generate if supported
     * @param candidateFolder  - folder where the candidate server should be created
     * @throws ProvisioningException                - if unable to provision the server
     * @throws ModelNotDefinedException             - if requested model is not provided by the feature-pack
     * @throws LayerNotFoundException               - if one of the requested layers is not provided by the feature-pack
     * @throws FeaturePackAlreadyInstalledException - if the requested feature-pack configuration wouldn't change the server state
     * @throws InvalidUpdateCandidateException      - if the folder at {@code updateDir} is not a valid update
     * @throws MetadataException                    - if unable to read or write the installation of update metadata
     */
    public void addFeaturePackWithLayers(String featurePackCoord, Set<String> layers, ConfigId configName, Path candidateFolder)
            throws ProvisioningException, OperationException {
        Objects.requireNonNull(layers);
        if (layers.isEmpty() && configName != null) {
            throw new IllegalArgumentException("The layers have to be selected if configName is not empty");
        }
        verifyFeaturePackCoord(featurePackCoord);

        FeaturePackLocation fpl = FeaturePackLocationParser.resolveFpl(featurePackCoord);

        if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
            ProsperoLogger.ROOT_LOGGER.trace("Adding feature-pack " + fpl);
        }

        final Map<String, Set<String>> allLayers = getAllLayers(fpl);

        if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
            ProsperoLogger.ROOT_LOGGER.trace("Found layers");
            for (String key : allLayers.keySet()) {
                ProsperoLogger.ROOT_LOGGER.trace(key + ": " + StringUtils.join(allLayers.get(key)));
            }
        }

        final String selectedModel = getSelectedModel(configName == null?null:configName.getModel(), allLayers);

        verifyLayerAvailable(layers, selectedModel, allLayers);

        final String selectedConfig = getSelectedConfig(configName, selectedModel);

        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.addingFeaturePack(fpl, selectedConfig + ":" + selectedModel, StringUtils.join(layers));
        }

        final GalleonProvisioningConfig newConfig = buildProvisioningConfig(layers, fpl, selectedConfig==null?Collections.emptySet():Set.of(new ConfigId(selectedModel, selectedConfig)));

        install(featurePackCoord, newConfig, candidateFolder);
    }

    /**
     * find a template matching feature-pack coordinates
     *
     * @param featurePackCoord - coordinates of the feature-pack in {@code groupId:artifactId} format
     * @return - template matching the feature-pack or null if none found.
     * @throws ProvisioningException
     * @throws OperationException
     */
    public FeaturePackTemplate getFeaturePackRecipe(String featurePackCoord)
            throws ProvisioningException, OperationException {
        Path tempDirectory = null;
        GalleonEnvironment galleonEnv = null;
        try {
            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debug("Looking up version of " + featurePackCoord);
            }
            // nothing should be written out in the temp folder, but we need to create it in order to create galleon
            // TODO: future improvement - replace it with just creating ChannelSession
            tempDirectory = Files.createTempDirectory("prospero-temp-target");
            galleonEnv = getGalleonEnv(tempDirectory);

            final ArtifactCoordinate coord = toMavenCoordinates(featurePackCoord);

            final String version = galleonEnv.getChannelSession().findLatestMavenArtifactVersion(coord.getGroupId(), coord.getArtifactId(),
                    coord.getExtension(), coord.getClassifier(), coord.getVersion()).getVersion();

            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debugf("Found version %s of %s, matching template", version, featurePackCoord);
            }

            return featurePackTemplateManager.find(coord.getGroupId(), coord.getArtifactId(), coord.getVersion());
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToCreateTemporaryDirectory(e);
        } finally {
            if (galleonEnv != null) {
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debug("Closing galleon env");
                }
                galleonEnv.close();
            }
            if (tempDirectory != null) {
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debugf("Removing temporary folder: %s", tempDirectory);
                }
                FileUtils.deleteQuietly(tempDirectory.toFile());
            }
        }
    }

    /**
     * check if a feature-pack with {@code featurePackCoord} can be resolved in available channels.
     *
     * @param featurePackCoord - maven {@code groupId:artifactId} coordinates of the feature-pack to install
     * @return true if the feature-pack is available, false otherwise
     * @throws OperationException    - if unable to read the metadata
     * @throws ProvisioningException - if unable to read the metadata
     */
    public boolean isFeaturePackAvailable(String featurePackCoord) throws OperationException, ProvisioningException {
        final ArtifactCoordinate coord = toMavenCoordinates(featurePackCoord);
        final ChannelSession channelSession = GalleonEnvironment
                .builder(installDir, prosperoConfig.getChannels(), mavenSessionManager, false).build()
                .getChannelSession();

        try {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("Resolving a feature-pack: " + featurePackCoord);
            }
            channelSession.resolveMavenArtifact(coord.getGroupId(), coord.getArtifactId(),
                    coord.getExtension(), coord.getClassifier(), coord.getVersion());
        } catch (NoStreamFoundException e) {
            return false;
        } catch (ArtifactTransferException e) {
            throw new ArtifactResolutionException("Unable to resolve feature-pack " + featurePackCoord, e,
                    e.getUnresolvedArtifacts(), e.getAttemptedRepositories(), false);
        }

        return true;
    }

    private static String getSelectedConfig(ConfigId defaultConfigName, String selectedModel) {
        if (defaultConfigName == null || defaultConfigName.getName() == null) {
            if (selectedModel == null) {
                return null;
            } else {
                return selectedModel + ".xml";
            }
        } else {
            return defaultConfigName.getName();
        }
    }

    private static void verifyFeaturePackCoord(String featurePackCoord) {
        if (featurePackCoord == null || featurePackCoord.isEmpty()) {
            throw new IllegalArgumentException("The feature-pack coordinate cannot be null");
        }
        if (featurePackCoord.split(":").length != 2) {
            throw new IllegalArgumentException("The feature-pack coordinate has to consist of <groupId>:<artifactId>");
        }
    }

    private void install(String featurePackCoord, GalleonProvisioningConfig newConfig, Path candidate) throws ProvisioningException, OperationException {
        final List<License> pendingLicenses = getRequiredLicenses(featurePackCoord);

        verifyConfigurationsAvailable(newConfig);

        // make sure the previous provisioning_config is persisted
        try (InstallationMetadata metadata = InstallationMetadata.loadInstallation(installDir)) {
            metadata.updateProvisioningConfiguration();
        }

        try (PrepareCandidateAction prepareCandidateAction = candidateActionsFactory.newPrepareCandidateActionInstance(mavenSessionManager, prosperoConfig);
             GalleonEnvironment galleonEnv = getGalleonEnv(candidate)) {

            ProsperoLogger.ROOT_LOGGER.updateCandidateStarted(installDir);
            prepareCandidateAction.buildCandidate(candidate, galleonEnv, ApplyCandidateAction.Type.FEATURE_ADD, newConfig);
            ProsperoLogger.ROOT_LOGGER.updateCandidateCompleted(installDir);
        }

        try {
            // copy licenses from the original server to the candidate to preserve accepted licenses record
            final Path existingLicenses = installDir.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(LICENSES_FOLDER);
            if (Files.exists(existingLicenses)) {
                FileUtils.copyDirectory(existingLicenses.toFile(), candidate.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(LICENSES_FOLDER).toFile());
            }
            if (!pendingLicenses.isEmpty()) {
                // accept additional licenses appending to the record
                licenseManager.recordAgreements(pendingLicenses, candidate);
            }
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToWriteFile(candidate.resolve(LICENSES_FOLDER), e);
        }
    }

    private static ArtifactCoordinate toMavenCoordinates(String featurePackCoord) {
        if (featurePackCoord == null || featurePackCoord.isEmpty()) {
            throw new IllegalArgumentException("The feature-pack coordinate cannot be null");
        }
        // a workaround for tests
        if (featurePackCoord.endsWith("::zip")) {
            featurePackCoord = featurePackCoord.substring(0, featurePackCoord.length() - "::zip".length());
        }
        final String[] splitCoordinates = featurePackCoord.split(":");
        if (splitCoordinates.length != 2) {
            throw new IllegalArgumentException("The feature-pack coordinate has to consist of <groupId>:<artifactId>");
        }
        final ArtifactCoordinate coord = new ArtifactCoordinate(splitCoordinates[0], splitCoordinates[1],
                "zip", null, "");
        return coord;
    }

    private GalleonProvisioningConfig buildProvisioningConfig(Set<String> layers, FeaturePackLocation fpl, Set<ConfigId> selectedConfigs)
            throws ProvisioningException, OperationException {
        if (!layers.isEmpty() && selectedConfigs.size() > 1) {
            throw new IllegalArgumentException("Only one config can be selected when selecting layers");
        }
        try (GalleonEnvironment galleonEnv = getGalleonEnv(installDir);
            Provisioning pm = galleonEnv.getProvisioning()) {

            final GalleonProvisioningConfig existingConfig = pm.getProvisioningConfig();
            final GalleonProvisioningConfig.Builder builder = GalleonProvisioningConfig.builder(existingConfig);

            final GalleonFeaturePackConfig.Builder fpBuilder = buildFeaturePackConfig(fpl, existingConfig, builder);

            if (!selectedConfigs.isEmpty()) {
                fpBuilder.setInheritConfigs(false);
            }

            for (ConfigId selectedConfig : selectedConfigs) {
                if (selectedConfig != null) {
                    fpBuilder.setInheritConfigs(false);
                    if (!layers.isEmpty()) {
                        final GalleonConfigurationWithLayersBuilderItf configBuilder = buildLayerConfig(layers,
                                selectedConfig.getName(),
                                selectedConfig.getModel(),
                                pm,
                                existingConfig,
                                builder);
                        builder.addConfig(configBuilder.build());
                    } else {
                        fpBuilder.includeDefaultConfig(selectedConfig.getModel(), selectedConfig.getName());
                    }
                }
            }

            if (!layers.isEmpty()) {
                fpBuilder.setInheritPackages(false);
            }

            // order of feature-packs matter. if the feature-pack redefines an existing package, insert it in its place
            final int fpIndex;
            final FeaturePackTemplate mapping = getFeaturePackRecipe(fpl.getProducerName());
            if (mapping != null) {
                fpIndex = applyProvisioningTemplate(fpl, builder, mapping, existingConfig, fpBuilder);
            } else {
                fpIndex = existingConfig.getFeaturePackDeps().size();
            }

            final GalleonProvisioningConfig newConfig = builder
                    .addFeaturePackDep(fpIndex, fpBuilder.build())
                    .build();

            if (newConfig.equals(existingConfig)) {
                throw ProsperoLogger.ROOT_LOGGER.featurePackAlreadyInstalled(fpl);
            }

            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("New provisioning configuration: " + newConfig);
            }

            return newConfig;
        }
    }

    private static int applyProvisioningTemplate(FeaturePackLocation fpl, GalleonProvisioningConfig.Builder builder,
                                                 FeaturePackTemplate mapping, GalleonProvisioningConfig existingConfig,
                                                 GalleonFeaturePackConfig.Builder fpBuilder) throws ProvisioningException {
        final ProvisioningConfigManipulator provisioningConfigManipulator = new ProvisioningConfigManipulator(builder);
        int fpIndex = -1;
        if (mapping.getReplacesDependency() != null) {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.tracef("Replacing %s dependency with %s", mapping.getReplacesDependency(), fpl);
            }
            fpIndex = provisioningConfigManipulator.removeFeaturePackDefinition(mapping.getReplacesDependency());

            final GalleonFeaturePackConfig removedConfig = existingConfig.getFeaturePackDep(
                    FeaturePackLocationParser.resolveFpl(mapping.getReplacesDependency()).getProducer());
            ProvisioningConfigManipulator.copyFeaturePackConfig(removedConfig, fpBuilder);
        } else if (mapping.getTransitiveDependency() != null) {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.tracef("Marking %s as a transitive dependency", mapping.getTransitiveDependency());
            }

            fpIndex = provisioningConfigManipulator.convertToTransitiveDep(mapping.getTransitiveDependency(), existingConfig);
        } else {
            fpIndex = existingConfig.getFeaturePackDeps().size();
        }

        for (String additionalPackage : mapping.getAdditionalPackages()) {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.tracef("Adding additional package %s to %s", additionalPackage, fpl);
            }
            if (!fpBuilder.isPackageExcluded(additionalPackage)) {
                fpBuilder.includePackage(additionalPackage);
            }
        }
        return fpIndex;
    }

    private static GalleonFeaturePackConfig.Builder buildFeaturePackConfig(FeaturePackLocation fpl,
                                                                    GalleonProvisioningConfig existingConfig,
                                                                    GalleonProvisioningConfig.Builder builder)
            throws ProvisioningException {
        final GalleonFeaturePackConfig.Builder fpBuilder;
        if (existingConfig.hasFeaturePackDep(fpl.getProducer())) {
            GalleonFeaturePackConfig fp = existingConfig.getFeaturePackDep(fpl.getProducer());
            fpBuilder = GalleonFeaturePackConfig.builder(fp);
            builder.removeFeaturePackDep(fp.getLocation());
        } else {
            fpBuilder = GalleonFeaturePackConfig.builder(fpl);
        }
        return fpBuilder;
    }

    private static GalleonConfigurationWithLayersBuilderItf buildLayerConfig(Set<String> layers, String selectedConfig, String selectedModel,
                                                        Provisioning provisioning, GalleonProvisioningConfig existingConfig, GalleonProvisioningConfig.Builder builder)
            throws ProvisioningException {
        final GalleonConfigurationWithLayersBuilderItf configBuilder;
        final ConfigId id = new ConfigId(selectedModel, selectedConfig);
        if (existingConfig.hasDefinedConfig(id)) {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("Replacing existing ConfigModel " + id);
            }
            GalleonConfigurationWithLayers cmodel = existingConfig.getDefinedConfig(id);
            configBuilder = provisioning.buildConfigurationBuilder(cmodel);
            includeLayers(layers, configBuilder, cmodel);
            builder.removeConfig(id);
        } else {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("Adding new ConfigModel " + id);
            }
            configBuilder = GalleonConfigurationWithLayersBuilder.builder(selectedModel, selectedConfig);
            for (String layer: layers) {
                configBuilder.includeLayer(layer);
            }
        }
        return configBuilder;
    }

    private static void includeLayers(Set<String> layers, GalleonConfigurationWithLayersBuilderItf configBuilder, GalleonConfigurationWithLayers cmodel) throws ProvisioningDescriptionException {
        for (String layer: layers) {
            if (cmodel.getExcludedLayers().contains(layer)){
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Un-excluding layer" + layer);
                }
                configBuilder.removeExcludedLayer(layer);
            }
            if (!cmodel.getIncludedLayers().contains(layer)) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Adding layer " + layer);
                }
                configBuilder.includeLayer(layer);
            }
        }
    }

    private static void verifyLayerAvailable(Set<String> layers, String selectedModel, Map<String, Set<String>> allLayers) throws LayerNotFoundException {
        if (allLayers.isEmpty() && !layers.isEmpty()) {
            final String missingLayers = StringUtils.join(layers, ", ");
            throw new LayerNotFoundException(ProsperoLogger.ROOT_LOGGER.layerNotFoundInFeaturePack(missingLayers), layers,
                    Collections.emptySet());
        }
        if (selectedModel != null) {
            final Set<String> modelLayers = allLayers.get(selectedModel);
            for (String layer : layers) {
                final Set<String> missingLayers = layers.stream().filter(l->!modelLayers.contains(layer)).collect(Collectors.toSet());
                if (!missingLayers.isEmpty()) {
                    final String missingLayersTxt = StringUtils.join(missingLayers, ", ");
                    throw new LayerNotFoundException(ProsperoLogger.ROOT_LOGGER.layerNotFoundInFeaturePack(missingLayersTxt), missingLayers, modelLayers);
                }
            }
        }
    }

    private void verifyConfigurationsAvailable(GalleonProvisioningConfig config) throws ProvisioningException, OperationException {
        try (GalleonEnvironment env = GalleonEnvironment
                .builder(installDir, prosperoConfig.getChannels(), mavenSessionManager, false).build()) {
            final Stream<ConfigId> configIds = Stream.concat(
                    config.getFeaturePackDeps().stream().flatMap(fd -> fd.getIncludedConfigs().stream()),
                    config.getDefinedConfigs().stream().map(GalleonConfigurationWithLayers::getId));

            final Optional<ConfigId> missingConfig = configIds.filter(cfg -> {
                try {
                    return !env.getProvisioning().hasOrderedFeaturePacksConfig(config, cfg);
                } catch (ProvisioningException ex) {
                    throw new RuntimeException(ex);
                }
            }).findFirst();

            if (missingConfig.isPresent()) {
                final ConfigId cfg = missingConfig.get();
                throw new ConfigurationNotFoundException(ProsperoLogger.ROOT_LOGGER.galleonConfigNotFound(cfg.getModel(), cfg.getName()), cfg);
            }
        }
    }

    public List<License> getRequiredLicenses(String featurePackCoord) {
       return licenseManager.getLicenses(Set.of(featurePackCoord));
   }

    private static String getSelectedModel(String model, Map<String, Set<String>> allLayers)
            throws ModelNotDefinedException {
        if (allLayers.isEmpty()) {
            return null;
        }

        final String selectedModel;
        if (model == null || model.isEmpty()) {
            if (allLayers.size() > 1) {
                throw new ModelNotDefinedException(ProsperoLogger.ROOT_LOGGER.noDefaultModel(), allLayers.keySet());
            }
            selectedModel = allLayers.keySet().iterator().next();
        } else {
            if (!allLayers.containsKey(model)) {
                throw new ModelNotDefinedException(ProsperoLogger.ROOT_LOGGER.modelNotFoundInFeaturePack(model), model, allLayers.keySet());
            }
            selectedModel = model;
        }
        return selectedModel;
    }

    private GalleonEnvironment getGalleonEnv(Path target) throws ProvisioningException, OperationException {
        return GalleonEnvironment
                .builder(target, prosperoConfig.getChannels(), mavenSessionManager, false)
                .setSourceServerPath(this.installDir)
                .setConsole(console)
                .build();
    }

    private Map<String, Set<String>> getAllLayers(FeaturePackLocation fpl)
            throws ProvisioningException, OperationException {
        final GalleonProvisioningConfig config = GalleonProvisioningConfig.builder()
                .addFeaturePackDep(GalleonFeaturePackConfig.builder(fpl).build())
                .build();

        final MavenRepoManager repositoryManager = GalleonEnvironment
                .builder(installDir, prosperoConfig.getChannels(), mavenSessionManager, false).build()
                .getRepositoryManager();
        final Map<String, Set<String>> layersMap = new HashMap<>();
        try (Provisioning p = new GalleonBuilder().addArtifactResolver(repositoryManager).newProvisioningBuilder(config).build()) {
            try (GalleonProvisioningLayout layout = p.newProvisioningLayout(config)) {
                for (GalleonFeaturePackLayout fp : layout.getOrderedFeaturePacks()) {
                    final Set<ConfigId> configIds;
                    try {
                        configIds = fp.loadLayers();
                    } catch (IOException e) {
                        // this should not happen as the code IOException is not actually thrown by loadLayers
                        throw new RuntimeException(e);
                    }
                    for (ConfigId layer : configIds) {
                        final String model = layer.getModel();
                        Set<String> names = layersMap.get(model);
                        if (names == null) {
                            names = new HashSet<>();
                            layersMap.put(model, names);
                        }
                        names.add(layer.getName());
                    }
                }
            }
        }

        return layersMap;
    }

    private ProsperoConfig addTemporaryRepositories(List<Repository> repositories) {
        final ProsperoConfig prosperoConfig = metadata.getProsperoConfig();

        final List<Channel> channels = TemporaryRepositoriesHandler.overrideRepositories(prosperoConfig.getChannels(), repositories);

        return new ProsperoConfig(channels, prosperoConfig.getMavenOptions());
    }

    /**
     * Thrown if a requested layer cannot be found in a feature-pack
     */
    public static class LayerNotFoundException extends OperationException {

        private final Set<String> layers;
        private final Set<String> supportedLayers;

        public LayerNotFoundException(String msg, Set<String> layers, Set<String> supportedLayers) {
            super(msg);
            this.layers = layers;
            this.supportedLayers = supportedLayers;
        }

        public Set<String> getLayers() {
            return layers;
        }

        public Set<String> getSupportedLayers() {
            return new TreeSet<>(supportedLayers);
        }
    }

    /**
     * Thrown if either the user requested model is not supported by the feature-packs,
     * or if it is impossible to determine default model.
     */
    public static class ModelNotDefinedException extends OperationException {

        private String model;
        private Set<String> supportedModels;

        public ModelNotDefinedException(String msg, Set<String> supportedModels) {
            super(msg);
            this.supportedModels = supportedModels;
        }

        public ModelNotDefinedException(String msg, String model, Set<String> supportedModels) {
            super(msg);
            this.model = model;
            this.supportedModels = supportedModels;
        }

        public String getModel() {
            return model;
        }

        public Set<String> getSupportedModels() {
            return new TreeSet<>(supportedModels);
        }
    }

    public static class FeaturePackAlreadyInstalledException extends OperationException {

        public FeaturePackAlreadyInstalledException(String msg) {
            super(msg);
        }
    }

    public static class ConfigurationNotFoundException extends OperationException {
        private final String model;
        private final String name;

        public ConfigurationNotFoundException(String msg, ConfigId id) {
            super(msg);
            model = id.getModel();
            name = id.getName();
        }

        public String getModel() {
            return model;
        }

        public String getName() {
            return name;
        }
    }

    // used in testing to inject mocks
    interface CandidateActionsFactory {
        PrepareCandidateAction newPrepareCandidateActionInstance(MavenSessionManager mavenSessionManager, ProsperoConfig prosperoConfig) throws OperationException;

        ApplyCandidateAction newApplyCandidateActionInstance(Path candidateDir) throws ProvisioningException, OperationException;
    }

    private static class DefaultCandidateActionsFactory implements CandidateActionsFactory {

        private final Path installDir;

        public DefaultCandidateActionsFactory(Path installDir) {
            this.installDir = installDir;
        }

        @Override
        public PrepareCandidateAction newPrepareCandidateActionInstance(
                MavenSessionManager mavenSessionManager, ProsperoConfig prosperoConfig) throws OperationException {
            return new PrepareCandidateAction(installDir, mavenSessionManager, prosperoConfig);
        }

        @Override
        public ApplyCandidateAction newApplyCandidateActionInstance(Path candidatePath)
                throws ProvisioningException, OperationException {
            return new ApplyCandidateAction(installDir, candidatePath);
        }
    }
}
