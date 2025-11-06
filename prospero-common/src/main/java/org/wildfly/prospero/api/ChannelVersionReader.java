package org.wildfly.prospero.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.wildfly.channel.Channel;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.metadata.ManifestVersionRecord;

/**
 * Combines the information about currently installed versions of manifests per channel into {@link ChannelVersionReader}.
 *
 * The information is spread between installation-channels.yaml and manifest_version.yaml.
 * The name of the channel with the location of the manifest is available in the installation-channels.yaml
 * The versions of each manifest are in manifest_version.yaml
 * The common element is the location of the manifest
 */

class ChannelVersionReader {

    private final List<Channel> channels;
    private final ManifestVersionRecord currentVersions;

    ChannelVersionReader(List<Channel> channels, ManifestVersionRecord currentVersions) {
        this.channels = channels;
        this.currentVersions = currentVersions;
    }

    public List<ChannelVersion> getChannelVersions() {
        final HashMap<String, ManifestVersionRecord.MavenManifest> recordedMaven = new HashMap<>();
        final HashMap<String, ManifestVersionRecord.UrlManifest> recordedUrl = new HashMap<>();
        final List<ChannelVersion> res = new ArrayList<>();
        for (ManifestVersionRecord.MavenManifest mavenManifest : currentVersions.getMavenManifests()) {
            final String key = mavenManifest.getGroupId() + ":" + mavenManifest.getArtifactId();
            recordedMaven.put(key, mavenManifest);
        }
        for (ManifestVersionRecord.UrlManifest urlManifest : currentVersions.getUrlManifests()) {
            final String key = urlManifest.getUrl();
            recordedUrl.put(key, urlManifest);
        }

        for (Channel channel : channels) {
            final String key;
            final ChannelVersion.Builder builder = new ChannelVersion.Builder();
            // TODO: this should be common with code in UpdateFinder
            if (channel.getManifestCoordinate() == null) {
                final String repos = channel.getRepositories().stream().map(Repository::getId).collect(Collectors.joining(","));
                key = channel.getNoStreamStrategy().toString().toLowerCase(Locale.ROOT) + "@[" + repos + "]";
                builder.setType(ChannelVersion.Type.OPEN);
            } else if (channel.getManifestCoordinate().getUrl() != null) {
                key = channel.getManifestCoordinate().getUrl().toExternalForm();
                builder.setType(ChannelVersion.Type.URL);
            } else {
                key = channel.getManifestCoordinate().getGroupId() + ":" + channel.getManifestCoordinate().getArtifactId();
                builder.setType(ChannelVersion.Type.MAVEN);
            }
            builder.setLocation(key)
                    .setChannelName(channel.getName());

            if (recordedMaven.containsKey(key)) {
                final ManifestVersionRecord.MavenManifest record = recordedMaven.get(key);
                res.add(builder
                        .setPhysicalVersion(record.getVersion())
                        .setLogicalVersion(record.getDescription())
                        .build());
            } else if (recordedUrl.containsKey(key)) {
                final ManifestVersionRecord.UrlManifest record = recordedUrl.get(key);
                res.add(builder
                        .setPhysicalVersion(record.getHash())
                        .setLogicalVersion(record.getDescription())
                        .build());
            } else {
                res.add(builder.build());
            }
        }
        return res;
    }
}
