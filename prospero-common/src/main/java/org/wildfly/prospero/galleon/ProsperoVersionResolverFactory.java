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

package org.wildfly.prospero.galleon;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.channel.spi.MavenVersionsResolver;
import org.wildfly.prospero.galleon.artifactfiltering.ArtifactVersion;
import org.wildfly.prospero.galleon.artifactfiltering.FilteringVersionResolver;
import org.wildfly.prospero.galleon.artifactfiltering.ManifestBasedArtifactFilter;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class ProsperoVersionResolverFactory implements MavenVersionsResolver.Factory {

    private final VersionResolverFactory factory;
    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final ArtifactCache artifactCache;
    private final Path installDir;
    private final Path filterManifest;

    public ProsperoVersionResolverFactory(VersionResolverFactory factory, Path installDir, RepositorySystem system, DefaultRepositorySystemSession session, Path filterManifest) throws IOException {
        this.factory = factory;
        this.system = system;
        this.session = session;
        this.artifactCache = ArtifactCache.getInstance(installDir);
        this.installDir = installDir;
        this.filterManifest = filterManifest;
    }

    @Override
    public MavenVersionsResolver create(Collection<Repository> repositories) {
        MavenVersionsResolver wildflyChannelVersionsResolver = factory.create(repositories);

        Predicate<ArtifactVersion> artifactFiler;
        if (filterManifest != null) {
            artifactFiler = new ManifestBasedArtifactFilter(filterManifest, true);
        } else {
            artifactFiler = artifactVersion -> true;
        }
        FilteringVersionResolver filteringVersionResolver = new FilteringVersionResolver(artifactFiler, wildflyChannelVersionsResolver);

        return new CachedVersionResolver(filteringVersionResolver, artifactCache, system, session,
                (a)->getCurrentManifestVersion(a, installDir.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.CURRENT_VERSION_FILE)));
    }

    private static String getCurrentManifestVersion(ArtifactCoordinate a, Path manifestVersionRecord) {
        String version = null;
        try {
            final Optional<ManifestVersionRecord> read = ManifestVersionRecord.read(manifestVersionRecord);
            if (read.isPresent()) {
                final List<ManifestVersionRecord.MavenManifest> manifests = read.get().getMavenManifests();
                for (ManifestVersionRecord.MavenManifest manifest : manifests) {
                    if (manifest.getGroupId().equals(a.getGroupId()) && manifest.getArtifactId().equals(a.getArtifactId())) {
                        version = manifest.getVersion();
                        break;
                    }
                }
            }
        } catch (IOException ex) {
            // TODO: log and do not use cache
            throw new RuntimeException(ex);
        }
        return version;
    }

}
