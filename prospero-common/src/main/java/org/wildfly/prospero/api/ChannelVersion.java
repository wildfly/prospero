package org.wildfly.prospero.api;

import java.util.Objects;

import org.wildfly.channel.version.VersionMatcher;

public class ChannelVersion implements Comparable<ChannelVersion> {

    public enum Type {MAVEN, URL, OPEN}
    private final String channelName;
    private final Type type;
    private final String location;
    private final String physicalVersion;
    private final String logicalVersion;

    private ChannelVersion(Builder builder) {
        this.channelName = builder.channelName;
        this.type =builder.type;
        this.location = builder.location;
        this.physicalVersion = builder.physicalVersion;
        this.logicalVersion = builder.logicalVersion;
    }

    public String getChannelName() {
        return channelName;
    }

    public Type getType() {
        return type;
    }

    public String getLocation() {
        return location;
    }

    public String getPhysicalVersion() {
        return physicalVersion;
    }

    public String getLogicalVersion() {
        return logicalVersion;
    }

    @Override
    public String toString() {
        return "ChannelVersion{" +
                "channelName='" + channelName + '\'' +
                ", type=" + type +
                ", location='" + location + '\'' +
                ", physicalVersion='" + physicalVersion + '\'' +
                ", logicalVersion='" + logicalVersion + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelVersion that = (ChannelVersion) o;
        return Objects.equals(channelName, that.channelName) && type == that.type && Objects.equals(location, that.location) && Objects.equals(physicalVersion, that.physicalVersion) && Objects.equals(logicalVersion, that.logicalVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelName, type, location, physicalVersion, logicalVersion);
    }

    @Override
    public int compareTo(ChannelVersion o) {
        return VersionMatcher.COMPARATOR.compare(this.physicalVersion, o.physicalVersion);
    }

    public static class Builder {
        private String channelName;
        private Type type;
        private String location;
        private String physicalVersion;
        private String logicalVersion;

        public ChannelVersion build() {
            return new ChannelVersion(this);
        }

        public Builder setChannelName(String channelName) {
            this.channelName = channelName;
            return this;
        }

        public Builder setType(Type type) {
            this.type = type;
            return this;
        }

        public Builder setLocation(String location) {
            this.location = location;
            return this;
        }

        public Builder setPhysicalVersion(String physicalVersion) {
            this.physicalVersion = physicalVersion;
            return this;
        }

        public Builder setLogicalVersion(String logicalVersion) {
            this.logicalVersion = logicalVersion;
            return this;
        }
    }
}
