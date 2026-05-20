/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Definition of the provisioning changes a feature-pack requires to be installed on top of the base server.
 */
public class FeaturePackTemplate {

    private final String artifactId;
    private final String version;
    private final String groupId;
    private final List<String> additionalPackages;
    private final String transitiveDependency;
    private final boolean supportsCustomization;
    private final boolean requiresLayers;
    private final String replacesDependency;

    @JsonCreator
    public FeaturePackTemplate(@JsonProperty("groupId") String groupId,
                               @JsonProperty("artifactId") String artifactId,
                               @JsonProperty("version") String version,
                               @JsonProperty("additional-packages") List<String> additionalPackages,
                               @JsonProperty("transitive-dependencies") String transitiveDependency,
                               @JsonProperty("replaces-dependency") String replacesDependency,
                               @JsonProperty(value = "supports-customization") Boolean supportsCustomization,
                               @JsonProperty(value = "requires-layers") boolean requiresLayers
    ) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.additionalPackages = additionalPackages == null ? Collections.emptyList() : additionalPackages;
        this.transitiveDependency = transitiveDependency;
        this.replacesDependency = replacesDependency;
        this.supportsCustomization = supportsCustomization == null || supportsCustomization;
        this.requiresLayers = requiresLayers;
    }

    private FeaturePackTemplate(Builder builder) {
        this(
                builder.groupId,
                builder.artifactId,
                builder.version,
                builder.additionalPackages,
                builder.transitiveDependency,
                builder.replacesDependency,
                builder.supportsCustomization,
                builder.requiresLayers
        );
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public boolean isRequiresLayers() {
        return requiresLayers;
    }

    public boolean isSupportsCustomization() {
        return supportsCustomization;
    }

    public List<String> getAdditionalPackages() {
        return additionalPackages;
    }

    public String getTransitiveDependency() {
        return transitiveDependency;
    }

    public String getReplacesDependency() {
        return replacesDependency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FeaturePackTemplate template = (FeaturePackTemplate) o;

        return supportsCustomization == template.supportsCustomization && requiresLayers == template.requiresLayers
                && Objects.equals(artifactId, template.artifactId) && Objects.equals(version, template.version)
                && Objects.equals(groupId, template.groupId) && Objects.equals(additionalPackages, template.additionalPackages)
                && Objects.equals(transitiveDependency, template.transitiveDependency)
                && Objects.equals(replacesDependency, template.replacesDependency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactId, version, groupId, additionalPackages, transitiveDependency, supportsCustomization, requiresLayers, replacesDependency);
    }

    @Override
    public String toString() {
        return "FeaturePackTemplate{" +
                "artifactId='" + artifactId + '\'' +
                ", version='" + version + '\'' +
                ", groupId='" + groupId + '\'' +
                ", additionalPackages=" + additionalPackages +
                ", transitiveDependency='" + transitiveDependency + '\'' +
                ", supportsCustomization=" + supportsCustomization +
                ", requiresLayers=" + requiresLayers +
                ", replacesDependency='" + replacesDependency + '\'' +
                '}';
    }

    public static class Builder {
        private final String artifactId;
        private final String version;
        private final String groupId;
        private List<String> additionalPackages;
        private String transitiveDependency;
        private boolean supportsCustomization;
        private String replacesDependency;
        private boolean requiresLayers;

        public Builder(String groupId, String artifactId, String version) {
            this.artifactId = artifactId;
            this.version = version;
            this.groupId = groupId;
        }

        public FeaturePackTemplate build() {
            return new FeaturePackTemplate(this);
        }

        public Builder addAdditionalPackage(String name) {
            if (additionalPackages == null) {
                additionalPackages = new ArrayList<>();
            }
            additionalPackages.add(name);
            return this;
        }

        public Builder addTransitiveDependency(String name) {
            transitiveDependency = name;
            return this;
        }

        public Builder setSupportsCustomization(boolean supportsCustomization) {
            this.supportsCustomization = supportsCustomization;
            return this;
        }

        public Builder setReplacesDependency(String replacesDependency) {
            this.replacesDependency = replacesDependency;
            return this;
        }

        public Builder setRequiresLayers(boolean requiresLayers) {
            this.requiresLayers = requiresLayers;
            return this;
        }
    }
}
