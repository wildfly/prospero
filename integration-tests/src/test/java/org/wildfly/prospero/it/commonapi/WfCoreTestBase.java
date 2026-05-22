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

package org.wildfly.prospero.it.commonapi;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeployResult;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.wildfly.channel.Repository;
import org.wildfly.channel.maven.VersionResolverFactory;
import org.wildfly.prospero.actions.ProvisioningAction;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.test.MetadataTestUtils;
import org.wildfly.prospero.wfchannel.MavenSessionManager;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.BeforeClass;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class WfCoreTestBase {

    public static final String BASE_VERSION = "20.0.0.Beta5";
    public static final String CHANNEL_REQUIRING_VERSION = "20.0.0.Beta5-channel";
    public static final String UPGRADE_VERSION = "20.0.0.Beta5-test";
    public static final String UNDERTOW_VESION = "2.3.0.Final";
    public static final String XNIO_VERSION = "3.8.8.Final";
    public static final String BASE_JAR = "wildfly-cli-" + BASE_VERSION + ".jar";
    public static final String UPGRADE_JAR = "wildfly-cli-" + UPGRADE_VERSION + ".jar";
    public static final String CHANNEL_BASE_CORE_19 = "manifests/wfcore-base-require-channels.yaml";
    public static final String CHANNEL_FP_UPDATES = "manifests/wfcore-upgrade-fp.yaml";
    public static final String CHANNEL_COMPONENT_UPDATES = "manifests/wfcore-upgrade-component.yaml";
    public static final Repository REPOSITORY_MAVEN_CENTRAL = new Repository("maven-central", "https://repo1.maven.org/maven2/");
    public static final Repository REPOSITORY_NEXUS = new Repository("nexus", "https://repository.jboss.org/nexus/content/groups/public-jboss");
    public static final VersionResolverFactory CHANNELS_RESOLVER_FACTORY = new VersionResolverFactory(null, null);

    protected static Artifact resolvedUpgradeArtifact;
    protected static Artifact resolvedUpgradeClientArtifact;
    private static Path localCachePath;
    protected Path outputPath;
    protected Path manifestPath;
    protected ProvisioningAction installation;

    protected final List<Repository> repositories = defaultRemoteRepositories();
    protected MavenOptions mavenOptions;
    protected MavenSessionManager mavenSessionManager;

    protected static Path updateRepositoryPath;
    protected static RemoteRepository updateRepository;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    protected static Path testRepo;

    @BeforeClass
    public static void deployUpgrade() throws Exception {
        localCachePath = Files.createTempDirectory("local-cache").toAbsolutePath();
        testRepo = Files.createTempDirectory("test-fp-repo");
        updateRepositoryPath = Files.createTempDirectory("updates-repository");
        updateRepository = new RemoteRepository.Builder("updates", "default", updateRepositoryPath.toUri().toURL().toExternalForm()).build();
        final MavenSessionManager msm = new MavenSessionManager(MavenOptions.builder()
                .setLocalCachePath(localCachePath)
                .setOffline(false)
                .build());
        final RepositorySystem system = msm.newRepositorySystem();
        final DefaultRepositorySystemSession session = msm.newRepositorySystemSession(system);

        /* mock a wildfly-core feature-pack that requires a channel resolve
         * the mocked artifact lives in {@code testRepo}
         */
        deployFeaturePackRequiringChannels(system, session);

        resolvedUpgradeArtifact = deployIfMissing(system, session, "org.wildfly.core", "wildfly-cli", null, "jar");
        resolvedUpgradeClientArtifact = deployIfMissing(system, session, "org.wildfly.core", "wildfly-cli", "client", "jar");
        deployIfMissing(system, session, "org.wildfly.core", "wildfly-core-galleon-pack", null, "zip");
    }

    @AfterClass
    public static void removeCache() throws Exception {
        FileUtils.deleteQuietly(localCachePath.toFile());
        FileUtils.deleteQuietly(testRepo.toFile());
        FileUtils.deleteQuietly(updateRepositoryPath.toFile());
    }

    @Before
    public void setUp() throws Exception {
        mavenOptions = MavenOptions.builder()
                .setLocalCachePath(localCachePath)
                .setOffline(false)
                .build();
        mavenSessionManager = new MavenSessionManager(mavenOptions);
        outputPath = temp.newFolder().toPath().resolve("test-server");
        manifestPath = outputPath.resolve(MetadataTestUtils.MANIFEST_FILE_PATH);
        installation = new ProvisioningAction(outputPath, mavenOptions, new CliConsole());
    }

    protected static Artifact deployIfMissing(RepositorySystem system, DefaultRepositorySystemSession session, String groupId, String artifactId, String classifier, String extension) throws ArtifactResolutionException, DeploymentException {
        final ArtifactRequest artifactRequest = new ArtifactRequest();
        Artifact updateCli = new DefaultArtifact(groupId, artifactId, classifier, extension, UPGRADE_VERSION);
        artifactRequest.setArtifact(updateCli);
        artifactRequest.setRepositories(List.of(updateRepository));
        Artifact upgradeArtifact;
        try {
            final ArtifactResult result = system.resolveArtifact(session, artifactRequest);
            upgradeArtifact = result.getArtifact();
        } catch (ArtifactResolutionException e) {
            final DeployRequest deployRequest = new DeployRequest();
            updateCli = updateCli.setFile(resolveExistingCliArtifact(system, session, groupId, artifactId, classifier, extension));
            deployRequest.addArtifact(updateCli);
            deployRequest.setRepository(updateRepository);
            final DeployResult result = system.deploy(session, deployRequest);
            upgradeArtifact = result.getArtifacts().stream().findFirst().get();
        }
        return upgradeArtifact;
    }

    private static File resolveExistingCliArtifact(RepositorySystem system, DefaultRepositorySystemSession session,
                                                   String groupId, String artifactId, String classifier, String extension)
            throws ArtifactResolutionException {
        final DefaultArtifact existing = new DefaultArtifact(groupId, artifactId, classifier, extension, BASE_VERSION);
        return resolveArtifact(system, session, existing).getFile();
    }

    protected ProvisioningDefinition.Builder defaultWfCoreDefinition() {
        return ProvisioningDefinition.builder()
                .setFpl("org.wildfly.core:wildfly-core-galleon-pack::zip")
                .setOverrideRepositories(repositories);
    }

    private static Artifact resolveArtifact(RepositorySystem system, DefaultRepositorySystemSession session, DefaultArtifact existing) throws ArtifactResolutionException {
        final ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setRepositories(Arrays.asList(toRemoteRepository(REPOSITORY_MAVEN_CENTRAL)));
        artifactRequest.setArtifact(existing);
        final ArtifactResult artifactResult = system.resolveArtifact(session, artifactRequest);
        return artifactResult.getArtifact();
    }

    private static Object toRemoteRepository() {
        return toRemoteRepository(REPOSITORY_MAVEN_CENTRAL);
    }

    public static RemoteRepository toRemoteRepository(Repository repo) {
        return new RemoteRepository.Builder(repo.getId(), "default", repo.getUrl()).build();
    }

    public static List<Repository> defaultRemoteRepositories() {
        return Arrays.asList(
                REPOSITORY_MAVEN_CENTRAL,
                REPOSITORY_NEXUS,
                new Repository("test-fp-repo", testRepo.toFile().toURI().toString()),
                new Repository("update-repository", updateRepository.getUrl())
        );
    }

    protected URL mockTemporaryRepo(boolean updatesOnly) throws Exception {
        final RepositorySystem system = mavenSessionManager.newRepositorySystem();
        final DefaultRepositorySystemSession session = mavenSessionManager.newRepositorySystemSession(system);

        final File repo = temp.newFolder();
        if (!updatesOnly) {
            populateTestRepo(repo, system, session);
        }
        final URL repoUrl = repo.toURI().toURL();


        final DeployRequest deployRequest = new DeployRequest();
        deployRequest.addArtifact(resolvedUpgradeArtifact);
        deployRequest.addArtifact(resolvedUpgradeClientArtifact);
        deployRequest.setRepository(RepositoryUtils.toRemoteRepository("test", repoUrl.toString()));
        system.deploy(session, deployRequest);

        return repoUrl;
    }

    private Path populateTestRepo(File repo, RepositorySystem system, DefaultRepositorySystemSession session) throws Exception {
        final List<RemoteRepository> remoteRepositories = defaultRemoteRepositories().stream().map(r -> toRemoteRepository(r)).collect(Collectors.toList());

        // resolve wildfly-core galleon pack zip
        ArtifactRequest request = new ArtifactRequest(
                new DefaultArtifact("org.wildfly.core", "wildfly-core-galleon-pack", "zip", CHANNEL_REQUIRING_VERSION),
                remoteRepositories, null);
        final ArtifactResult galleonPackArtifact = system.resolveArtifact(session, request);

        // extract artifact list
        File galleonPackZip = galleonPackArtifact.getArtifact().getFile();
        Map<String, String> artifacts = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(galleonPackZip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if(entry.getName().endsWith("artifact-versions.properties")) {
                    artifacts = parseArtifactList(IOUtils.toString(zis, StandardCharsets.UTF_8).lines());
                }
            }
        }

        // additional artifacts not included in galleon-pack
        artifacts.put("galleon-pack", "org.wildfly.core:wildfly-core-galleon-pack:"+ CHANNEL_REQUIRING_VERSION + "::zip");
        artifacts.put("galleon-pack-base", "org.wildfly.core:wildfly-core-galleon-pack:"+ BASE_VERSION + "::zip");
        artifacts.put("galleon-plugins", "org.wildfly.galleon-plugins:wildfly-galleon-plugins:6.4.2.Final::jar");
        artifacts.put("wildfly-config-gen", "org.wildfly.galleon-plugins:wildfly-config-gen:6.4.2.Final::jar");

        // resolve all dependencies
        final List<ArtifactRequest> requests = artifacts.values().stream()
                .map(s-> parseGav(s))
                .map(a -> new ArtifactRequest(a,
                        remoteRepositories,
                        null))
                .collect(Collectors.toList());

        final List<ArtifactResult> artifactResults = system.resolveArtifacts(session, requests);

        // deploy resolved artifacts to a temporary folder
        final DeployRequest deployRequest = new DeployRequest();
        for (ArtifactResult artifactResult : artifactResults) {
            deployRequest.addArtifact(artifactResult.getArtifact());
        }
        final Path path = repo.toPath();
        deployRequest.setRepository(RepositoryUtils.toRemoteRepository("test", path.toUri().toURL().toExternalForm()));
        system.deploy(session, deployRequest);
        return path;
    }

    private static Map<String, String> parseArtifactList(java.util.stream.Stream<String> lines) {
        Map<String, String> variables = new HashMap<>();
        final Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            final String line = iterator.next();
            final int i = line.indexOf('=');
            if (i < 0) {
                throw new IllegalArgumentException("Failed to locate '=' character in " + line);
            }
            variables.put(line.substring(0, i), line.substring(i + 1));
        }
        return variables;
    }

    private DefaultArtifact parseGav(String gavString) {
        final String[] gav = gavString.split(":");
        if (gav.length < 3) {
            throw new IllegalArgumentException("Wrong format " + gavString);
        }
        return new DefaultArtifact(gav[0], gav[1], gav[3], gav[4], gav[2]);
    }

    private static void deployFeaturePackRequiringChannels(RepositorySystem system, DefaultRepositorySystemSession session) throws ArtifactResolutionException, IOException, DeploymentException {
        final File zip = resolveExistingCliArtifact(system, session, "org.wildfly.core", "wildfly-core-galleon-pack", null, "zip");

        final Path copied = testRepo.resolve(zip.getName());
        Files.copy(zip.toPath(), copied);

        try (FileSystem fs = FileSystems.newFileSystem(copied, WfCoreTestBase.class.getClassLoader())) {
            final Path channelProperties = fs.getPath("/resources/wildfly/wildfly-channel.properties");
            Files.writeString(channelProperties, "resolution=REQUIRED");
        }

        final DeployRequest deployRequest = new DeployRequest();
        deployRequest.addArtifact(new DefaultArtifact("org.wildfly.core", "wildfly-core-galleon-pack", null, "zip", CHANNEL_REQUIRING_VERSION, null, copied.toFile()));
        deployRequest.setRepository(new RemoteRepository.Builder("test-fp-repo", "default", testRepo.toFile().toURI().toString()).build());
        system.deploy(session, deployRequest);
        Files.delete(copied);
    }

    protected static void emptyLocalCache() {
        final File[] files = localCachePath.toFile().listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            FileUtils.deleteQuietly(file);
        }
    }
}
