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

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.io.IOUtils;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.config.FeaturePackConfig;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.InvalidChannelMetadataException;
import org.wildfly.channel.Repository;
import org.wildfly.channel.maven.ChannelCoordinate;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.exceptions.ChannelDefinitionException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.NoChannelException;
import org.wildfly.prospero.galleon.FeaturePackLocationParser;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.model.KnownFeaturePack;

public class ProvisioningDefinition {

    /**
     * Galleon feature pack location. Can be either a well-known name (like "eap-8.0" or "wildfly") that references a predefined
     * combination of Galleon configuration and channels, a feature pack G:A(:V), or a standard Galleon feature pack location
     * string (see https://docs.wildfly.org/galleon/#_feature_pack_location).
     */
    private final String fpl;

    /**
     * This field would only contain channels parsed from the prosper-known-combinations.yaml file ("well-known feature packs"),
     * or a synthetic channel created when a manifest coordinate is given (via {@link Builder#setManifest(String)}).
     *
     * When channels are provided via {@link Builder#setChannelCoordinates(String)}, the channels would come in a form of
     * ChannelCoordinate(s).
     */
    private List<Channel> channels = new ArrayList<>();

    /**
     * Coordinates of channels to use for artifact resolution. Coordinate can be either GA(V) or URL.
     */
    private final List<ChannelCoordinate> channelCoordinates = new ArrayList<>();

    /**
     * Maven repositories to download artifacts from. If set, this list is going to override default repositories defined in the
     * channels.
     */
    private final List<Repository> overrideRepositories = new ArrayList<>();

    /**
     * Galleon provisioning.xml file URI. Alternative to {@link #fpl}. Galleon provisioning.xml file can be used to provide
     * detailed provisioning configuration.
     */
    private final URI definition;

    private ProvisioningDefinition(Builder builder) throws NoChannelException {
        this.overrideRepositories.addAll(builder.overrideRepositories);
        this.channelCoordinates.addAll(builder.channelCoordinates);

        if (builder.profile.isPresent()) {

            if (!KnownFeaturePacks.isWellKnownName(builder.profile.get())) { // if known FP name
                throw ProsperoLogger.ROOT_LOGGER.unknownInstallationProfile(builder.profile.get(), String.join(",", KnownFeaturePacks.getNames()));
            }
            KnownFeaturePack featurePackInfo = KnownFeaturePacks.getByName(builder.profile.get());
            this.fpl = null;
            this.definition = featurePackInfo.getGalleonConfiguration();
            if (this.channelCoordinates.isEmpty()) { // no channels provided by user
                if (!builder.manifests.isEmpty()) { // if manifest given, use it to create a channel
                    List<Repository> repositories = extractRepositoriesFromChannels(featurePackInfo.getChannels());
                    this.channels = builder.manifests.stream().map(m->composeChannelFromManifest(m, repositories)).collect(Collectors.toList());
                } else if (!featurePackInfo.getChannels().isEmpty()) { // if no manifest given, use channels from known FP
                    this.channels = featurePackInfo.getChannels();
                } else {
                    throw ProsperoLogger.ROOT_LOGGER.fplDefinitionDoesntContainChannel(builder.profile.get());
                }
            }
        } else {
            this.fpl = builder.fpl.orElse(null);
            this.definition = builder.definitionFile.orElse(null);
            if (this.channelCoordinates.isEmpty() && builder.manifests.isEmpty()) {
                throw ProsperoLogger.ROOT_LOGGER.predefinedFplOrChannelRequired(String.join(", ", KnownFeaturePacks.getNames()));
            } else if (!builder.manifests.isEmpty()) { // if manifest given, use it to create a channel
                if (overrideRepositories.isEmpty()) {
                    throw ProsperoLogger.ROOT_LOGGER.repositoriesMustBeSetWithManifest();
                }
                this.channels = builder.manifests.stream().map(m->composeChannelFromManifest(m, overrideRepositories)).collect(Collectors.toList());
            }
        }
    }

    private List<RemoteRepository> channelResolutionRepositories() {
        if (overrideRepositories.isEmpty()) {
            return List.of(new RemoteRepository.Builder("central", null, "https://repo1.maven.org/maven2").build());
        } else {
            return overrideRepositories.stream()
                    .map(RepositoryUtils::toRemoteRepository)
                    .collect(Collectors.toList());
        }
    }

    public String getFpl() {
        return fpl;
    }

    public URI getDefinition() {
        return definition;
    }

    public ProvisioningConfig toProvisioningConfig() throws MetadataException, ProvisioningException {
        if (fpl != null) {
            FeaturePackLocation loc = FeaturePackLocationParser.resolveFpl(getFpl());

            final FeaturePackConfig.Builder configBuilder = FeaturePackConfig.builder(loc);
            return ProvisioningConfig.builder().addFeaturePackDep(configBuilder.build()).build();
        } else if (definition != null) {
            try {
                return GalleonUtils.loadProvisioningConfig(definition);
            } catch (XMLStreamException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToParseConfigurationUri(definition, e);
            }
        } else {
            throw ProsperoLogger.ROOT_LOGGER.fplNorGalleonConfigWereSet();
        }
    }

    /**
     * Resolves channel coordinates into Channel instances.
     *
     * @param versionResolverFactory a VersionResolverFactory instance to perform the channel resolution
     * @return Channel instances
     */
    public List<Channel> resolveChannels(VersionResolverFactory versionResolverFactory) throws NoChannelException, ChannelDefinitionException {
        try {
            List<Channel> channels = new ArrayList<>(this.channels);

            final List<ChannelCoordinate> urlCoordinates = channelCoordinates.stream().filter(c -> c.getUrl() != null)
                    .collect(Collectors.toList());
            final List<ChannelCoordinate> gavCoordinates = channelCoordinates.stream().filter(c -> c.getUrl() == null)
                    .collect(Collectors.toList());

            if (!gavCoordinates.isEmpty()) {
                channels.addAll(versionResolverFactory.resolveChannels(gavCoordinates, channelResolutionRepositories()));
            }
            if (!urlCoordinates.isEmpty()) {
                // The URL-based coordinates are resolved in a way that bypasses the VersionResolverFactory, because the factory
                // only reads the first Channel from each document. We expect a document can contain multiple channels.
                channels.addAll(resolveUrlCoordinates(urlCoordinates));
            }

            channels = TemporaryRepositoriesHandler.overrideRepositories(channels, overrideRepositories);

            validateResolvedChannels(channels);
            return channels;
        } catch (InvalidChannelMetadataException e) {
            throw ProsperoLogger.ROOT_LOGGER.invalidChannel(e);
        } catch (MalformedURLException e) {
            // I believe the MalformedURLException is declared mistakenly by VersionResolverFactory#resolveChannels().
            throw new IllegalArgumentException(e);
        }
    }

    private static List<Channel> resolveUrlCoordinates(List<ChannelCoordinate> urlCoordinates) {
        ArrayList<Channel> channels = new ArrayList<>();
        for (ChannelCoordinate coord: urlCoordinates) {
            Objects.requireNonNull(coord.getUrl(), "This method only expects URL coordinates.");
            try {
                InputStream is = coord.getUrl().openStream();
                String yaml = IOUtils.toString(is, StandardCharsets.UTF_8);
                channels.addAll(ChannelMapper.fromString(yaml));
            } catch (IOException e) {
                InvalidChannelMetadataException ice = new InvalidChannelMetadataException(
                        "Failed to read channel " + coord.getUrl(), List.of(e.getLocalizedMessage()));
                ice.initCause(e);
                throw ice;
            }
        }
        return channels;
    }

    private static void validateResolvedChannels(List<Channel> channels) throws NoChannelException {
        if (channels.isEmpty()) {
            throw ProsperoLogger.ROOT_LOGGER.noChannelReference();
        }

        Optional<Channel> invalidChannel = channels.stream().filter(c -> c.getManifestCoordinate() == null).findFirst();
        if (invalidChannel.isPresent()) {
            throw ProsperoLogger.ROOT_LOGGER.noChannelManifestReference(invalidChannel.get().getName());
        }
    }

    private static List<Repository> extractRepositoriesFromChannels(List<Channel> channels) {
        return channels.stream().flatMap(c -> c.getRepositories().stream()).collect(Collectors.toList());
    }

    private static Channel composeChannelFromManifest(ChannelManifestCoordinate manifestCoordinate, List<Repository> repositories) {
        return new Channel(null, null, null, repositories, manifestCoordinate, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Optional<String> fpl = Optional.empty();
        private Optional<URI> definitionFile = Optional.empty();
        private List<Repository> overrideRepositories = Collections.emptyList();
        private List<ChannelManifestCoordinate> manifests = Collections.emptyList();
        private List<ChannelCoordinate> channelCoordinates = Collections.emptyList();
        private Optional<String> profile = Optional.empty();

        public ProvisioningDefinition build() throws MetadataException, NoChannelException {
            return new ProvisioningDefinition(this);
        }

        public Builder setFpl(String fpl) {
            this.fpl = Optional.ofNullable(fpl);
            return this;
        }

        public Builder setOverrideRepositories(List<Repository> repositories) {
            this.overrideRepositories = repositories;
            return this;
        }

        public Builder setManifest(String manifests) {
            if (manifests != null) {
                this.manifests = List.of(ArtifactUtils.manifestCoordFromString(manifests));
            }
            return this;
        }

        public Builder setManifests(List<String> manifests) {
            if (manifests != null) {
                this.manifests = manifests.stream().map(ArtifactUtils::manifestCoordFromString).collect(Collectors.toList());
            }
            return this;
        }

        public Builder setChannelCoordinates(String channelCoordinate) {
            return setChannelCoordinates(List.of(channelCoordinate));
        }

        public Builder setChannelCoordinates(List<String> channelCoordinates) {
            Objects.requireNonNull(channelCoordinates);
            this.channelCoordinates = new ArrayList<>();
            for (String coord: channelCoordinates) {
                this.channelCoordinates.add(ArtifactUtils.channelCoordFromString(coord));
            }
            return this;
        }

        public Builder setDefinitionFile(URI provisionDefinition) {
            this.definitionFile = Optional.ofNullable(provisionDefinition);
            return this;
        }

        public Builder setProfile(String profile) {
            this.profile = Optional.ofNullable(profile);
            return this;
        }
    }
}
