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

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.logging.Logger;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.ArtifactCache;
import org.wildfly.prospero.galleon.GalleonEnvironment;
import org.wildfly.prospero.galleon.GalleonFeaturePackAnalyzer;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.licenses.LicenseManager;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.metadata.ManifestVersionResolver;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.ProsperoConfig;
import org.wildfly.prospero.updates.CandidateProperties;
import org.wildfly.prospero.updates.CandidatePropertiesParser;
import org.wildfly.prospero.updates.MarkerFile;
import org.wildfly.prospero.updates.UpdateSet;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;

class PrepareCandidateAction implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PrepareCandidateAction.class.getName());
    private final InstallationMetadata metadata;
    private final ProsperoConfig prosperoConfig;
    private final MavenSessionManager mavenSessionManager;
    private final Path installDir;

    PrepareCandidateAction(Path installDir, MavenSessionManager mavenSessionManager, ProsperoConfig prosperoConfig)
            throws OperationException {
        this.metadata = InstallationMetadata.loadInstallation(installDir);
        this.installDir = installDir;
        this.prosperoConfig = prosperoConfig;
        this.mavenSessionManager = mavenSessionManager;
    }

    boolean buildCandidate(Path targetDir, GalleonEnvironment galleonEnv, ApplyCandidateAction.Type operation,
                           GalleonProvisioningConfig config) throws ProvisioningException, OperationException {
        return this.buildCandidate(targetDir, galleonEnv, operation, config, new UpdateSet(Collections.emptyList()));
    }

    /**
     * Builds an update/revert candidate server in {@code targetDir}. Uses the manifests resolved during
     * provisioning of the candidate to generate metadata.
     *
     * @param targetDir
     * @param galleonEnv
     * @param operation
     * @param config
     * @param updateSet
     * @return
     * @throws ProvisioningException
     * @throws OperationException
     */
    boolean buildCandidate(Path targetDir, GalleonEnvironment galleonEnv, ApplyCandidateAction.Type operation,
                           GalleonProvisioningConfig config, UpdateSet updateSet) throws ProvisioningException, OperationException {
        return this.buildCandidate(targetDir, galleonEnv, operation, config, updateSet, () -> getManifestVersionRecord(galleonEnv));
    }

    /**
     * Builds an update/revert candidate server in {@code targetDir}.
     *
     * @param targetDir
     * @param galleonEnv
     * @param operation
     * @param config
     * @param updateSet
     * @param manifestVersionRecordSupplier - provides information about manifest versions that should be used to generate
     *                                      metadata and cache after provisioning
     * @return
     * @throws ProvisioningException
     * @throws OperationException
     */
    boolean buildCandidate(Path targetDir, GalleonEnvironment galleonEnv, ApplyCandidateAction.Type operation,
                           GalleonProvisioningConfig config, UpdateSet updateSet,
                           Supplier<Optional<ManifestVersionRecord>> manifestVersionRecordSupplier) throws ProvisioningException, OperationException {
        Objects.requireNonNull(manifestVersionRecordSupplier);

        doBuildUpdate(targetDir, galleonEnv, config, manifestVersionRecordSupplier);

        try {
            final SavedState savedState = metadata.getRevisions().get(0);
            new MarkerFile(savedState.getName(), operation).write(targetDir);
            writeCandidateProperties(updateSet, targetDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return true;
    }

    private void doBuildUpdate(Path targetDir, GalleonEnvironment galleonEnv, GalleonProvisioningConfig provisioningConfig,
                               Supplier<Optional<ManifestVersionRecord>> manifestVersionResolver)
            throws ProvisioningException, OperationException {
        final Provisioning provMgr = galleonEnv.getProvisioning();
        try {
            GalleonUtils.executeGalleon((options) -> {
                        options.put(Constants.EXPORT_SYSTEM_PATHS, "true");
                        provMgr.provision(provisioningConfig, options);
                    },
                    mavenSessionManager.getProvisioningRepo().toAbsolutePath());
        } catch (UnresolvedMavenArtifactException e) {
            throw new ArtifactResolutionException(ProsperoLogger.ROOT_LOGGER.unableToResolve(), e, e.getUnresolvedArtifacts(),
                    e.getAttemptedRepositories(), mavenSessionManager.isOffline());
        }


        final Optional<ManifestVersionRecord> manifestRecord = manifestVersionResolver.get();

        if (LOG.isTraceEnabled()) {
            LOG.tracef("Recording manifests: %s", manifestRecord.orElse(new ManifestVersionRecord()));
        }
        manifestRecord.ifPresent(rec -> cacheManifests(rec, targetDir));
        writeProsperoMetadata(targetDir, galleonEnv.getChannelSession().getRecordedChannel(), prosperoConfig.getChannels(),
                manifestRecord);

        try {
            final GalleonFeaturePackAnalyzer galleonFeaturePackAnalyzer = new GalleonFeaturePackAnalyzer(galleonEnv.getChannels(), mavenSessionManager, null);
            galleonFeaturePackAnalyzer.cacheGalleonArtifacts(targetDir, provisioningConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            new LicenseManager().copyIfExists(installDir, targetDir);
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToWriteFile(installDir.resolve(LicenseManager.LICENSES_FOLDER), e);
        }
    }

    private Optional<ManifestVersionRecord> getManifestVersionRecord(GalleonEnvironment galleonEnv) {
        try {
            return Optional.of(ManifestVersionResolver.getCurrentVersions(galleonEnv.getChannelSession()));
        } catch (IOException e) {
            ProsperoLogger.ROOT_LOGGER.debug("Unable to retrieve current manifest versions", e);
            return Optional.empty();
        }
    }

    private void cacheManifests(ManifestVersionRecord manifestRecord, Path installDir) {
        try {
            final RepositorySystem system = mavenSessionManager.newRepositorySystem();
            final DefaultRepositorySystemSession session = mavenSessionManager.newRepositorySystemSession(system);

            ArtifactCache.getInstance(installDir).cache(manifestRecord, session.getLocalRepositoryManager());
        } catch (IOException e) {
            ProsperoLogger.ROOT_LOGGER.debug("Unable to record manifests in the internal cache", e);
        }
    }

    @Override
    public void close() {
        metadata.close();
    }

    private void writeProsperoMetadata(Path home, ChannelManifest manifest, List<Channel> channels, Optional<ManifestVersionRecord> manifestVersions) throws MetadataException {
        try (InstallationMetadata installationMetadata = InstallationMetadata.newInstallation(home, manifest,
                new ProsperoConfig(channels), manifestVersions)) {
            installationMetadata.recordProvision(true, false);
        }
    }

    private void writeCandidateProperties(UpdateSet updateSet, Path installationDir) {
        final List<CandidateProperties.ComponentUpdate> updates = new ArrayList<>();

        for (ArtifactChange artifactChange : updateSet.getArtifactUpdates()) {
            final String[] gaSplit = artifactChange.getArtifactName().split(":");
            if (artifactChange.getChannelName().isPresent()) {
                updates.add(new CandidateProperties.ComponentUpdate(gaSplit[0], gaSplit[1], artifactChange.getChannelName().get()));
            }
        }

        final Path candidateFile = installationDir.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ApplyCandidateAction.CANDIDATE_CHANNEL_NAME_LIST);
        try {
            CandidatePropertiesParser.write(new CandidateProperties(updates), candidateFile);
            ProsperoLogger.ROOT_LOGGER.channelNamesWrittenToFile(candidateFile.toFile().getAbsolutePath());
        } catch (IOException e) {
            ProsperoLogger.ROOT_LOGGER.unableToWriteChannelNamesToFile(candidateFile.toFile().getAbsolutePath(),e);
        }
    }
}
