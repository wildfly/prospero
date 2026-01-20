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

package org.wildfly.prospero.galleon;

import org.apache.commons.io.FileUtils;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.logging.Logger;
import org.wildfly.channel.ArtifactTransferException;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ChannelMetadataCoordinate;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.InvalidChannelMetadataException;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.channel.spi.MavenVersionsResolver;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.exceptions.ChannelDefinitionException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.UnresolvedChannelMetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.galleon.Constants;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.util.PathsUtils;

public class GalleonEnvironment implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(GalleonEnvironment.class.getName());

    public static final String TRACK_JBMODULES = "JBMODULES";
    public static final String TRACK_JBEXAMPLES = "JBEXTRACONFIGS";
    public static final String TRACK_JB_ARTIFACTS_RESOLVE = "JB_ARTIFACTS_RESOLVE";

    public static final String TRACK_RESOLVING_VERSIONS = "RESOLVING_VERSIONS";
    private final Provisioning provisioning;
    private final MavenRepoManager repositoryManager;
    private final ChannelSession channelSession;
    private final List<Channel> channels;
    private Path restoreManifestPath = null;

    private boolean resetGalleonLineEndings = true;

    private GalleonEnvironment(Builder builder) throws ProvisioningException, MetadataException, ChannelDefinitionException, UnresolvedChannelMetadataException {
        Optional<Console> console = Optional.ofNullable(builder.console);
        Optional<ChannelManifest> restoreManifest = Optional.ofNullable(builder.manifest);
        if (restoreManifest.isPresent()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Replacing channel manifests with restore manifest");
            }
            channels = replaceManifestWithRestoreManifests(builder, restoreManifest);
        } else {
            channels = builder.channels;
        }
        List<Channel> substitutedChannels = new ArrayList<>();
        final ChannelManifestSubstitutor substitutor = new ChannelManifestSubstitutor(Map.of("installation.home", builder.installDir.toString()));
        // substitute any properties found in URL of ChannelManifestCoordinate.
        for (Channel channel : channels) {
            substitutedChannels.add(substitutor.substitute(channel));
        }

        final RepositorySystem system = builder.mavenSessionManager.newRepositorySystem();
        final DefaultRepositorySystemSession session = builder.mavenSessionManager.newRepositorySystemSession(system);
        final Path sourceServerPath = builder.sourceServerPath == null? builder.installDir:builder.sourceServerPath;
        final RepositoryFactory repositoryFactory = new RepositoryFactory(List.of(
                new MavenProxyHandler(), new RepositoryAuthenticationHandler(builder.mavenSettings)));

        MavenVersionsResolver.Factory factory;
        try {
            factory = new CachedVersionResolverFactory(new VersionResolverFactory(system, session, repositoryFactory),
                    sourceServerPath, system, session);
        } catch (IOException e) {
            ProsperoLogger.ROOT_LOGGER.debug("Unable to read artifact cache, falling back to Maven resolver.", e);
            factory = new VersionResolverFactory(system, session, repositoryFactory);
        }

        channelSession = initChannelSession(session, factory);

        if (restoreManifest.isPresent()) {
            // try to load the manifests used by the state that's being reverted to
            // they have to be in the maven cache for later version resolution
            final ManifestVersionRecord manifestVersions = new ManifestVersionRecord("1.0.0",
                    builder.restoredManifestVersions, Collections.emptyList(), Collections.emptyList());
            storeOriginalChannelManifestAsResolved(builder, factory, manifestVersions.getMavenManifests());
        }

        if (builder.artifactDirectResolve) {
            repositoryManager = new MavenArtifactDirectResolverRepositoryManager(channelSession);
        } else {
            if (restoreManifest.isEmpty()) {
                repositoryManager = new ChannelMavenArtifactRepositoryManager(channelSession);
            } else {
                repositoryManager = new ChannelMavenArtifactRepositoryManager(channelSession, restoreManifest.get());
            }
        }

        if (System.getProperty(Constants.PROP_LINUX_LINE_ENDINGS) == null) {
            System.setProperty(Constants.PROP_LINUX_LINE_ENDINGS, "true");
        } else {
            resetGalleonLineEndings = false;
        }

        final GalleonBuilder provider = GalleonUtils.newGalleonBuilder(repositoryManager, builder.fpTracker);
        provisioning = GalleonUtils.newProvisioning(provider, builder.installDir, builder.config, PathsUtils.getProvisioningXml(builder.installDir), builder.useDefaultCore);

        Stream.of(Constants.TRACK_LAYOUT_BUILD,
                  Constants.TRACK_PACKAGES,
                  Constants.TRACK_CONFIGS,
                  TRACK_JBMODULES,
                  TRACK_JBEXAMPLES)
                .forEach(t->provisioning.setProgressCallback(t, new GalleonCallbackAdapter(console.orElse(null), t)));

        final DownloadsCallbackAdapter callback = new DownloadsCallbackAdapter(console.orElse(null));
        session.setTransferListener(callback);
        provisioning.setProgressCallback(TRACK_JB_ARTIFACTS_RESOLVE, callback);
    }

    private static void storeOriginalChannelManifestAsResolved(Builder builder, MavenVersionsResolver.Factory factory,
                                                               List<ManifestVersionRecord.MavenManifest> mavenManifests) {

        // attempt to resolve the manifests we're reverting to. Doing so will record the manifest in the ResolvedArtifactsStore
        try (ChannelSession tempSession = new ChannelSession(builder.channels, factory)) {
            for (ManifestVersionRecord.MavenManifest mavenManifest : mavenManifests) {
                try {
                    if (LOG.isTraceEnabled()) {
                        LOG.tracef("Trying to resolve %s.", mavenManifest);
                    }
                    // don't use the GalleonEnvironment#channelSession - we don't want to inject those into recorded manifest, just local maven cache
                    tempSession.resolveDirectMavenArtifact(
                            mavenManifest.getGroupId(),
                            mavenManifest.getArtifactId(),
                            ChannelManifest.EXTENSION,
                            ChannelManifest.CLASSIFIER,
                            mavenManifest.getVersion()
                    );
                } catch (ArtifactTransferException e) {
                    // catch the error here so we can ignore it
                    ProsperoLogger.ROOT_LOGGER.debugf(e, "Unable to resolve manifest %s:%s:%s for maven cache",
                            mavenManifest.getGroupId(), mavenManifest.getArtifactId(), mavenManifest.getVersion());
                }
            }
        } catch (ArtifactTransferException e) {
            // ignore; we just won't cache this manifest later
            LOG.debug("Unable to resolve restored manifest", e);
        }
    }

    private List<Channel> replaceManifestWithRestoreManifests(Builder builder, Optional<ChannelManifest> restoreManifest) throws ProvisioningException {
        ChannelManifestCoordinate manifestCoord;
        try {
            restoreManifestPath = Files.createTempFile("prospero-restore-manifest", "yaml");
            restoreManifestPath.toFile().deleteOnExit();
            if (LOG.isDebugEnabled()) {
                LOG.debugf("Created temporary restore manifest file at %s", restoreManifestPath);
            }
            Files.writeString(restoreManifestPath, ChannelManifestMapper.toYaml(restoreManifest.get()));
            manifestCoord = new ChannelManifestCoordinate(restoreManifestPath.toUri().toURL());
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToCreateTemporaryFile(e);
        }
        List<Channel> channels = builder.channels.stream()
                .map(c-> new Channel(
                        c.getSchemaVersion(),
                        c.getName(),
                        c.getDescription(),
                        c.getVendor(),
                        c.getRepositories(),
                        manifestCoord,
                        c.getBlocklistCoordinate(),
                        c.getNoStreamStrategy()))
                .collect(Collectors.toList());
        return channels;
    }

    private ChannelSession initChannelSession(DefaultRepositorySystemSession session, MavenVersionsResolver.Factory factory) throws UnresolvedChannelMetadataException, ChannelDefinitionException {
        final ChannelSession channelSession;
        try {
            channelSession = new ChannelSession(channels, factory);
        } catch (UnresolvedMavenArtifactException e) {
            final Set<ChannelMetadataCoordinate> missingArtifacts = e.getUnresolvedArtifacts().stream()
                    .map(a -> new ChannelMetadataCoordinate(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getClassifier(), a.getExtension()))
                    .collect(Collectors.toSet());

            throw new UnresolvedChannelMetadataException(ProsperoLogger.ROOT_LOGGER.unableToResolve(), e, missingArtifacts,
                    e.getAttemptedRepositories(), session.isOffline());
        } catch (InvalidChannelMetadataException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                final String url = e.getValidationMessages().get(0);
                try {
                    final ChannelMetadataCoordinate coord = new ChannelMetadataCoordinate(new URL(url));
                    throw new UnresolvedChannelMetadataException(ProsperoLogger.ROOT_LOGGER.unableToResolve(), e, Set.of(coord), Collections.emptySet(), session.isOffline());
                } catch (MalformedURLException ex) {
                    throw ProsperoLogger.ROOT_LOGGER.invalidManifest(e);
                }
            }
            throw ProsperoLogger.ROOT_LOGGER.invalidManifest(e);
        }
        return channelSession;
    }

    public Provisioning getProvisioning() {
        return provisioning;
    }

    public ChannelSession getChannelSession() {
        return channelSession;
    }

    public MavenRepoManager getRepositoryManager() {
        return repositoryManager;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    @Override
    public void close() {
        if (resetGalleonLineEndings) {
            System.clearProperty(Constants.PROP_LINUX_LINE_ENDINGS);
        }
        if (restoreManifestPath != null) {
            FileUtils.deleteQuietly(restoreManifestPath.toFile());
        }
        provisioning.close();
    }

    public static Builder builder(Path installDir, List<Channel> channels, MavenSessionManager mavenSessionManager, boolean useDefaultCore) {
        Objects.requireNonNull(installDir);
        Objects.requireNonNull(channels);
        Objects.requireNonNull(mavenSessionManager);

        return new Builder(installDir, channels, mavenSessionManager, useDefaultCore);
    }

    public static class Builder {

        private final Path installDir;
        private final List<Channel> channels;
        private final MavenSessionManager mavenSessionManager;
        private Console console;
        private ChannelManifest manifest;
        private Consumer<String> fpTracker;
        private Path sourceServerPath;
        private boolean artifactDirectResolve;
        private List<ManifestVersionRecord.MavenManifest> restoredManifestVersions;
        private final boolean useDefaultCore;
        private Settings mavenSettings;

        private GalleonProvisioningConfig config;

        private Builder(Path installDir, List<Channel> channels, MavenSessionManager mavenSessionManager, boolean useDefaultCore) {
            this.installDir = installDir;
            this.channels = channels;
            this.mavenSessionManager = mavenSessionManager;
            this.useDefaultCore = useDefaultCore;
        }

        public Builder setConsole(Console console) {
            this.console = console;
            return this;
        }

        public Builder setProvisioningConfig(GalleonProvisioningConfig config) {
            this.config = config;
            return this;
        }

        public Builder setRestoreManifest(ChannelManifest manifest) {
            this.manifest = manifest;
            return this;
        }

        /**
         * use the {@code manifest} to resolve artifact versions instead of defined channels. If {@code manifestVersions}
         * is provided, they will be pre-loaded for maven cache.
         *
         * @param manifest
         * @param manifestVersions
         * @return
         */
        public Builder setRestoreManifest(ChannelManifest manifest, ManifestVersionRecord manifestVersions) {
            this.restoredManifestVersions = manifestVersions == null ? Collections.emptyList() : manifestVersions.getMavenManifests();
            return this.setRestoreManifest(manifest);
        }

        public Builder setResolvedFpTracker(Consumer<String> fpTracker) {
            this.fpTracker = fpTracker;
            return this;
        }

        public GalleonEnvironment build() throws ProvisioningException, OperationException {
            return new GalleonEnvironment(this);
        }

        public Builder setSourceServerPath(Path sourceServerPath) {
            this.sourceServerPath = sourceServerPath;
            return this;
        }

        /**
         * Resolving the artifacts directly without checking the channel manifest or not.
         *
         * @param artifactDirectResolve true if resolving directly, false otherwise.
         * @return this for fluent api
         */
        public Builder setArtifactDirectResolve(boolean artifactDirectResolve) {
            this.artifactDirectResolve = artifactDirectResolve;
            return this;
        }

        public Builder setMavenSettings(Settings mavenSettings) {
            this.mavenSettings = mavenSettings;
            return this;
        }

        public Path getSourceServerPath() {
            return sourceServerPath;
        }
    }
}
