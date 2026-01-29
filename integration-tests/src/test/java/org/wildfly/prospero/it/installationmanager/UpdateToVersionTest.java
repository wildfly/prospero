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

package org.wildfly.prospero.it.installationmanager;

import org.assertj.core.groups.Tuple;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.channel.Channel;
import org.wildfly.installationmanager.AvailableManifestVersions;
import org.wildfly.installationmanager.InstallationUpdates;
import org.wildfly.installationmanager.ManifestVersion;
import org.wildfly.installationmanager.ManifestVersionPair;
import org.wildfly.installationmanager.MavenOptions;
import org.wildfly.installationmanager.spi.InstallationManager;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.cli.commands.CliConstants;
import org.wildfly.prospero.spi.ProsperoInstallationManagerFactory;
import org.wildfly.prospero.test.TestInstallation;
import org.wildfly.prospero.test.TestLocalRepository;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class UpdateToVersionTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path localRepoPath;
    private Path candidatePath;
    private Path serverPath;
    private TestInstallation installation;
    private Channel channel;

    @Before
    public void setup() throws Exception {
        localRepoPath = temp.newFolder("local-repo").toPath();
        candidatePath = temp.newFolder("candidate").toPath();
        serverPath = temp.newFolder("server").toPath();

        installation = new TestInstallation(serverPath);
        TestLocalRepository localRepository = org.wildfly.prospero.it.cli.UpdateToVersionTest.prepareLocalRepository(localRepoPath);
        channel = new Channel.Builder()
                .setName("test-channel")
                .setDescription("channel description")
                .addRepository("local-repo", localRepository.getUri().toString())
                .setManifestCoordinate("org.test", "test-channel")
                .build();
    }

    @Test
    public void prepareUpdate_upgradeToLatestVersionNullManifestVersions() throws Exception {
        prepareInstallation("1.0.0");

        assertThat(installationManager().prepareUpdate(candidatePath, Collections.emptyList(), null, false)).isTrue(); // No target manifest version given

        try (InstallationMetadata candidateMetadata = InstallationMetadata.loadInstallation(candidatePath)) {
            assertThat(candidateMetadata.getManifestVersions()).satisfies(optional -> {
                assertThat(optional).isPresent();
                assertThat(optional.get().getMavenManifests().get(0).getVersion()).isEqualTo("1.0.2");
            });
        }
    }

    @Test
    public void prepareUpdate_upgradeToLatestVersion() throws Exception {
        prepareInstallation("1.0.0");

        assertThat(installationManager().prepareUpdate(candidatePath, Collections.emptyList(), false)).isTrue(); // No target manifest version given

        try (InstallationMetadata candidateMetadata = InstallationMetadata.loadInstallation(candidatePath)) {
            assertThat(candidateMetadata.getManifestVersions()).satisfies(optional -> {
                assertThat(optional).isPresent();
                assertThat(optional.get().getMavenManifests().get(0).getVersion()).isEqualTo("1.0.2");
            });
        }
    }

    @Test
    public void prepareUpdate_upgradeToSpecificVersion() throws Exception {
        prepareInstallation("1.0.0");

        ManifestVersion manifestVersion = new ManifestVersion("test-channel", null, "1.0.1",
                ManifestVersion.Type.MAVEN);
        assertThat(installationManager().prepareUpdate(candidatePath, Collections.emptyList(), List.of(manifestVersion),
                false)).isTrue();

        try (InstallationMetadata candidateMetadata = InstallationMetadata.loadInstallation(candidatePath)) {
            assertThat(candidateMetadata.getManifestVersions()).satisfies(optional -> {
                assertThat(optional).isPresent();
                assertThat(optional.get().getMavenManifests().get(0).getVersion()).isEqualTo("1.0.1");
            });
        }
    }

    @Test
    public void prepareUpdate_downgradeToSpecificVersionDenied() throws Exception {
        prepareInstallation("1.0.1");

        ManifestVersion manifestVersion = new ManifestVersion("test-channel", null, "1.0.0",
                ManifestVersion.Type.MAVEN);
        try {
            installationManager().prepareUpdate(candidatePath, Collections.emptyList(), List.of(manifestVersion),
                    false); // allowManifestDowngrades=false
            fail("Exception was expected, because downgrade operation was not allowed.");
        } catch (RuntimeException e) {
            // expected
        }
    }

    @Test
    public void prepareUpdate_downgradeToSpecificVersion() throws Exception {
        prepareInstallation("1.0.1");

        ManifestVersion manifestVersion = new ManifestVersion("test-channel", null, "1.0.0",
                ManifestVersion.Type.MAVEN);
        assertThat(installationManager().prepareUpdate(candidatePath, Collections.emptyList(), List.of(manifestVersion),
                true)).isTrue(); // allowManifestDowngrades=true

        try (InstallationMetadata candidateMetadata = InstallationMetadata.loadInstallation(candidatePath)) {
            assertThat(candidateMetadata.getManifestVersions()).satisfies(optional -> {
                assertThat(optional).isPresent();
                assertThat(optional.get().getMavenManifests().get(0).getVersion()).isEqualTo("1.0.0");
            });
        }
    }

    @Test
    public void findUpdates_toLatestVersion() throws Exception {
        prepareInstallation("1.0.0");

        InstallationUpdates updates = installationManager().findInstallationUpdates(Collections.emptyList());// No target manifest version given

        assertThat(updates.artifactUpdates()).satisfies(artifactUpdates -> {
            assertThat(artifactUpdates.size()).isEqualTo(1);
            assertThat(artifactUpdates.get(0).getNewVersion()).isEqualTo("2.18.0.CP-02");
        });
        assertThat(updates.manifestUpdates()).satisfies(manifestUpdates -> {
            assertThat(manifestUpdates.size()).isEqualTo(1);
            assertThat(manifestUpdates.get(0).getNewVersion())
                    .isEqualTo(new ManifestVersionPair("1.0.2", "Logical version 1.0.2"));
        });
    }

    @Test
    public void findUpdates_toSpecificVersion() throws Exception {
        prepareInstallation("1.0.0");

        ManifestVersion manifestVersion = new ManifestVersion("test-channel", null, "1.0.1", ManifestVersion.Type.MAVEN);
        InstallationUpdates updates = installationManager().findInstallationUpdates(Collections.emptyList(), List.of(manifestVersion));

        assertThat(updates.artifactUpdates().size()).isEqualTo(1);
        assertThat(updates.artifactUpdates().get(0).getNewVersion()).isEqualTo("2.18.0.CP-01");
    }

    @Test
    public void findUpdates_downgradeToSpecificVersion() throws Exception {
        prepareInstallation("1.0.1");

        ManifestVersion manifestVersion = new ManifestVersion("test-channel", null, "1.0.0", ManifestVersion.Type.MAVEN);
        InstallationUpdates updates = installationManager().findInstallationUpdates(Collections.emptyList(), List.of(manifestVersion));

        assertThat(updates.artifactUpdates().size()).isEqualTo(1);
        assertThat(updates.artifactUpdates().get(0).getNewVersion()).isEqualTo("2.18.0");
    }

    @Test
    public void findAvailableManifestVersions_upgradesOnly() throws Exception {
        prepareInstallation("1.0.1");

        List<AvailableManifestVersions> manifestsWithUpgrades = installationManager().findAvailableManifestVersions(Collections.emptyList(), false);

        assertThat(manifestsWithUpgrades.size()).isEqualTo(1);
        assertThat(manifestsWithUpgrades.get(0)).satisfies(manifestUpgrades -> {
            assertThat(manifestUpgrades.getChannelName()).isEqualTo("test-channel");
            assertThat(manifestUpgrades.getLocation()).isEqualTo("org.test:test-channel");
            assertThat(manifestUpgrades.getCurrentVersion().getPhysicalVersion()).isEqualTo("1.0.1");
            assertThat(manifestUpgrades.getCurrentVersion().getLogicalVersion()).isEqualTo("Logical version 1.0.1");
            assertThat(manifestUpgrades.getAvailableVersions()).extracting("physicalVersion", "logicalVersion")
                    .containsExactly(
                            Tuple.tuple("1.0.2", "Logical version 1.0.2")
                    );
        });
    }

    @Test
    public void findAvailableManifestVersions_includeDowngrades() throws Exception {
        prepareInstallation("1.0.1");

        List<AvailableManifestVersions> manifestsWithUpgrades = installationManager().findAvailableManifestVersions(Collections.emptyList(), true);

        assertThat(manifestsWithUpgrades.size()).isEqualTo(1);
        assertThat(manifestsWithUpgrades.get(0)).satisfies(manifestUpgrades -> {
            assertThat(manifestUpgrades.getChannelName()).isEqualTo("test-channel");
            assertThat(manifestUpgrades.getLocation()).isEqualTo("org.test:test-channel");
            assertThat(manifestUpgrades.getCurrentVersion().getPhysicalVersion()).isEqualTo("1.0.1");
            assertThat(manifestUpgrades.getCurrentVersion().getLogicalVersion()).isEqualTo("Logical version 1.0.1");
            assertThat(manifestUpgrades.getAvailableVersions()).extracting("physicalVersion", "logicalVersion")
                    .containsExactly(
                            Tuple.tuple("1.0.0", "Logical version 1.0.0"),
                            Tuple.tuple("1.0.2", "Logical version 1.0.2")
                    );
        });
    }

    private void prepareInstallation(String version) throws Exception {
        installation.install("org.test:pack-one:1.0.0", List.of(channel), CliConstants.MANIFEST_VERSIONS, "test-channel::" + version);

        // Verify that we've provisioned the version we wanted
        try (InstallationMetadata installationMetadata = InstallationMetadata.loadInstallation(serverPath)) {
            assertThat(installationMetadata.getManifestVersions()).satisfies(optional -> {
                assertThat(optional).isPresent();
                assertThat(optional.get().getMavenManifests().get(0).getVersion()).isEqualTo(version);
            });
        }
    }

    private InstallationManager installationManager() throws Exception {
        return new ProsperoInstallationManagerFactory().create(serverPath, new MavenOptions(localRepoPath, false));
    }

}
