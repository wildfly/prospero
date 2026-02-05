package org.wildfly.prospero.updates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.MavenCoordinate;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.api.exceptions.MetadataException;


/**
 * A Maven resolver to find manifest versions available in a given channel.
 */
public class ChannelUpdateFinder {
    private final RepositorySystemSession session;
    private final RepositorySystem system;

    public ChannelUpdateFinder(RepositorySystem system, RepositorySystemSession session) {
        this.session = session;
        this.system = system;
    }

    /**
     * Lists all manifests versions that can be found for the channel.
     *
     * NOTE: only Maven channels can be passed to this method. Any other channel will result in an exception to be thrown.
     *
     * @param channel - the channel to find the versions for
     * @return - list of all the possible channel manifest versions
     * @throws MetadataException - if unable to retrieve or parse the manifest information
     */
    public Collection<ChannelVersion> findAvailableChannelVersions(Channel channel)
            throws MetadataException {
        Objects.requireNonNull(channel);

        return retrieveArtifactVersions(channel, null);
    }

    /**
     * Lists manifests versions that can be found for the channel and are newer then {@code channelVersion}.
     *
     * NOTE: only Maven channels can be passed to this method. Any other channel will result in an exception to be thrown.
     *
     * @param channel - the channel to find the versions for
     * @return - list of all the channel manifest versions newer then requested {@code channelVersion}
     * @throws MetadataException - if unable to retrieve or parse the manifest information
     */
    public Collection<ChannelVersion> findNewerChannelVersions(Channel channel, String channelVersion)
            throws MetadataException {
        Objects.requireNonNull(channel);
        Objects.requireNonNull(channelVersion);

        return retrieveArtifactVersions(channel, channelVersion);
    }

    private ArrayList<ChannelVersion> retrieveArtifactVersions(Channel channel, String channelVersion) throws MetadataException {
        if (channel.getManifestCoordinate() == null || channel.getManifestCoordinate().getMaven() == null) {
            throw new RuntimeException("The channel %s needs to have a maven manifest to be able to retrieve channel updates.".formatted(channel.getName()));
        }

        final List<ArtifactResult> artifactResults = resolveArtifactsFromMaven(channel, toArtifact(channel, channelVersion));

        return mapArtifactsToVersions(channel, artifactResults);
    }

    private static ArrayList<ChannelVersion> mapArtifactsToVersions(Channel channel, List<ArtifactResult> artifactResults) throws MetadataException {
        final ArrayList<ChannelVersion> channelVersions = new ArrayList<>();
        for (ArtifactResult artifactResult : artifactResults) {
            String location;
            if (channel.getManifestCoordinate().getMaven() != null) {
                MavenCoordinate coord = channel.getManifestCoordinate().getMaven();
                location = coord.getGroupId() + ":" + coord.getArtifactId();
                if (coord.getVersion() != null) {
                    location = location + ":" + coord.getVersion();
                }
            } else if (channel.getManifestCoordinate().getUrl() != null) {
                location = channel.getManifestCoordinate().getUrl().toExternalForm();
            } else {
                throw new IllegalStateException("Unable to resolve channel manifest coordinate for channel " + channel.getName());
            }
            channelVersions.add(new ChannelVersion.Builder()
                    .setChannelName(channel.getName())
                    .setLocation(location)
                    .setPhysicalVersion(artifactResult.getArtifact().getVersion())
                    .setLogicalVersion(readLogicalName(artifactResult))
                    .build());
        }
        return channelVersions;
    }

    private List<ArtifactResult> resolveArtifactsFromMaven(Channel channel, Artifact artifact) throws MetadataException {
        final List<RemoteRepository> repos = channel.getRepositories().stream()
                .map(RepositoryUtils::toRemoteRepository)
                .toList();
        try {
            final VersionRangeResult versionRangeResult = getMavenVersions(artifact, repos);

            return downloadMavenArtifacts(versionRangeResult, artifact, repos);
        } catch (VersionRangeResolutionException | ArtifactResolutionException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToResolveChannelVersionInformation(channel.getName(),
                    artifact.getGroupId(), artifact.getArtifactId(),
                    repos.stream().map(RemoteRepository::getUrl).collect(Collectors.joining()), e);
        }
    }

    private static String readLogicalName(ArtifactResult artifactResult) throws MetadataException {
        final Path downloadedManifest = artifactResult.getArtifact().getFile().toPath();
        try {
            final ChannelManifest manifest = ChannelManifestMapper.fromString(Files.readString(downloadedManifest));
            return manifest.getLogicalVersion();
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToReadFile(downloadedManifest, e);
        }
    }

    private List<ArtifactResult> downloadMavenArtifacts(VersionRangeResult versionRangeResult, Artifact artifact, List<RemoteRepository> repos) throws ArtifactResolutionException {
        final ArrayList<ArtifactRequest> requests = versionRangeResult.getVersions().stream()
                .map(version -> new ArtifactRequest(artifact.setVersion(version.toString()), repos, null))
                .collect(Collectors.toCollection(ArrayList::new));
        return system.resolveArtifacts(session, requests);
    }

    private VersionRangeResult getMavenVersions(Artifact artifact, List<RemoteRepository> repos) throws VersionRangeResolutionException {
        final VersionRangeRequest req = new VersionRangeRequest(artifact, repos, null);
        return system.resolveVersionRange(session, req);
    }

    private static Artifact toArtifact(Channel channel, String channelVersion) {
        final ChannelManifestCoordinate coord = channel.getManifestCoordinate();
        return new DefaultArtifact(
                coord.getGroupId(),
                coord.getArtifactId(),
                coord.getClassifier(),
                coord.getExtension(),
                createVersionRange(channelVersion));
    }

    private static String createVersionRange(String channelVersion) {
        return "(" + (channelVersion == null ? "0" : channelVersion) + ",)";
    }
}
