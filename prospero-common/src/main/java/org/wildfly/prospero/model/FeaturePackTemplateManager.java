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

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.actions.FeaturesAddAction;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Matches provisioning configurations that need to be applied if a certain feature-pack GAV is installed.
 */
public class FeaturePackTemplateManager {

    protected static final String FEATURE_PACK_TEMPLATES_YAML = "feature-pack-templates.yaml";
    private final List<FeaturePackTemplate> recipes;

    public FeaturePackTemplateManager() throws MetadataException {
        final URL resource = FeaturesAddAction.class.getClassLoader().getResource(FEATURE_PACK_TEMPLATES_YAML);
        try {
            final String yaml = IOUtils.toString(resource, StandardCharsets.UTF_8);
            this.recipes = FeaturePackTemplateList.read(yaml).getRecipes();
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToReadFile(Path.of(resource.getPath()), e);
        }
    }

    public FeaturePackTemplateManager(FeaturePackTemplateList featurePackRecipeBook) {
        recipes = featurePackRecipeBook.getRecipes();
    }

    /**
     * finds a matching provisioning configuration for a given GAV. The provided artifactCoordinate needs to specify
     * groupId, artifactId and version.
     *
     * @param groupId - groupId of the feature-pack Maven coordinates
     * @param artifactId - artifactId of the feature-pack Maven coordinates
     * @param version - Maven version of the feature-pack being installed
     * @return matched configuration or null if either it doesn't exist
     * @throws FeatureTemplateVersionMismatchException - if the template GA is matched, but none of the records match the version
     */
    public FeaturePackTemplate find(String groupId, String artifactId, String version)
            throws FeatureTemplateVersionMismatchException {

        final List<FeaturePackTemplate> matchingRecipes = recipes.stream()
                .filter(r -> r.getGroupId().equals(groupId) &&
                        r.getArtifactId().equals(artifactId))
                .collect(Collectors.toList());

        if (matchingRecipes.isEmpty()) {
            return null;
        }

        final List<String> ranges = new ArrayList<>();
        for (FeaturePackTemplate recipe : matchingRecipes) {
            if (versionMatches(recipe.getVersion(), version)) {
                return recipe;
            }
            ranges.add(recipe.getVersion());
        }

        throw new FeatureTemplateVersionMismatchException(
                String.format("Provisioning template for %s:%s is defined only for versions ranges %s", groupId, artifactId, String.join(",", ranges)));
    }

    private static boolean versionMatches(String recipeVersion, String version) {
        try {
            final VersionRange versionRange = VersionRange.createFromVersionSpec(recipeVersion);
            return versionRange.containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException e) {
            throw new RuntimeException("Invalid version pattern in the provisioning template file", e);
        }
    }

    public static class FeatureTemplateVersionMismatchException extends OperationException {

        public FeatureTemplateVersionMismatchException(String message) {
            super(message);
        }
    }
}
