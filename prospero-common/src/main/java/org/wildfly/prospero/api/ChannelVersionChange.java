package org.wildfly.prospero.api;

import org.wildfly.channel.version.VersionMatcher;

public record ChannelVersionChange(String channelName, ChannelVersion oldVersion, ChannelVersion newVersion) {

    /**
     * Checks if the channel change represents a downgrade - i.e. attempt to install a lower version manifest.
     *
     * NOTE: the "downgrade" concept only makes sense for Maven backed channels. In all other channels this method always return "false"
     * @return
     */
    public boolean isDowngrade() {
        if (oldVersion != null && oldVersion.getPhysicalVersion() != null && oldVersion.getType() == ChannelVersion.Type.MAVEN &&
                newVersion != null && newVersion.getPhysicalVersion() != null && newVersion.getType() == ChannelVersion.Type.MAVEN) {
            return VersionMatcher.COMPARATOR.compare(oldVersion().getPhysicalVersion(), newVersion().getPhysicalVersion()) > 0;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return "ChannelVersionChange{" +
                "channelName='" + channelName + '\'' +
                ", oldVersion=" + oldVersion +
                ", newVersion=" + newVersion +
                '}';
    }
}
