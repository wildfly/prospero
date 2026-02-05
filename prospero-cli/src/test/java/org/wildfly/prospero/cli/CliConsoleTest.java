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

package org.wildfly.prospero.cli;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.ChannelVersionChange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class CliConsoleTest extends AbstractConsoleTest {
    private CliConsole cliConsole;

    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;

    @Before
    public void setUp() {
        cliConsole = new CliConsole();
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        System.setOut(new PrintStream(outputStream));
    }

    @Test
    public void testUpdatesFoundWithUpdates_ArtifactChange_update() {
        final List<ArtifactChange> artifactChanges = new ArrayList<>();
        final ArtifactChange artifactChange = ArtifactChange.updated(new DefaultArtifact("test.group", "test-artifact2", "jar", "2.0.0"), new DefaultArtifact("test.group", "test-artifact2", "jar", "2.1.0"), "channel-1");
        artifactChanges.add(artifactChange);

        final List<ChannelVersionChange> manifestChanges = new ArrayList<>();
        ChannelVersion originalManifest = new ChannelVersion.Builder()
                .setLocation("groupId:artifactId")
                .setType(ChannelVersion.Type.MAVEN)
                .setPhysicalVersion("1.0.0")
                .setChannelName("channel-0")
                .build();
        ChannelVersion newManifest = new ChannelVersion.Builder()
                .setLocation("groupId:artifactId")
                .setType(ChannelVersion.Type.MAVEN)
                .setPhysicalVersion("1.0.1")
                .setChannelName("channel-0")
                .build();
        final ChannelVersionChange manifestChange = new ChannelVersionChange("channel-0", originalManifest, newManifest);
        manifestChanges.add(manifestChange);

        cliConsole.updatesFound(artifactChanges, manifestChanges);
        final String capturedOutput = outputStream.toString();

        assertThat(capturedOutput)
                .contains("test.group:test-artifact2")
                .contains("[channel-1]");
    }
    @Test
    public void testUpdatesFoundWithUpdates_ArtifactChange_add() {
        final List<ArtifactChange> artifactChanges = new ArrayList<>();
        final ArtifactChange artifactChange = ArtifactChange.added(new DefaultArtifact("test.group", "test-artifact2", "jar", "2.0.0"), "channel-1");
        artifactChanges.add(artifactChange);

        final List<ChannelVersionChange> manifestChanges = new ArrayList<>();
        ChannelVersion originalManifest = new ChannelVersion.Builder()
                .setLocation("groupId:artifactId")
                .setType(ChannelVersion.Type.MAVEN)
                .setPhysicalVersion("1.0.0")
                .setChannelName("channel-0")
                .build();
        ChannelVersion newManifest = new ChannelVersion.Builder()
                .setLocation("groupId:artifactId")
                .setType(ChannelVersion.Type.MAVEN)
                .setPhysicalVersion("1.0.1")
                .setChannelName("channel-0")
                .build();
        final ChannelVersionChange manifestChange = new ChannelVersionChange("channel-0", originalManifest, newManifest);
        manifestChanges.add(manifestChange);

        cliConsole.updatesFound(artifactChanges, manifestChanges);
        final String capturedOutput = outputStream.toString();

        assertThat(capturedOutput)
                .contains("test.group:test-artifact2")
                .contains("[channel-1]");
    }

    @After
    public void destory() throws IOException {
        outputStream.close();
        cliConsole = null;
        System.setOut(originalOut);
    }
}
