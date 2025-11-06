package org.wildfly.prospero.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.wildfly.channel.Channel;
import org.wildfly.prospero.metadata.ManifestVersionRecord;

public class ChannelVersionReaderTest {

    @Test
    public void noRecordedVersionsReturnsInfoWithoutVersion() throws Exception {
        final ChannelVersionReader reader = new ChannelVersionReader(
                List.of(
                        new Channel.Builder()
                                .setName("test-channel")
                                .setManifestCoordinate("org", "test")
                                .build()
                ),
                new ManifestVersionRecord());

        assertThat(reader.getChannelVersions())
                .contains(new ChannelVersion.Builder()
                        .setChannelName("test-channel")
                        .setLocation("org:test")
                        .setType(ChannelVersion.Type.MAVEN)
                        .build());
    }

    @Test
    public void matchingMavenChannel() throws Exception {
        final ChannelVersionReader reader = new ChannelVersionReader(
                List.of(
                        new Channel.Builder()
                                .setName("test-channel")
                                .setManifestCoordinate("org", "test")
                                .build()
                ),
                new ManifestVersionRecord(
                        "1.0.0",
                        List.of(
                                new ManifestVersionRecord.MavenManifest("org", "test", "1.0.0", "Update 0")
                        ),
                        Collections.emptyList(),
                        Collections.emptyList()
                        ));

        assertThat(reader.getChannelVersions())
                .contains(new ChannelVersion.Builder()
                        .setChannelName("test-channel")
                        .setLocation("org:test")
                        .setPhysicalVersion("1.0.0")
                        .setLogicalVersion("Update 0")
                        .setType(ChannelVersion.Type.MAVEN)
                        .build());
    }

    @Test
    public void matchingUrlChannel() throws Exception {
        final ChannelVersionReader reader = new ChannelVersionReader(
                List.of(
                        new Channel.Builder()
                                .setName("test-channel")
                                .setManifestUrl(new URL("http://test.te"))
                                .build()
                ),
                new ManifestVersionRecord(
                        "1.0.0",
                        Collections.emptyList(),
                        List.of(
                                new ManifestVersionRecord.UrlManifest("http://test.te", "abcd", "Update 0")
                        ),
                        Collections.emptyList()
                ));

        assertThat(reader.getChannelVersions())
                .contains(new ChannelVersion.Builder()
                        .setChannelName("test-channel")
                        .setLocation("http://test.te")
                        .setPhysicalVersion("abcd")
                        .setLogicalVersion("Update 0")
                        .setType(ChannelVersion.Type.URL)
                        .build());
    }

    @Test
    public void matchingOpenChannel() throws Exception {
        final ChannelVersionReader reader = new ChannelVersionReader(
                List.of(
                        new Channel.Builder()
                                .setName("test-channel")
                                .addRepository("test-repo", "http://test.te")
                                .setResolveStrategy(Channel.NoStreamStrategy.LATEST)
                                .build()
                ),
                new ManifestVersionRecord(
                        "1.0.0",
                        Collections.emptyList(),
                        Collections.emptyList(),
                        List.of(
                                new ManifestVersionRecord.NoManifest(List.of("test-repo"), "latest")
                        )
                ));

        assertThat(reader.getChannelVersions())
                .contains(new ChannelVersion.Builder()
                        .setChannelName("test-channel")
                        .setType(ChannelVersion.Type.OPEN)
                        .setLocation("latest@[test-repo]")
                        .build());
    }

}