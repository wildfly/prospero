package org.wildfly.prospero.updates;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.wildfly.prospero.api.ChannelVersion;

/**
 * Collection of possible updates to the channels.
 * Only Maven-based channels can have a versioned update, thus all other channels are marked as unsupported.
 */
public class ChannelsUpdateResult {
    private final Map<String, ChannelResult> channelResultMap = new HashMap<>();

    public ChannelsUpdateResult(ChannelResult... channelResults) {
        this(Arrays.asList(channelResults));
    }

    public ChannelsUpdateResult(List<ChannelResult> channelResults) {
        for (ChannelResult channelResult : channelResults) {
            if (channelResultMap.containsKey(channelResult.getChannelName())) {
                throw new IllegalArgumentException("The results for channel " + channelResult.getChannelName() + " are listed twice.");
            }

            this.channelResultMap.put(channelResult.getChannelName(), channelResult);
        }
    }

    public Set<String> getUpdatedChannels() {
        return channelResultMap.keySet();
    }

    public ChannelResult getUpdatedVersion(String channel) {
        return channelResultMap.get(channel);
    }

    public Set<String> getUnsupportedChannels() {
        return channelResultMap.values().stream()
                .filter(r->r.getStatus() == Status.Unsupported)
                .map(ChannelResult::getChannelName)
                .collect(Collectors.toSet());
    }

    public boolean hasUpdates() {
        return channelResultMap.values().stream().anyMatch(r->r.getStatus() == Status.UpdatesFound);
    }

    public enum Status {
        Unsupported, NoUpdates, UpdatesFound
    }

    /**
     * Represents the possible versions a channel can be updated to.
     *
     * Since only Maven-based channels can have update versions, all other channels should have status set to Unsupported.
     */
    public static class ChannelResult {
        private final Status status;
        private final String channelName;
        private final ChannelVersion currentVersion;
        private final Set<ChannelVersion> availableVersions;

        /**
         * Create channel updates for a versioned channel record.
         *
         * @param channelName
         * @param currentVersion
         * @param availableVersions
         */
        public ChannelResult(String channelName, ChannelVersion currentVersion, Collection<ChannelVersion> availableVersions) {
            Objects.requireNonNull(channelName);
            Objects.requireNonNull(availableVersions);

            this.channelName = channelName;
            this.currentVersion = currentVersion;
            this.availableVersions = new TreeSet<>(availableVersions);
            this.status = availableVersions.isEmpty() ? Status.NoUpdates : Status.UpdatesFound;
        }

        /**
         * Create unsupported channel versions record.
         *
         * @param channelName
         * @param currentVersion
         */
        public ChannelResult(String channelName, ChannelVersion currentVersion) {
            Objects.requireNonNull(channelName);

            this.channelName = channelName;
            this.status = Status.Unsupported;
            this.currentVersion = currentVersion;
            this.availableVersions = Collections.emptySet();
        }

        public Status getStatus() {
            return status;
        }

        public ChannelVersion getCurrentVersion() {
            return currentVersion;
        }

        public Set<ChannelVersion> getAvailableVersions() {
            return availableVersions;
        }

        public String getChannelName() {
            return channelName;
        }
    }
}
