package org.wildfly.prospero.galleon.artifactfiltering;

public class ArtifactVersion {

    final String groupId;
    final String artifactId;
    final String extension;
    final String classifier;
    final String version;

    public static ArtifactVersion of(String groupId, String artifactId, String extension, String classifier, String version) {
        return new ArtifactVersion(groupId, artifactId, extension, classifier, version);
    }

    private ArtifactVersion(String groupId, String artifactId, String extension, String classifier, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.extension = extension;
        this.classifier = classifier;
        this.version = version;
    }
}
