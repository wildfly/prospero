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

package org.wildfly.prospero.updates;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.galleon.util.HashUtils;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.Repository;
import org.wildfly.channel.RuntimeChannel;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.VersionResult;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.ChannelVersionChange;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.MetadataException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class UpdateFinder implements AutoCloseable {

    public static final int UPDATES_SEARCH_PARALLELISM = 10;

    private final ChannelSession channelSession;
    private final ExecutorService executorService;
    public UpdateFinder(ChannelSession channelSession) {
        this.channelSession = channelSession;
        this.executorService = Executors.newWorkStealingPool(UPDATES_SEARCH_PARALLELISM);
    }

    @Deprecated(forRemoval = true)
    public UpdateSet findUpdates(List<Artifact> artifacts) throws ArtifactResolutionException {
        try {
            return findUpdates(artifacts, Collections.emptyList());
        } catch (MetadataException e) {
            // need to wrap this exception so the method signature is not changed
            throw new RuntimeException(e);
        }
    }

    public UpdateSet findUpdates(List<Artifact> artifacts, List<ChannelVersion> channelVersions) throws ArtifactResolutionException, MetadataException {
        // use parallel executor to speed up the artifact resolution
        List<CompletableFuture<Optional<ArtifactChange>>> allPackages = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            final CompletableFuture<Optional<ArtifactChange>> cf = new CompletableFuture<>();
            executorService.submit(() -> {
                try {
                    final Optional<ArtifactChange> found = findUpdates(artifact);
                    cf.complete(found);
                } catch (Exception e) {
                    cf.completeExceptionally(e);
                }
            });
            allPackages.add(cf);
        }

        try {
            CompletableFuture.allOf(allPackages.toArray(new CompletableFuture[]{})).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ArtifactResolutionException) {
                throw (ArtifactResolutionException) e.getCause();
            } else {
                throw e;
            }
        }

        final List<ArtifactChange> updates = allPackages.stream()
                .map(cf ->cf.getNow(Optional.empty()))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());

        List<ChannelVersionChange> channelVersionChanges = findChannelVersions(channelVersions);

        return new UpdateSet(updates, channelVersionChanges);
    }

    @Override
    public void close() {
        this.executorService.shutdown();
    }

    private Optional<ArtifactChange> findUpdates(Artifact artifact) {
        final String latestVersion;
        final Optional<String> channelName;
        try {
            final VersionResult versionResult = channelSession.findLatestMavenArtifactVersion(artifact.getGroupId(),
                    artifact.getArtifactId(), artifact.getExtension(), artifact.getClassifier(), null);
            latestVersion = versionResult.getVersion();
            channelName = versionResult.getChannelName();

        } catch (UnresolvedMavenArtifactException e) {
            return Optional.of(ArtifactChange.removed(artifact));
        }

        final Artifact latest = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getExtension(), latestVersion);

        if (latestVersion == null || latest.getVersion().equals(artifact.getVersion())) {
            return Optional.empty();
        } else {
            return Optional.of(ArtifactChange.updated(artifact, latest, channelName.orElse(null)));
        }
    }

    private List<ChannelVersionChange> findChannelVersions(List<ChannelVersion> currentVersions) throws MetadataException {

        final List<ChannelVersionChange> res = new ArrayList<>();
        final List<RuntimeChannel> runtimeChannels = channelSession.getRuntimeChannels();

        final HashMap<String, ChannelVersion> oldManifests = new HashMap<>();
        // match the channels by name
        currentVersions.forEach(v -> oldManifests.put(v.getChannelName(), v));

        // name :: channel version info
        final HashMap<String, ChannelVersion> newManifests = new HashMap<>();
        // match the channels by name
        for (RuntimeChannel c : runtimeChannels) {
            final String location;
            final Channel channelDefinition = c.getChannelDefinition();
            if (channelDefinition.getManifestCoordinate() == null) {
                final String repos = channelDefinition.getRepositories().stream().map(Repository::getId).collect(Collectors.joining(","));
                location = channelDefinition.getNoStreamStrategy().toString().toLowerCase(Locale.ROOT) + "@[" + repos + "]";
            } else if (channelDefinition.getManifestCoordinate().getUrl() != null) {
                location = channelDefinition.getManifestCoordinate().getUrl().toExternalForm();
            } else {
                location = channelDefinition.getManifestCoordinate().getGroupId() + ":" + channelDefinition.getManifestCoordinate().getArtifactId();
            }

            final String physicalVersion;
            final ChannelVersion.Type type;
            if (channelDefinition.getManifestCoordinate() == null) {
                physicalVersion = null;
                type = ChannelVersion.Type.OPEN;
            } else if (channelDefinition.getManifestCoordinate().getUrl() != null) {
                try {
                    physicalVersion = HashUtils.hash(read(channelDefinition.getManifestCoordinate().getUrl()));
                } catch (IOException e) {
                    throw ProsperoLogger.ROOT_LOGGER.unableToDownloadFile(e);
                }
                type = ChannelVersion.Type.URL;
            } else {
                physicalVersion = channelDefinition.getManifestCoordinate().getVersion();
                type = ChannelVersion.Type.MAVEN;
            }


            final ChannelVersion cv = new ChannelVersion.Builder()
                    .setChannelName(channelDefinition.getName())
                    .setLocation(location)
                    .setLogicalVersion(c.getChannelManifest().getLogicalVersion())
                    .setPhysicalVersion(physicalVersion)
                    .setType(type)
                    .build();

            newManifests.put(channelDefinition.getName(), cv);
        }

        // handle changed and removed channels
        for (String channelName: oldManifests.keySet()) {
            final ChannelVersion oldVersion = oldManifests.get(channelName);
            final ChannelVersion newVersion = newManifests.get(channelName);
            if (ChannelVersion.Type.OPEN.equals(oldVersion.getType()) || !oldVersion.equals(newVersion)) {
                // Always add OPEN channels (channels that have noResolveStrategy==LATEST), because these channels can
                // bring updates everytime, even if the manifest location doesn't change. This was guarded by test case
                // UpdateFinderTest#listChannelVersionChangesOpenChannel, which is why I'm adding this check, but I'm
                // not sure if we should always list OPEN channels. The URL channels can in theory change any time
                // as well, but I don't like always listing is as "updated".
                // Otherwise, add maven based channels when the version has changed.
                res.add(new ChannelVersionChange(channelName, oldVersion, newVersion));
            }
        }

        // add versions were a channel was added since history
        for (String channelName: newManifests.keySet()) {
            if (oldManifests.containsKey(channelName)) {
                // skip, we've already handled those
                continue;
            }

            final ChannelVersion newVersion = newManifests.get(channelName);

            res.add(new ChannelVersionChange(channelName, null, newVersion));
        }

        return res;
    }

    private static String read(URL url) throws IOException {
        try(InputStream inputStream = url.openStream();
            OutputStream outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            return outputStream.toString();
        }
    }
}
