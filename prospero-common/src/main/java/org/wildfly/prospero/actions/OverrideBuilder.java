package org.wildfly.prospero.actions;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.ProsperoLogger;


/**
 * Creates a list of channels with appropriate overrides. Currently, supports overrides manifest versions and
 * repository definitions. If no overrides are defined, returns an empty list.
 */
public class OverrideBuilder {

    private static final ProsperoLogger logger = ProsperoLogger.ROOT_LOGGER;

    private final List<Channel> channels;
    private List<Repository> shadowRepositories = null;
    private List<String> versions = null;

    OverrideBuilder(List<Channel> channels) {
        this.channels = channels;
    }

    public static OverrideBuilder from(List<Channel> channels) {
        return new OverrideBuilder(channels);
    }

    public List<Channel> build() throws IllegalArgumentException {
        final Map<String, VersionOverride> channelsMap = new HashMap<>();

        if ((shadowRepositories == null || shadowRepositories.isEmpty())
                && (versions == null || versions.isEmpty())) {
            logger.debugf("No version overrides or shadow repositories provided, returning empty list");
            // we don't need to alter the channels, can return an empty collection
            return Collections.emptyList();
        }

        if (versions != null && !versions.isEmpty()) {
            logger.debugf("Processing %d version override(s): %s", versions.size(), versions);

            for (String version : versions) {
                VersionOverride override = parseVersionOverrideString(version);
                if (channelsMap.containsKey(override.channelName())) {
                    logger.debugf("Duplicate override detected for channel: '%s'", override.channelName());
                    throw ProsperoLogger.ROOT_LOGGER.duplicatedVersionOverride(override.channelName());
                }
                channelsMap.put(override.channelName(), override);
                logger.debugf("Successfully added version override: %s -> %s", override.channelName(), override.version());
            }

            final Set<String> existingChannelNames = channels.stream().map(Channel::getName).collect(Collectors.toSet());
            logger.debugf("Available channels: %s", existingChannelNames);

            for (String overrideKey : channelsMap.keySet()) {
                if (!existingChannelNames.contains(overrideKey)) {
                    logger.debugf("Channel '%s' specified in override does not exist in available channels", overrideKey);
                    throw ProsperoLogger.ROOT_LOGGER.channelNotFoundException(overrideKey);
                }
            }

            if (existingChannelNames.size() != channelsMap.size() || !existingChannelNames.containsAll(channelsMap.keySet())) {
                logger.debugf("Version overrides incomplete - provided: %s, required: %s",
                    channelsMap.keySet(), existingChannelNames);
                throw ProsperoLogger.ROOT_LOGGER.versionOverrideHasToApplyToAllChannels();
            }

            logger.infof("Applied version overrides to %d channel(s)", channelsMap.size());
        }


        final List<Channel> list = new ArrayList<>();
        for (Channel c : channels) {
            // map the channel to version override
            final VersionOverride version = channelsMap.get(c.getName());
            Channel channel = overrideChannel(c, version);
            list.add(channel);
            if (version != null) {
                logger.infof("Channel '%s' overridden to version '%s'", c.getName(), version.version());
            }
        }
        return list;
    }

    public OverrideBuilder withRepositories(List<Repository> shadowRepositories) {
        this.shadowRepositories = shadowRepositories;
        return this;
    }

    public OverrideBuilder withManifestVersions(List<String> versions) {
        this.versions = versions;
        return this;
    }

    private VersionOverride parseVersionOverrideString(String version) throws IllegalArgumentException {
        String[] parts = (" " + version + " ").split("::");
        if (parts.length != 2) {
            logger.debugf("Invalid format - found %d parts after splitting by '::', expected 2", parts.length);
            if (parts.length == 1) {
                throw ProsperoLogger.ROOT_LOGGER.invalidVersionOverrideMissingDelimiter(version);
            } else {
                throw ProsperoLogger.ROOT_LOGGER.invalidVersionOverrideTooManyDelimiters(version);
            }
        }

        String channelName = parts[0].trim();
        String versionStr = parts[1].trim();

        logger.debugf("Parsed channel name: '%s', version: '%s'", channelName, versionStr);

        if (channelName.isEmpty()) {
            logger.debugf("Channel name is empty after trimming");
            throw ProsperoLogger.ROOT_LOGGER.invalidVersionOverrideEmptyChannel(version);
        }
        if (versionStr.isEmpty()) {
            logger.debugf("Version string is empty after trimming");
            throw ProsperoLogger.ROOT_LOGGER.invalidVersionOverrideEmptyVersion(version);
        }

        return new VersionOverride(channelName, versionStr);
    }

    private Channel overrideChannel(Channel c, VersionOverride version) {
        final Channel.Builder builder = new Channel.Builder(c);
        if (version != null && c.getName().equals(version.channelName())) {
            final ChannelManifestCoordinate coord = c.getManifestCoordinate();
            if (coord.getUrl() != null) {
                try {
                    builder.setManifestUrl(URI.create(version.version()).toURL());
                } catch (MalformedURLException e) {
                    throw ProsperoLogger.ROOT_LOGGER.invalidUrl(version.version(), e);
                }
            } else {
                builder.setManifestCoordinate(coord.getGroupId(), coord.getArtifactId(), version.version());
            }
        }
        if (shadowRepositories != null && !shadowRepositories.isEmpty()) {
            builder.setRepositories(shadowRepositories);
        }

        return builder.build();
    }

    private static record VersionOverride(String channelName, String version) {}
}
