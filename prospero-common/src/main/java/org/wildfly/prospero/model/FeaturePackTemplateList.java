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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * contains recipes for feature-pack addition
 */
class FeaturePackTemplateList {

    private static final String DEFAULT_VERSION = "1.0.0";
    private static final Set<String> KNOWN_SCHEMA_VERSIONS = Set.of(DEFAULT_VERSION);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    private String schemaVersion;
    private List<FeaturePackTemplate> recipes;

    public FeaturePackTemplateList(List<FeaturePackTemplate> recipes) {
        this(DEFAULT_VERSION, recipes);
    }

    @JsonCreator
    public FeaturePackTemplateList(@JsonProperty(value = "schemaVersion", required = true) String schemaVersion,
                                   @JsonProperty("feature-packs") List<FeaturePackTemplate> recipes) {
        if (recipes == null) {
            recipes = Collections.emptyList();
        }
        validateUnique(recipes);
        this.schemaVersion = schemaVersion;
        this.recipes = recipes;
    }

    private void validateUnique(List<FeaturePackTemplate> recipes) {
        final List<String> recipeGavs = recipes.stream()
                .map(r -> String.format("%s:%s:%s", r.getGroupId(), r.getArtifactId(), r.getVersion()))
                .sorted()
                .collect(Collectors.toList());

        String previousGav = "";
        for (String recipeGav : recipeGavs) {
            if (recipeGav.equals(previousGav)) {
                throw new IllegalArgumentException("Duplicated mappings for GAV: " + recipeGav);
            }
            previousGav = recipeGav;
        }
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<FeaturePackTemplate> getRecipes() {
        return recipes;
    }

    public String toYaml() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(this);
    }

    public static FeaturePackTemplateList read(String yaml) throws JsonProcessingException {
        final JsonNode schemaVersion = OBJECT_MAPPER.readTree(yaml).get("schemaVersion");
        if (schemaVersion == null || !KNOWN_SCHEMA_VERSIONS.contains(schemaVersion.asText())) {
            throw new IllegalArgumentException("Unknown schema version for the recipe book");
        }
        return OBJECT_MAPPER.readValue(yaml, FeaturePackTemplateList.class);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FeaturePackTemplateList that = (FeaturePackTemplateList) o;
        return Objects.equals(schemaVersion, that.schemaVersion) && Objects.equals(recipes, that.recipes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, recipes);
    }

    @Override
    public String toString() {
        return "FeaturePackTemplateList{" +
                "schemaVersion='" + schemaVersion + '\'' +
                ", recipes=" + recipes +
                '}';
    }
}
