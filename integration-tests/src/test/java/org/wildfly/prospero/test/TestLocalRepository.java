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

package org.wildfly.prospero.test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.galleon.ProvisioningException;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

/**
 * Utility class to generate a local Maven repository and deploy artifacts to it
 */
public class TestLocalRepository {
    public static final String GALLEON_PLUGINS_VERSION = BuildProperties.getProperty("version.org.wildfly.galleon-plugins");

    private final Path root;
    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final List<RemoteRepository> upstreamRepositories;

    /**
     *
     * @param root - path of the repository
     * @param upstreamRepos - URLs of Maven repositories used to resolve artifacts to be deployed in this repository
     * @throws ProvisioningException
     */
    public TestLocalRepository(Path root, List<URL> upstreamRepos) throws ProvisioningException {
        this.root = root;
        final AtomicInteger i = new AtomicInteger(0);
        this.upstreamRepositories = upstreamRepos.stream()
                .map(r -> new RemoteRepository.Builder("repo-" + i.getAndIncrement(), "default", r.toExternalForm()).build())
                .collect(Collectors.toList());

        final MavenSessionManager msm = new MavenSessionManager();
        system = msm.newRepositorySystem();
        session = msm.newRepositorySystemSession(system);
    }

    /**
     * Deploys channel manifest in the repository
     *
     * @param manifestGav - The Maven GAV that the manifest will be deployed at
     * @param manifest - the manifest to be deployed
     * @throws IOException
     * @throws DeploymentException
     */
    public void deploy(Artifact manifestGav, ChannelManifest manifest) throws IOException, DeploymentException {
        final Path tempFile = Files.createTempFile("test-manifest", "yaml");
        try {
            Files.writeString(tempFile, ChannelManifestMapper.toYaml(manifest));

            final Artifact artifactWithFile = manifestGav.setFile(tempFile.toFile());
            deploy(artifactWithFile);
        } finally {
            Files.delete(tempFile);
        }
    }

    /**
     * Deploys an artifact
     *
     * @param artifact
     * @throws DeploymentException
     */
    public void deploy(Artifact artifact) throws DeploymentException {
        final DeployRequest req = new DeployRequest();
        req.setRepository(new RemoteRepository.Builder("local-repo", "default", root.toUri().toString()).build());
        req.addArtifact(artifact);

        system.deploy(session, req);
    }

    /**
     * Resolves an artifact in upstream repositories, and if successful, deploys it locally.
     *
     * @param artifact
     * @return the resolved artifact
     * @throws ArtifactResolutionException
     * @throws DeploymentException
     */
    public Artifact resolveAndDeploy(Artifact artifact) throws ArtifactResolutionException, DeploymentException {
        final Artifact resolved = resolveUpstream(artifact);
        deploy(resolved);
        return resolved;
    }

    /**
     * Mocks an update to the artifact with provided GAV.
     *
     * Resolves an upstream artifact with {@code groupId:artifactId:version} and deploys it as an update.
     * The version of the new artifact is {@code version} + {@code newVersionSuffix}
     *
     * @param groupId
     * @param artifactId
     * @param version
     * @param newVersionSuffix
     * @throws DeploymentException
     * @throws ArtifactResolutionException
     */
    public void deployMockUpdate(String groupId, String artifactId, String version, String newVersionSuffix) throws DeploymentException, ArtifactResolutionException {
        Artifact artifact = resolveUpstream(new DefaultArtifact(groupId, artifactId, null, "jar", version));
        deploy(artifact.setVersion(version + newVersionSuffix));
    }

    /**
     * Deploys default Galleon jars needed by the provisioning
     *
     * @throws ArtifactResolutionException
     * @throws DeploymentException
     */
    public void deployGalleonPlugins() throws ArtifactResolutionException, DeploymentException {
        resolveAndDeploy(new DefaultArtifact("org.wildfly.galleon-plugins", "wildfly-galleon-plugins", "jar", GALLEON_PLUGINS_VERSION));
        resolveAndDeploy(new DefaultArtifact("org.wildfly.galleon-plugins", "wildfly-config-gen", "jar", GALLEON_PLUGINS_VERSION));
    }

    /**
     * Local URI of this repository
     * @return
     */
    public URI getUri() {
        return root.toUri();
    }

    public Path getRootPath() {
        return root;
    }

    private Artifact resolveUpstream(Artifact artifact) throws ArtifactResolutionException {
        final ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setRepositories(upstreamRepositories);
        artifactRequest.setArtifact(artifact);
        final ArtifactResult artifactResult = system.resolveArtifact(session, artifactRequest);
        return artifactResult.getArtifact();
    }
}
