package org.wildfly.prospero.galleon.artifactfiltering;

import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.channel.ArtifactTransferException;
import org.wildfly.channel.ChannelMetadataCoordinate;
import org.wildfly.channel.spi.MavenVersionsResolver;

import java.io.File;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class FilteringVersionResolver implements MavenVersionsResolver  {

    private final MavenVersionsResolver fallbackResolver;
    private final Predicate<ArtifactVersion> predicate;

    public FilteringVersionResolver(Predicate<ArtifactVersion> predicate, MavenVersionsResolver fallbackResolver) {
        this.fallbackResolver = fallbackResolver;
        this.predicate = predicate;
    }

    @Override
    public Set<String> getAllVersions(String groupId, String artifactId, String extension, String classifier) {
        Set<String> allVersions = fallbackResolver.getAllVersions(groupId, artifactId, extension, classifier);
        List<String> filteredVersions = allVersions.stream()
                .filter(version ->
                        predicate.test(ArtifactVersion.of(groupId, artifactId, extension, classifier, version)))
                .toList();
        return new HashSet<>(filteredVersions);
    }

    @Override
    public File resolveArtifact(String groupId, String artifactId, String extension, String classifier, String version) throws ArtifactTransferException {
        return fallbackResolver.resolveArtifact(groupId, artifactId, extension, classifier, version);
    }

    @Override
    public List<File> resolveArtifacts(List<ArtifactCoordinate> coordinates) throws ArtifactTransferException {
        return fallbackResolver.resolveArtifacts(coordinates);
    }

    @Override
    public List<URL> resolveChannelMetadata(List<? extends ChannelMetadataCoordinate> manifestCoords) throws ArtifactTransferException {
        return fallbackResolver.resolveChannelMetadata(manifestCoords);
    }

    @Override
    public String getMetadataReleaseVersion(String groupId, String artifactId) {
        return fallbackResolver.getMetadataReleaseVersion(groupId, artifactId);
    }

    @Override
    public String getMetadataLatestVersion(String groupId, String artifactId) {
        return fallbackResolver.getMetadataLatestVersion(groupId, artifactId);
    }
}
