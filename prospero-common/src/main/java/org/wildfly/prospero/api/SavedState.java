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

package org.wildfly.prospero.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public class SavedState {

    private Set<Version> manifestVersions = Collections.emptySet();

    public enum Type { UPDATE, INSTALL, ROLLBACK, CONFIG_CHANGE,
        /**
         * A provisioning state change. For example adding a new feature pack
          */
        FEATURE_PACK,
        /**
         * Change that should not be visible to users.
         * It might be a change required to support new capabilities.
         */
        INTERNAL_UPDATE,
        UNKNOWN;

        public static Type fromText(String text) {
            for (Type value : values()) {
                if (value.name().equals(text)) {
                    return value;
                }
            }
            return UNKNOWN;
        }
    }

    private String hash;
    private Instant timestamp;
    private Type type;
    private String msg = "";

    @Deprecated
    public SavedState(String hash, Instant timestamp, Type type, String msg) {
        this.hash = hash;
        this.timestamp = timestamp;
        this.type = type;
        this.msg = msg;
    }

    public SavedState(String hash, Instant timestamp, Type type, String msg, List<Version> manifestVersions) {
        this.hash = hash;
        this.timestamp = timestamp;
        this.type = type;
        this.msg = msg;
        // sort the manifests alphabetically so that the order of manifests in display will be consistent
        this.manifestVersions = new TreeSet<>(manifestVersions);
    }

    public SavedState(String hash) {
        this.hash = hash;
        this.timestamp = null;
        this.type = null;
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return this.hash;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getMsg() {
        return msg;
    }

    public Collection<Version> getManifestVersions() {
        return manifestVersions;
    }

    public String shortDescription() {
        if (manifestVersions.isEmpty()) {
            return String.format("[%s] %s - %s %s", hash, timestamp.toString(), type.toString().toLowerCase(Locale.ROOT), msg);
        } else {
            return String.format("[%s] %s - %s", hash, timestamp.toString(), type.toString().toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SavedState that = (SavedState) o;
        return Objects.equals(manifestVersions, that.manifestVersions) && Objects.equals(hash, that.hash) && Objects.equals(timestamp, that.timestamp) && type == that.type && Objects.equals(msg, that.msg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(manifestVersions, hash, timestamp, type, msg);
    }

    @Override
    public String toString() {
        return "SavedState{" +
                "manifestVersions=" + manifestVersions +
                ", hash='" + hash + '\'' +
                ", timestamp=" + timestamp +
                ", type=" + type +
                ", msg='" + msg + '\'' +
                '}';
    }

    public static class Version implements Comparable<Version> {
        public final String physicalVersion;
        public final String logicalVersion;
        private final String identifier;

        /**
         * Constructs version description of a manifest.
         *
         * @param identifier - see {@link Version#getIdentifier()}
         * @param physicalVersion - see {@link Version#getPhysicalVersion()}
         * @param logicalVersion - see {@link Version#getLogicalVersion()}
         */
        public Version(String identifier, String physicalVersion, String logicalVersion) {
            Objects.requireNonNull(identifier);
            Objects.requireNonNull(physicalVersion);

            this.identifier = identifier;
            this.physicalVersion = physicalVersion;
            this.logicalVersion = logicalVersion;
        }

        /**
         * The version of Maven coordinate if the manifest is a Maven artifact or a hash of a file in case if URL manifest.
         * @return
         */
        public String getPhysicalVersion() {
            return physicalVersion;
        }

        /**
         * The version of manifest declared by the manifest header. For example since schema version 1.1.0, manifests
         * can define {@code logicalVersion} field. Note this is an optional value.
         * @return
         */
        public String getLogicalVersion() {
            return logicalVersion;
        }

        /**
         * Identifier of the manifest. In case of Maven manifest it is the GA part of the Maven coordinates. In case
         * of URL manifest, it is the URL of the manifest.
         * @return
         */
        public String getIdentifier() {
            return identifier;
        }

        public String getDisplayVersion() {
            if (logicalVersion != null) {
                return identifier + ":" + physicalVersion + " (" + logicalVersion + ")";
            } else {
                return identifier + ":" + physicalVersion;
            }
        }
        @Override
        public String toString() {
            return "Version{" +
                    "physicalVersion='" + physicalVersion + '\'' +
                    ", logicalVersion='" + logicalVersion + '\'' +
                    ", identifier='" + identifier + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Version version = (Version) o;
            return Objects.equals(physicalVersion, version.physicalVersion) && Objects.equals(logicalVersion, version.logicalVersion) && Objects.equals(identifier, version.identifier);
        }

        @Override
        public int hashCode() {
            return Objects.hash(physicalVersion, logicalVersion, identifier);
        }

        @Override
        public int compareTo(Version o) {
            int res = this.identifier.compareTo(o.getIdentifier());
            if (res == 0) {
                res = this.physicalVersion.compareTo(o.getPhysicalVersion());
            }
            if (res == 0 && this.logicalVersion != null && o.getLogicalVersion() != null) {
                res = this.logicalVersion.compareTo(o.getLogicalVersion());
            }
            return res;
        }
    }
}
