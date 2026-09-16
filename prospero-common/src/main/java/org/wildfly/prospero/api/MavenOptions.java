/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.DefaultSettingsReader;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS;

public class MavenOptions {

    private static final Path DEFAULT_MAVEN_SETTINGS = Path.of(System.getProperty("user.home"), ".m2/settings.xml");

    private static final YAMLFactory YAML_FACTORY = new YAMLFactory()
            .configure(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR, true);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(YAML_FACTORY)
            .configure(FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final Optional<Path> localCache;
    private final Optional<Boolean> offline;
    private final Optional<Boolean> noLocalCache;
    @JsonIgnore
    private final Optional<Path> mavenSettingsPath;
    private final Path filterManifest;

    public static final MavenOptions DEFAULT_OPTIONS = builder().build();
    public static final MavenOptions OFFLINE_NO_CACHE = builder()
            .setOffline(true)
            .setNoLocalCache(true)
            .build();

    public static final MavenOptions OFFLINE = builder()
            .setOffline(true)
            .build();

    public static MavenOptions.Builder builder() {
        return new Builder();
    }

    @JsonCreator
    private MavenOptions(@JsonProperty("localCache") Path localCache,
                         @JsonProperty("offline") boolean offline,
                         @JsonProperty("noLocalCache") boolean noLocalCache) {
        this.localCache = Optional.ofNullable(localCache).map(Path::toAbsolutePath);
        this.noLocalCache = Optional.of(noLocalCache);
        this.offline = Optional.of(offline);
        this.mavenSettingsPath = Optional.of(DEFAULT_MAVEN_SETTINGS);
        this.filterManifest = null;
    }

    private MavenOptions(Optional<Path> localCache, Optional<Boolean> offline, Optional<Boolean> noLocalCache, Optional<Path> mavenSettings, Path filterManifest) {
        this.localCache = localCache;
        this.noLocalCache = noLocalCache;
        this.offline = offline;
        this.mavenSettingsPath = mavenSettings;
        this.filterManifest = filterManifest;
    }

    public Path getLocalCache() {
        return localCache.orElse(null);
    }

    public boolean isOffline() {
        return offline.orElse(false);
    }

    public boolean isNoLocalCache() {
        return noLocalCache.orElseGet(localCache::isEmpty);
    }


    public boolean overridesLocalCache() {
        return localCache.isPresent();
    }

    public Path getMavenSettingsPath() {
        return mavenSettingsPath.orElse(null);
    }

    /**
     * Parses the maven settings file
     * @return Maven Settings instance
     */
    public Settings getMavenSettings() {
        if (mavenSettingsPath.isEmpty() || !mavenSettingsPath.get().toFile().exists()) {
            return null;
        }
        try {
            DefaultSettingsReader settingsReader = new DefaultSettingsReader();
            return settingsReader.read(mavenSettingsPath.get().toFile(), null);
        } catch (IOException e) {
            throw new RuntimeException("Can't read settings.xml file: " + mavenSettingsPath.get(), e);
        }
    }

    public Path getFilterManifest() {
        return filterManifest;
    }

    @Override
    public String toString() {
        return "MavenOptions{" +
                "localCache=" + localCache +
                ", offline=" + offline +
                ", noLocalCache=" + noLocalCache +
                ", mavenSettings=" + mavenSettingsPath +
                '}';
    }

    public MavenOptions merge(MavenOptions override) {
        final Builder builder = builder();
        if (override.offline.isPresent()) {
            builder.setOffline(override.isOffline());
        } else if (this.offline.isPresent()) {
            builder.setOffline(this.isOffline());
        }

        if (override.noLocalCache.isPresent()) {
            builder.setNoLocalCache(override.isNoLocalCache());
        } else if (this.noLocalCache.isPresent()) {
            builder.setNoLocalCache(this.isNoLocalCache());
        }

        if (override.localCache.isPresent()) {
            builder.setLocalCachePath(override.getLocalCache());
        } else if (this.localCache.isPresent()) {
            builder.setLocalCachePath(this.getLocalCache());
        }

        if (override.mavenSettingsPath.isPresent()) {
            builder.setMavenSettingsPath(override.getMavenSettingsPath());
        } else if (this.mavenSettingsPath.isPresent()) {
            builder.setMavenSettingsPath(this.getMavenSettingsPath());
        }

        if (override.filterManifest != null) {
            builder.setFilterManifest(override.getFilterManifest());
        } else if (this.filterManifest != null) {
            builder.setFilterManifest(this.getFilterManifest());
        }

        return builder.build();
    }

    public void write(Path target) throws IOException {
        final StringWriter w = new StringWriter();
        OBJECT_MAPPER.writeValue(w, this);
        Files.writeString(target, w.toString());
    }

    public static MavenOptions read(Path target) throws IOException {
        return OBJECT_MAPPER.readValue(target.toFile(), MavenOptions.class);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MavenOptions that = (MavenOptions) o;
        return Objects.equals(localCache, that.localCache)
                && Objects.equals(offline, that.offline)
                && Objects.equals(noLocalCache, that.noLocalCache)
                && Objects.equals(mavenSettingsPath, that.mavenSettingsPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(localCache, offline, noLocalCache, mavenSettingsPath);
    }

    public static class Builder {

        private Optional<Boolean> offline = Optional.empty();
        private Optional<Boolean> noLocalCache = Optional.empty();
        private Optional<Path> localCachePath = Optional.empty();
        private Optional<Path> mavenSettingsPath = Optional.of(DEFAULT_MAVEN_SETTINGS);
        private Path filterManifest = null;

        private Builder() {
        }

        public MavenOptions build() {
            return new MavenOptions(localCachePath, offline, noLocalCache, mavenSettingsPath, filterManifest);
        }

        public Builder setOffline(boolean offline) {
            this.offline = Optional.of(offline);
            return this;
        }

        public Builder setNoLocalCache(boolean noLocalCache) {
            this.noLocalCache = Optional.of(noLocalCache);
            return this;
        }

        public Builder setLocalCachePath(Path localCachePath) {
            this.localCachePath = Optional.of(localCachePath);
            return this;
        }

        /**
         * Allows to specify location of the Maven settings file.
         * <p>
         * This parameter is currently not stored in installation metadata, it is only used for given command invocation.
         *
         * @param mavenSettings maven settings.xml file path, defaults to ~/.m2/settings.xml
         */
        public Builder setMavenSettingsPath(Path mavenSettings) {
            this.mavenSettingsPath = Optional.of(mavenSettings);
            return this;
        }

        /**
         * Set filter manifest used to
         * @param filterManifest
         * @return
         */
        public Builder setFilterManifest(Path filterManifest) {
            this.filterManifest = filterManifest;
            return this;
        }
    }
}
