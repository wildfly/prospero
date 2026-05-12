package org.wildfly.prospero.galleon;

import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.creator.FeaturePackCreator;
import org.jboss.galleon.repo.RepositoryArtifactResolver;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.repo.SimplisticMavenRepoManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.channel.Channel;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class GalleonFeaturePackAnalyzerTest {

    private FeaturePackCreator creator;
    private RepositoryArtifactResolver repo;
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private Path repoHome;

    @Before
    public void setUp() throws Exception {
        repoHome = temp.newFolder().toPath();
        repo = initRepoManager(repoHome);
        creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);

        createTestFeaturePack();
    }

    @Test
    public void featurePackDependencyIsIncluded() throws Exception {
        final MavenSessionManager msm = new MavenSessionManager();

        final GalleonProvisioningConfig provisioningConfig = GalleonProvisioningConfig.builder()
                .addFeaturePackDep(FeaturePackLocation.fromString("org.test:pack-two:1.0.0"))
                .build();

        final List<Channel> channels = List.of(new Channel.Builder()
                .addRepository("local-test", repoHome.toUri().toString())
                .build());
        final Set<String> featurePacks = new GalleonFeaturePackAnalyzer(channels, msm, null, null).getFeaturePacks(temp.newFile().toPath(), provisioningConfig);
        assertThat(featurePacks)
                .containsOnly("org.test:pack-two", "org.test:pack-one");
    }

    private void createTestFeaturePack() throws Exception {
        final String fpl = "org.test:pack-two:1.0.0";
        creator.newFeaturePack(FeaturePackLocation.fromString("org.test:pack-one:1.0.0").getFPID())
                .newPackage("p2", true)
                .writeContent("prod2/p1.txt", "p2 1.0.0")
                .getFeaturePack();
        creator.newFeaturePack(FeaturePackLocation.fromString(fpl).getFPID())
                .addDependency(FeaturePackLocation.fromString("org.test:pack-one:1.0.0"))
                .newPackage("p2", true)
                .writeContent("prod2/p1.txt", "p2 1.0.0")
                .getFeaturePack();
        creator.install();

        // generate hashes in the repository
        Files.walkFileTree(repoHome, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".zip")) {
                    final String sha = DigestUtils.sha1Hex(new FileInputStream(file.toFile()));
                    Files.writeString(file.getParent().resolve(file.getFileName().toString() + ".sha1"), sha);
                }
                return super.visitFile(file, attrs);
            }
        });
    }

    protected RepositoryArtifactResolver initRepoManager(Path repoHome) {
        return SimplisticMavenRepoManager.getInstance(repoHome);
    }


}