/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.api.exceptions;

import org.junit.Test;
import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.channel.ChannelMetadataCoordinate;
import org.wildfly.channel.Repository;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class ResolutionExceptionMessageTest {

    @Test
    public void unresolvedChannelMetadataMessageContainsArtifactsAndRepositories() {
        final UnresolvedChannelMetadataException ex = new UnresolvedChannelMetadataException("Unable to resolve artifact",
                new RuntimeException("cause"),
                Set.of(new ChannelMetadataCoordinate("org.test", "manifest", "1.0.0", "manifest", "yaml")),
                Set.of(new Repository("repo-a", "https://a.example.org/maven")), false);

        assertThat(ex.getMessage())
                .isEqualTo("Unable to resolve artifact: org.test:manifest:manifest:yaml:1.0.0."
                        + " Attempted repositories: repo-a (https://a.example.org/maven)");
    }

    @Test
    public void artifactResolutionMessageContainsArtifactsAndRepositories() {
        final ArtifactResolutionException ex = new ArtifactResolutionException("Unable to resolve artifact",
                new RuntimeException("cause"),
                Set.of(new ArtifactCoordinate("org.test", "artifact", "jar", null, "1.0.0")),
                Set.of(new Repository("repo-a", "https://a.example.org/maven"),
                        new Repository("repo-b", "file:/tmp/repo-b")), false);

        assertThat(ex.getMessage())
                .startsWith("Unable to resolve artifact: org.test:artifact:jar:1.0.0.")
                .contains("repo-a (https://a.example.org/maven)")
                .contains("repo-b (file:/tmp/repo-b)");
    }

    @Test
    public void messageIsUnchangedWhenNoDetailsAvailable() {
        final ArtifactResolutionException ex = new ArtifactResolutionException("Unable to resolve artifact",
                new RuntimeException("cause"), Collections.emptySet(), Collections.emptySet(), false);

        assertThat(ex.getMessage()).isEqualTo("Unable to resolve artifact");
    }

    @Test
    public void messageContainsOnlyRepositoriesWhenNoArtifactsAvailable() {
        final ArtifactResolutionException ex = new ArtifactResolutionException("Unable to resolve artifact",
                new RuntimeException("cause"), Collections.emptySet(),
                Set.of(new Repository("repo-a", "file:/home/user/my%20repo/")), false);

        assertThat(ex.getMessage())
                .isEqualTo("Unable to resolve artifact. Attempted repositories: repo-a (file:/home/user/my%20repo/)");
    }

    @Test
    public void nullMessageIsPreserved() {
        final ArtifactResolutionException ex = new ArtifactResolutionException(null,
                new RuntimeException("cause"), Collections.emptySet(), Collections.emptySet(), false);

        assertThat(ex.getMessage()).isNull();
    }

    @Test
    public void detailsDoNotChangeTypedAccessors() {
        final Set<Repository> repositories = Set.of(new Repository("repo-a", "https://a.example.org/maven"));
        final Set<ArtifactCoordinate> artifacts = Set.of(new ArtifactCoordinate("org.test", "artifact", "jar", null, "1.0.0"));
        final ArtifactResolutionException ex = new ArtifactResolutionException("Unable to resolve artifact",
                new RuntimeException("cause"), artifacts, repositories, true);

        assertThat(ex.getMissingArtifacts()).isEqualTo(artifacts);
        assertThat(ex.getRepositories()).isEqualTo(repositories);
        assertThat(ex.isOffline()).isTrue();
    }
}
