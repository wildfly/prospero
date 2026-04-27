package org.wildfly.prospero.galleon.artifactfiltering;

import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.Stream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Filters artifact versions based on provided manifest (which must contain version patterns).
 */
public class ManifestBasedArtifactFilter implements Predicate<ArtifactVersion> {

    private final ChannelManifest channelManifest;
    private final boolean allowMissingStreams;

    public ManifestBasedArtifactFilter(Path manifestFile, boolean allowMissingStreams) {
        try {
            channelManifest = ChannelManifestMapper.fromString(Files.readString(manifestFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.allowMissingStreams = allowMissingStreams;
    }

    public boolean test(ArtifactVersion artifactVersion) {
        Optional<Stream> streamOptional = channelManifest.findStreamFor(artifactVersion.groupId, artifactVersion.artifactId);
        if (streamOptional.isEmpty()) {
            if (allowMissingStreams) {
                return true;
            } else {
                throw new RuntimeException(String.format("Missing filter stream for %s:%s",
                        artifactVersion.groupId, artifactVersion.artifactId));
            }
        }
        Stream stream = streamOptional.get();
        if (stream.getVersionPattern() != null) {
            return stream.getVersionPattern().asPredicate().test(artifactVersion.version);
        } else if (stream.getVersion() != null) {
            return stream.getVersion().equals(artifactVersion.version);
        } else {
            throw new RuntimeException(String.format(
                    "Either version pattern or version must be provided by the manifest stream. " +
                    "Neither was provided for %s:%s.",
                    artifactVersion.groupId, artifactVersion.artifactId));
        }
    }

}
