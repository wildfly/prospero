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

package org.wildfly.prospero.actions;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.galleon.universe.maven.MavenUniverseException;
import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.Repository;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.api.TemporaryRepositoriesHandler;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.api.exceptions.StreamNotFoundException;
import org.wildfly.prospero.galleon.ArtifactCache;
import org.wildfly.prospero.galleon.GalleonFeaturePackAnalyzer;
import org.wildfly.prospero.galleon.GalleonEnvironment;
import org.wildfly.prospero.galleon.GalleonUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.wildfly.prospero.licenses.License;
import org.wildfly.prospero.licenses.LicenseManager;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.metadata.ManifestVersionResolver;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.wfchannel.MavenSessionManager;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;

public class ProvisioningAction {

    private static final String CHANNEL_NAME_PREFIX = "channel-";
    private final MavenSessionManager mavenSessionManager;
    private final Path installDir;
    private final Console console;
    private final LicenseManager licenseManager;
    private final MavenOptions mvnOptions;

    public ProvisioningAction(Path installDir, MavenOptions mvnOptions, Console console) throws ProvisioningException {
        this.installDir = InstallFolderUtils.toRealPath(installDir);
        this.console = console;
        this.mvnOptions = mvnOptions;
        this.mavenSessionManager = new MavenSessionManager(mvnOptions);
        this.licenseManager = new LicenseManager();

        verifyInstallDir(installDir);
    }

    /**
     * Provision installation according to given ProvisioningDefinition.
     *
     * <b>NOTE:</b> All required licenses are assumed to be accepted by calling this method.
     *
     * @param provisioningConfig prospero provisioning definition
     * @param channels list of channels to resolve installed artifacts
     */
    public void provision(GalleonProvisioningConfig provisioningConfig, List<Channel> channels)
            throws MalformedURLException, ProvisioningException, OperationException {
        provision(provisioningConfig, channels, Collections.emptyList());
    }

    /**
     * Provision installation according to given ProvisioningDefinition.
     * The {@code overrideRepositories} replace any repositories defined in {@code channels} for duration of this operation.
     * They are not persisted in the provisioned configuration.
     *
     * <b>NOTE:</b> All required licenses are assumed to be accepted by calling this method.
     *
     * @param provisioningConfig prospero provisioning definition
     * @param channels list of channels to resolve installed artifacts
     * @param overwriteRepositories list of repositories to resolve installed artifacts from. They do not alter persisted channel definitions.
     */
    public void provision(GalleonProvisioningConfig provisioningConfig, List<Channel> channels, List<Repository> overwriteRepositories)
            throws ProvisioningException, OperationException, MalformedURLException {

        provisionWithChannels(provisioningConfig, channels, TemporaryRepositoriesHandler.overrideRepositories(channels, overwriteRepositories));
    }

    public void provisionWithChannels(GalleonProvisioningConfig provisioningConfig,
                                      List<Channel> channels,
                                      List<Channel> overrideChannels)
            throws ProvisioningException, OperationException {
        ProsperoLogger.ROOT_LOGGER.startingProvision(installDir);
        channels = enforceChannelNames(channels);

        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.debug("Provisioning definition: " + provisioningConfig.toString());
            ProsperoLogger.ROOT_LOGGER.debug("Using channels: " + channels.stream().map(Channel::toString).collect(Collectors.joining(",")));
        }
        final List<Channel> recordedChannels = new ArrayList<>(channels);

        if (!overrideChannels.isEmpty()) {
            channels = overrideChannels;
        }

        try (GalleonEnvironment galleonEnv = GalleonEnvironment
                .builder(installDir, channels, mavenSessionManager, false)
                .setConsole(console)
                .setProvisioningConfig(provisioningConfig)
                .build()) {

            try {
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debug("Starting Galleon provisioning");
                }

                GalleonUtils.executeGalleon(options -> galleonEnv.getProvisioning().provision(provisioningConfig, options),
                        mavenSessionManager.getProvisioningRepo().toAbsolutePath());
            } catch (UnresolvedMavenArtifactException e) {
                throw new ArtifactResolutionException(ProsperoLogger.ROOT_LOGGER.unableToResolve(), e, e.getUnresolvedArtifacts(),
                        e.getAttemptedRepositories(), mavenSessionManager.isOffline());
            }

            final ManifestVersionRecord manifestRecord;
            try {
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debug("Resolving installed manifest versions");
                }

                manifestRecord = ManifestVersionResolver.getCurrentVersions(galleonEnv.getChannelSession());
            } catch (IOException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToDownloadFile(e);
            }

            cacheManifests(manifestRecord);

            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debug("Recording installed metadata");
            }

            try {
                ProsperoMetadataUtils.recordProvisioningDefinition(installDir);
            } catch (IOException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToSaveConfiguration(
                        installDir.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML), e);
            }
            writeProsperoMetadata(installDir, galleonEnv.getChannelSession().getRecordedChannel(), recordedChannels, manifestRecord);
        }


        try {
            final GalleonFeaturePackAnalyzer galleonFeaturePackAnalyzer = new GalleonFeaturePackAnalyzer(channels, mavenSessionManager);

            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debug("Recording accepted licenses");
            }
            try {
                // all agreements are implicitly accepted at this point
                licenseManager.recordAgreements(getPendingLicenses(provisioningConfig, galleonFeaturePackAnalyzer), installDir);
            } catch (IOException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToWriteFile(installDir.resolve(LicenseManager.LICENSES_FOLDER), e);
            }

            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debug("Updating galleon cache");
            }
            galleonFeaturePackAnalyzer.cacheGalleonArtifacts(installDir, provisioningConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ProsperoLogger.ROOT_LOGGER.provisioningComplete(installDir);
    }

    private void cacheManifests(ManifestVersionRecord manifestRecord) {
        try {
            final RepositorySystem system = mavenSessionManager.newRepositorySystem();
            final DefaultRepositorySystemSession session = mavenSessionManager.newRepositorySystemSession(system);

            ArtifactCache.getInstance(installDir).cache(manifestRecord, session.getLocalRepositoryManager());
        } catch (IOException e) {
            ProsperoLogger.ROOT_LOGGER.debug("Unable to record manifests in the internal cache", e);
        }
    }

    /**
     * List agreements and licenses that need to be accepted before installing the required Feature Packs.
     *
     * @param provisioningConfig - list of Feature Packs being installed
     * @param channels - list of channels used to resolve the Feature Packs
     * @return - list of {@code License}, or empty list if no licenses were required
     */
    public List<License> getPendingLicenses(GalleonProvisioningConfig provisioningConfig, List<Channel> channels) throws OperationException {
        Objects.requireNonNull(provisioningConfig);
        Objects.requireNonNull(channels);

        final GalleonFeaturePackAnalyzer exporter = new GalleonFeaturePackAnalyzer(channels, mavenSessionManager);
        return getPendingLicenses(provisioningConfig, exporter);
    }

    private List<License> getPendingLicenses(GalleonProvisioningConfig provisioningConfig, GalleonFeaturePackAnalyzer exporter) throws OperationException {
        try {
            final Set<String> featurePacks = exporter.getFeaturePacks(installDir, provisioningConfig);
            return licenseManager.getLicenses(featurePacks);
        } catch (MavenUniverseException e) {
            if (e.getCause() instanceof org.eclipse.aether.resolution.ArtifactResolutionException) {
                throw wrapAetherException((org.eclipse.aether.resolution.ArtifactResolutionException) e.getCause());
            } else if (e.getCause() instanceof UnresolvedMavenArtifactException && e.getMessage().contains("(no stream found)")) {
                final UnresolvedMavenArtifactException cause = (UnresolvedMavenArtifactException) e.getCause();
                throw new StreamNotFoundException(ProsperoLogger.ROOT_LOGGER.streamNotFound(),
                        e,
                        cause.getUnresolvedArtifacts(),
                        cause.getAttemptedRepositories(),
                        mavenSessionManager.isOffline());
            } else {
                // org.wildfly.channel.UnresolvedMavenArtifactException
                throw new ArtifactResolutionException(e.getMessage(), e);
            }
        } catch (IOException | ProvisioningException e) {
            throw new RuntimeException(e);
        }
    }

    private void writeProsperoMetadata(Path home, ChannelManifest manifest, List<Channel> channels,
                                       ManifestVersionRecord manifestVersions) throws MetadataException {
        try (InstallationMetadata installationMetadata = InstallationMetadata.newInstallation(home, manifest,
                new ProsperoConfig(channels, mvnOptions), Optional.of(manifestVersions))) {
            installationMetadata.recordProvision(true, true);
        }
    }

    private List<Channel> enforceChannelNames(List<Channel> newChannels) {
        final AtomicInteger channelCounter = new AtomicInteger(0);
        return newChannels.stream().map(c->{
            if (StringUtils.isEmpty(c.getName())) {
                return new Channel(c.getSchemaVersion(), CHANNEL_NAME_PREFIX + channelCounter.getAndIncrement(), c.getDescription(),
                        c.getVendor(), c.getRepositories(),
                        c.getManifestCoordinate(), c.getBlocklistCoordinate(), c.getNoStreamStrategy());
            } else {
                return c;
            }
        }).collect(Collectors.toList());
    }

    private static void verifyInstallDir(Path directory) {
        if (Files.exists(directory)) {
            InstallFolderUtils.verifyIsEmptyDir(directory);
        } else {
            InstallFolderUtils.verifyIsWritable(directory);
        }
    }

    private ArtifactResolutionException wrapAetherException(org.eclipse.aether.resolution.ArtifactResolutionException e) throws ArtifactResolutionException {
        final List<ArtifactResult> results = e.getResults();
        final Set<ArtifactCoordinate> missingArtifacts = results.stream()
                .filter(r -> !r.isResolved())
                .map(r -> r.getArtifact())
                .map(a -> new ArtifactCoordinate(a.getGroupId(), a.getArtifactId(), a.getExtension(), a.getClassifier(), a.getVersion()))
                .collect(Collectors.toSet());

        final Set<Repository> repositories = results.stream()
                .filter(r -> !r.isResolved())
                .flatMap(r -> r.getRequest().getRepositories().stream())
                .map(RepositoryUtils::toChannelRepository)
                .collect(Collectors.toSet());

        return new ArtifactResolutionException(ProsperoLogger.ROOT_LOGGER.unableToResolve(), e, missingArtifacts, repositories, mavenSessionManager.isOffline());
    }
}
