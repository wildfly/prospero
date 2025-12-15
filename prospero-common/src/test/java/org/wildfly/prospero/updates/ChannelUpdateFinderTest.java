package org.wildfly.prospero.updates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.prospero.api.ChannelVersion;

@RunWith(MockitoJUnitRunner.class)
public class ChannelUpdateFinderTest {

    private final GenericVersionScheme versionScheme = new GenericVersionScheme();
    @Mock
    private RepositorySystem system;

    @Mock
    private RepositorySystemSession session;

    @Captor
    private ArgumentCaptor<VersionRangeRequest> rangeRequests;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private ChannelUpdateFinder channelUpdateFinder;

    @Before
    public void setUp() throws Exception {
        channelUpdateFinder = new ChannelUpdateFinder(system, session);
    }

    @Test
    public void listNewerUpdatesOfManifest() throws Exception {
        final VersionRangeResult rangeRes = Mockito.mock(VersionRangeResult.class);
        when(rangeRes.getVersions()).thenReturn(List.of(versionScheme.parseVersion("1.0.1")));
        final ArtifactResult artifactRes = Mockito.mock(ArtifactResult.class);

        final File file = temp.newFile();
        ChannelManifest.Builder builder = new ChannelManifest.Builder();
        builder.setLogicalVersion("Logical version 1.0.1");
        Files.writeString(file.toPath(), ChannelManifestMapper.toYaml(builder.build()));

        when(artifactRes.getArtifact()).thenReturn(new DefaultArtifact("org.wildfly.channels", "wildfly-ee", "manifest", "yaml", "1.0.1", null, file));
        when(system.resolveVersionRange(eq(session), rangeRequests.capture())).thenReturn(rangeRes);
        when(system.resolveArtifacts(eq(session), any())).thenReturn(List.of(artifactRes));
        final Collection<ChannelVersion> versions = channelUpdateFinder
                .findNewerChannelVersions(new Channel.Builder()
                        .setName("test-channel")
                        .setManifestCoordinate("org.wildfly.channels", "wildfly-ee")
                        .addRepository("central", "https://repo1.maven.org/maven2/")
                        .build(),
                        "1.0.0");

        assertThat(versions)
                .containsExactly(new ChannelVersion.Builder()
                        .setChannelName("test-channel")
                        .setLocation("org.wildfly.channels:wildfly-ee")
                        .setPhysicalVersion("1.0.1")
                        .setLogicalVersion("Logical version 1.0.1")
                        .build());
        assertThat(rangeRequests.getValue().getArtifact().getVersion())
                .isEqualTo("(1.0.0,)");
    }

    @Test
    public void listAllUpdatesOfManifest() throws Exception {
        final VersionRangeResult rangeRes = Mockito.mock(VersionRangeResult.class);
        when(rangeRes.getVersions()).thenReturn(List.of(versionScheme.parseVersion("1.0.0"), versionScheme.parseVersion("1.0.1")));
        final ArtifactResult artifactRes = Mockito.mock(ArtifactResult.class);

        final File file = temp.newFile();
        ChannelManifest.Builder builder = new ChannelManifest.Builder();
        builder.setLogicalVersion("Logical version 1.0.1");
        Files.writeString(file.toPath(), ChannelManifestMapper.toYaml(builder.build()));

        when(artifactRes.getArtifact()).thenReturn(new DefaultArtifact("org.wildfly.channels", "wildfly-ee", "manifest", "yaml", "1.0.1", null, file));
        when(system.resolveVersionRange(eq(session), rangeRequests.capture())).thenReturn(rangeRes);
        when(system.resolveArtifacts(eq(session), any())).thenReturn(List.of(artifactRes));
        final Collection<ChannelVersion> versions = channelUpdateFinder
                .findAvailableChannelVersions(new Channel.Builder()
                                .setName("test-channel")
                                .setManifestCoordinate("org.wildfly.channels", "wildfly-ee")
                                .addRepository("central", "https://repo1.maven.org/maven2/")
                                .build());

        assertThat(versions)
                .containsExactly(new ChannelVersion.Builder()
                        .setChannelName("test-channel")
                        .setLocation("org.wildfly.channels:wildfly-ee")
                        .setPhysicalVersion("1.0.1")
                        .setLogicalVersion("Logical version 1.0.1")
                        .build());
        assertThat(rangeRequests.getValue().getArtifact().getVersion())
                .isEqualTo("(0,)");
    }

    @Test
    public void noUpdatesFoundReturnsAnEmptyList() throws Exception {
        final VersionRangeResult rangeRes = Mockito.mock(VersionRangeResult.class);
        when(rangeRes.getVersions()).thenReturn(Collections.emptyList());
        when(system.resolveVersionRange(eq(session), rangeRequests.capture())).thenReturn(rangeRes);
        final Collection<ChannelVersion> versions = channelUpdateFinder
                .findNewerChannelVersions(new Channel.Builder()
                                .setName("test-channel")
                                .setManifestCoordinate("org.wildfly.channels", "wildfly-ee")
                                .addRepository("central", "https://repo1.maven.org/maven2/")
                                .build(),
                        "1.0.0");

        assertThat(versions)
                .isEmpty();
    }

    @Test
    public void nonMavenManifestThrowsException() {
        assertThatThrownBy(()-> channelUpdateFinder
                .findNewerChannelVersions(new Channel.Builder()
                                .setName("test-channel")
                                .setManifestUrl(URI.create("file:test-manifest.yaml").toURL())
                                .addRepository("central", "https://repo1.maven.org/maven2/")
                                .build(),
                        "1.0.0"))
                .hasMessageContaining("The channel test-channel needs to have a maven manifest to be able to retrieve channel updates.");
    }

}