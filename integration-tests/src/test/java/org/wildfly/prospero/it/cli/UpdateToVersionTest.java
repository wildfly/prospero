package org.wildfly.prospero.it.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.wildfly.prospero.test.TestLocalRepository.GALLEON_PLUGINS_VERSION;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.Before;
import org.junit.Test;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.Stream;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.cli.commands.CliConstants;
import org.wildfly.prospero.it.ExecutionUtils;
import org.wildfly.prospero.it.utils.TestProperties;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.test.TestInstallation;
import org.wildfly.prospero.test.TestLocalRepository;

public class UpdateToVersionTest extends CliTestBase {
    protected static final String COMMONS_IO_VERSION = "2.18.0";
    protected static final String COMMONS_CODEC_VERSION = "1.17.1";

    private TestInstallation testInstallation;
    private Channel testChannel;
    private Channel testChannel2;
    private Channel testUrlChannel;
    private URL manifestUrl;
    private URL manifestUrl2;

    @Before
    public void setUp() throws Exception {

        TestLocalRepository testLocalRepository = prepareLocalRepository(temp.newFolder("local-repo").toPath());
        testInstallation = new TestInstallation(temp.newFolder("server").toPath());
        testChannel = new Channel.Builder()
                .setName("test-channel")
                .addRepository("local-repo", testLocalRepository.getUri().toString())
                .setManifestCoordinate("org.test", "test-channel")
                .build();
        testChannel2 = new Channel.Builder()
                .setName("another-channel")
                .addRepository("local-repo", testLocalRepository.getUri().toString())
                .setManifestCoordinate("org.test", "another-channel")
                .build();
        manifestUrl = testLocalRepository.getUri().resolve("org/test/test-channel/1.0.0/test-channel-1.0.0-manifest.yaml").toURL();
        manifestUrl2 = testLocalRepository.getUri().resolve("org/test/test-channel/1.0.1/test-channel-1.0.1-manifest.yaml").toURL();
        testUrlChannel = new Channel.Builder()
                .setName("test-channel")
                .addRepository("local-repo", testLocalRepository.getUri().toString())
                .setManifestUrl(manifestUrl)
                .build();
    }

    @Test
    public void installSpecificVersionOfManifest() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), CliConstants.MANIFEST_VERSIONS, "test-channel::1.0.0");

        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION);
        testInstallation.verifyInstallationMetadataPresent();
    }

    @Test
    public void updateServerToNonLatestVersion() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), CliConstants.MANIFEST_VERSIONS, "test-channel::1.0.0");

        testInstallation.update(CliConstants.MANIFEST_VERSIONS, "test-channel::1.0.1");

        testInstallation.verifyModuleJar("commons-io", "commons-io", bump(COMMONS_IO_VERSION));
        testInstallation.verifyInstallationMetadataPresent();
    }

    @Test
    public void downgradeServerToNonLatestVersion() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), "--manifest-versions=test-channel::1.0.1");

        testInstallation.update(Collections.emptyList(), "--manifest-versions=test-channel::1.0.0", CliConstants.YES);

        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION);
        testInstallation.verifyInstallationMetadataPresent();
    }

    @Test
    public void updateWithPrepareApplyServerToNonLatestVersion() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), CliConstants.MANIFEST_VERSIONS, "test-channel::1.0.0");

        final Path candidatePath = temp.newFolder("candidate").toPath();
        testInstallation.prepareUpdate(candidatePath, CliConstants.MANIFEST_VERSIONS, "test-channel::1.0.1");
        testInstallation.apply(candidatePath);

        testInstallation.verifyModuleJar("commons-io", "commons-io", bump(COMMONS_IO_VERSION));
        testInstallation.verifyInstallationMetadataPresent();
    }

    @Test
    public void warnIfDowngradeServerToNonLatestVersion() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), "--manifest-versions=test-channel::1.0.1", "-vv");

        testInstallation.updateWithCheck(
                List.of(
                        Pair.of(CliMessages.MESSAGES.continueWithUpdate(), "y"),
                        Pair.of(CliMessages.MESSAGES.continueWithUpdate(), "y")
                ),
                (ExecutionUtils.ExecutionResult e)-> {
                    try {
                        e.assertReturnCode(ReturnCodes.SUCCESS);
                        assertThat(e.getCommandOutput())
                                .contains("The update will downgrade following channels:")
                                .contains("  * test-channel: 1.0.1 (Logical version 1.0.1)  ->  1.0.0 (Logical version 1.0.0)");
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                },
                "--manifest-versions=test-channel::1.0.0", "-vv");

        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION);
        testInstallation.verifyInstallationMetadataPresent();
    }

    @Test
    public void rejectDowngradeServerIfVersionNotSpecified() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), "--manifest-versions=test-channel::1.0.1", "-vv");

        final TestLocalRepository newRepo = new TestLocalRepository(temp.newFolder("downgrade-repo").toPath(), List.of(new URL("https://repo1.maven.org/maven2")));

        newRepo.deploy(
                new DefaultArtifact("org.test", "test-channel", "manifest", "yaml","1.0.0"),
                new ChannelManifest("test-manifest", "manifest-id", "Logical version 1.0.0", "description", null, List.of(
                        new Stream("org.wildfly.galleon-plugins", "wildfly-config-gen", GALLEON_PLUGINS_VERSION),
                        new Stream("org.wildfly.galleon-plugins", "wildfly-galleon-plugins", GALLEON_PLUGINS_VERSION),
                        new Stream("commons-io", "commons-io", COMMONS_IO_VERSION),
                        new Stream("org.test", "pack-one", "1.0.0")
        )));

        testInstallation.updateWithCheck(
                Collections.emptyList(),
                (ExecutionUtils.ExecutionResult e)-> {
                    try {
                        e.assertReturnCode(ReturnCodes.PROCESSING_ERROR);
                        assertThat(e.getCommandOutput())
                                .contains("The update will downgrade following channels:")
                                .contains("  * test-channel: 1.0.1 (Logical version 1.0.1)  ->  1.0.0");
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }

                },
                "--repositories", newRepo.getUri().toString());

        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION + ".CP-01");
        testInstallation.verifyInstallationMetadataPresent();
    }

    @Test
    public void installMultiChannelAllManifestsRequired() throws Exception {
        ExecutionUtils.ExecutionResult result = testInstallation.install(ReturnCodes.INVALID_ARGUMENTS,
                "org.test:pack-two:1.0.0", List.of(testChannel, testChannel2),
                "--manifest-versions=test-channel::1.0.1", "-vv");
        assertThat(result.getCommandOutput()).contains("specify versions for all the channels");
    }

    @Test
    public void updateMultiChannelAllManifestRequired() throws Exception {
        testInstallation.install("org.test:pack-two:1.0.0", List.of(testChannel, testChannel2),
                "--manifest-versions=test-channel::1.0.0,another-channel::1.0.0", "-vv");

        ExecutionUtils.ExecutionResult result = testInstallation.update(ReturnCodes.INVALID_ARGUMENTS, Collections.emptyList(),
                "--manifest-versions=test-channel::1.0.0", CliConstants.YES, "-vv");
        assertThat(result.getCommandOutput()).contains("specify versions for all the channels");
    }

    @Test
    public void updateMultiChannelUpdateSingle() throws Exception {
        testInstallation.install("org.test:pack-two:1.0.0", List.of(testChannel, testChannel2),
                "--manifest-versions=test-channel::1.0.0,another-channel::1.0.0", "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION);
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION);

        testInstallation.update(Collections.emptyList(), "--manifest-versions=test-channel::1.0.1,another-channel::1.0.0", CliConstants.YES, "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION + ".CP-01"); // modified
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION); // not modified
    }

    @Test
    public void updateMultiChannelUpdateAll() throws Exception {
        testInstallation.install("org.test:pack-two:1.0.0", List.of(testChannel, testChannel2),
                "--manifest-versions=test-channel::1.0.0,another-channel::1.0.0", "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION);
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION);

        testInstallation.update(Collections.emptyList(), "--manifest-versions=test-channel::1.0.1,another-channel::1.0.1", CliConstants.YES, "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION + ".CP-01"); // modified
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION + ".CP-01"); // modified
    }

    @Test
    public void downgradeMultiChannelRejected() throws Exception {
        testInstallation.install("org.test:pack-two:1.0.0", List.of(testChannel, testChannel2),
                "--manifest-versions=test-channel::1.0.1,another-channel::1.0.0", "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION + ".CP-01");
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION);

        testInstallation.updateWithCheck(List.of(
                        Pair.of(CliMessages.MESSAGES.continueWithUpdate(), "n")
                ),
                (ExecutionUtils.ExecutionResult e) -> {
                    try {
                        e.assertReturnCode(ReturnCodes.PROCESSING_ERROR);
                        assertThat(e.getCommandOutput())
                                .contains("The update will downgrade following channels:")
                                .contains("  * test-channel: 1.0.1 (Logical version 1.0.1)  ->  1.0.0 (Logical version 1.0.0)");
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                }, "--manifest-versions=test-channel::1.0.0,another-channel::1.0.0", "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION + ".CP-01");
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION);
    }

    @Test
    public void downgradeAndUpgradeMultiChannel() throws Exception {
        testInstallation.install("org.test:pack-two:1.0.0", List.of(testChannel, testChannel2),
                "--manifest-versions=test-channel::1.0.1,another-channel::1.0.0", "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION + ".CP-01");
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION);

        testInstallation.update(Collections.emptyList(), "--manifest-versions=test-channel::1.0.0,another-channel::1.0.1", CliConstants.YES, "-vv");
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION); // downgraded
        testInstallation.verifyModuleJar("commons-codec", "commons-codec", COMMONS_CODEC_VERSION + ".CP-01"); // upgraded
    }

    @Test
    public void listChannelVersionUpdates() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), "--manifest-versions=test-channel::1.0.1", "-vv");

        assertThat(testInstallation.listChannelManifestUpdates(false).split("available versions")[1])
                .contains("1.0.2 (Logical version 1.0.2)")
                .doesNotContain("1.0.0 (Logical version 1.0.0)",
                        "1.0.1 (Logical version 1.0.1)");
    }

    @Test
    public void listAllChannelVersion() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), "--manifest-versions=test-channel::1.0.1", "-vv");

        assertThat(testInstallation.listChannelManifestUpdates(true).split("available versions")[1])
                .contains("1.0.0", "1.0.1", "1.0.2");
    }

    @Test
    public void listUpdatesToSpecificVersion() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testChannel), "--manifest-versions=test-channel::1.0.0", "-vv");

        assertThat(testInstallation.listUpdates(CliConstants.MANIFEST_VERSIONS, "test-channel::1.0.1"))
                .containsPattern("commons-io:commons-io\\s+2.18.0\\s+==>\\s+2.18.0.CP-01");
    }

    @Test
    public void updateUrlManifest() throws Exception {
        testInstallation.install("org.test:pack-one:1.0.0", List.of(testUrlChannel));
        testInstallation.verifyModuleJar("commons-io", "commons-io", COMMONS_IO_VERSION);

        testInstallation.update(CliConstants.MANIFEST_VERSIONS, "test-channel::" + manifestUrl2.toString());

        testInstallation.verifyModuleJar("commons-io", "commons-io", bump(COMMONS_IO_VERSION));
        testInstallation.verifyInstallationMetadataPresent();
        try (InstallationMetadata metadata = testInstallation.readInstallationMetadata()) {
            // Verify manifest url in manifest_version.yaml file is updated to manifestUrl2
            assertThat(metadata.getManifestVersions()).satisfies(versionRecord -> {
                assertThat(versionRecord).isPresent();
                List<String> currentManifestUrls = versionRecord.get().getUrlManifests().stream()
                        .map(ManifestVersionRecord.UrlManifest::getUrl).toList();
                assertThat(currentManifestUrls).contains(manifestUrl2.toString());
            });

            // Verify manifest url in installer-channels.yaml file *has not changed* - the installation is still
            // subscribed to the original manifest URL.
            List<URL> installedChannelsUrls = metadata.getProsperoConfig().getChannels().stream()
                    .map(c -> c.getManifestCoordinate().getUrl())
                    .toList();
            assertThat(installedChannelsUrls).contains(manifestUrl);
        }
    }

    public static TestLocalRepository prepareLocalRepository(Path repoPath) throws Exception {
        List<URL> testRepositories = new ArrayList<>(TestProperties.testReposToUrls());
        testRepositories.add(new URL("https://repo1.maven.org/maven2"));
        TestLocalRepository localRepository = new TestLocalRepository(repoPath, testRepositories);


        localRepository.deployGalleonPlugins();

        // Install different versions of commons-io
        Artifact resolved = localRepository.resolveAndDeploy(new DefaultArtifact("commons-io", "commons-io", "jar", COMMONS_IO_VERSION));
        resolved = resolved.setVersion(bump(resolved.getVersion()));
        localRepository.deploy(resolved);
        localRepository.deploy(resolved.setVersion(bump(resolved.getVersion())));

        // Install different versions of commons-codec
        resolved = localRepository.resolveAndDeploy(new DefaultArtifact("commons-codec", "commons-codec", "jar", COMMONS_CODEC_VERSION));
        resolved = resolved.setVersion(bump(resolved.getVersion()));
        localRepository.deploy(resolved);
        localRepository.deploy(resolved.setVersion(bump(resolved.getVersion())));

        // deploy test manifests updating the commons-io in each
        localRepository.deploy(
                new DefaultArtifact("org.test", "test-channel", "manifest", "yaml","1.0.0"),
                new ChannelManifest("test-manifest", "manifest-id", "Logical version 1.0.0", "Description", null, List.of(
                        new Stream("org.wildfly.galleon-plugins", "wildfly-config-gen", GALLEON_PLUGINS_VERSION),
                        new Stream("org.wildfly.galleon-plugins", "wildfly-galleon-plugins", GALLEON_PLUGINS_VERSION),
                        new Stream("commons-io", "commons-io", COMMONS_IO_VERSION),
                        new Stream("org.test", "pack-one", "1.0.0")
                )));

        localRepository.deploy(
                new DefaultArtifact("org.test", "test-channel", "manifest", "yaml","1.0.1"),
                new ChannelManifest("test-manifest", "manifest-id", "Logical version 1.0.1", "Description", null, List.of(
                        new Stream("org.wildfly.galleon-plugins", "wildfly-config-gen", GALLEON_PLUGINS_VERSION),
                        new Stream("org.wildfly.galleon-plugins", "wildfly-galleon-plugins", GALLEON_PLUGINS_VERSION),
                        new Stream("commons-io", "commons-io", bump(COMMONS_IO_VERSION)),
                        new Stream("org.test", "pack-one", "1.0.0")
                )));

        localRepository.deploy(
                new DefaultArtifact("org.test", "test-channel", "manifest", "yaml","1.0.2"),
                new ChannelManifest("test-manifest", "manifest-id", "Logical version 1.0.2", "Description", null, List.of(
                        new Stream("org.wildfly.galleon-plugins", "wildfly-config-gen", GALLEON_PLUGINS_VERSION),
                        new Stream("org.wildfly.galleon-plugins", "wildfly-galleon-plugins", GALLEON_PLUGINS_VERSION),
                        new Stream("commons-io", "commons-io", bump(bump(COMMONS_IO_VERSION))),
                        new Stream("org.test", "pack-one", "1.0.0")
                )));

        // deploy a different manifest that contains commons-codec (for multi-channel tests)
        localRepository.deploy(
                new DefaultArtifact("org.test", "another-channel", "manifest", "yaml","1.0.0"),
                new ChannelManifest("test-manifest", "another-manifest-id", "Logical version 1.0.0", "Description", null, List.of(
                        new Stream("commons-codec", "commons-codec", COMMONS_CODEC_VERSION),
                        new Stream("org.test", "pack-two", "1.0.0")
                )));

        localRepository.deploy(
                new DefaultArtifact("org.test", "another-channel", "manifest", "yaml","1.0.1"),
                new ChannelManifest("test-manifest", "another-manifest-id", "Logical version 1.0.1", "Description", null, List.of(
                        new Stream("commons-codec", "commons-codec", bump(COMMONS_CODEC_VERSION)),
                        new Stream("org.test", "pack-two", "1.0.0")
                )));

        localRepository.deploy(TestInstallation.fpBuilder("org.test:pack-one:1.0.0")
                .addModule("commons-io", "commons-io", COMMONS_IO_VERSION)
                .build());

        localRepository.deploy(TestInstallation.fpBuilder("org.test:pack-two:1.0.0")
                .addModule("commons-io", "commons-io", COMMONS_IO_VERSION)
                .addModule("commons-codec", "commons-codec", COMMONS_CODEC_VERSION)
                .build());

        return localRepository;
    }

    private static String bump(String version) {
        final Pattern pattern = Pattern.compile(".*\\.CP-(\\d{2})");
        final Matcher matcher = pattern.matcher(version);
        if (matcher.matches()) {
            final String suffixVersion = matcher.group(1);
            final int ver = Integer.parseInt(suffixVersion) + 1;
            final String replaced = version.replace(".CP-" + suffixVersion, String.format(".CP-%02d", ver));
            return replaced;
        } else {
            return version + ".CP-01";
        }
    }
}
