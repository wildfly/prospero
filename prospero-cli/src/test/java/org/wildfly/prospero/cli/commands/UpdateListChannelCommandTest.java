package org.wildfly.prospero.cli.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.Assert;
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
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.test.MetadataTestUtils;
import org.wildfly.prospero.updates.ChannelsUpdateResult;

@RunWith(MockitoJUnitRunner.class)
public class UpdateListChannelCommandTest extends AbstractMavenCommandTest {

    public static final String A_PROSPERO_FP = UpdateCommand.PROSPERO_FP_GA + ":1.0.0";

    @Mock
    private UpdateAction updateAction;

    @Mock
    private ActionFactory actionFactory;

    @Captor
    private ArgumentCaptor<MavenOptions> mavenOptions;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path installationDir;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        when(actionFactory.update(any(), anyList(), any(), any())).thenReturn(updateAction);
        installationDir = tempFolder.newFolder().toPath();

        MetadataTestUtils.createInstallationMetadata(installationDir);
        MetadataTestUtils.createGalleonProvisionedState(installationDir, A_PROSPERO_FP);
    }

    @Test
    public void currentDirNotValidInstallation() {
        int exitCode = commandLine.execute(CliConstants.Commands.UPDATE, CliConstants.Commands.LIST_CHANNELS);

        Assert.assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(CliMessages.MESSAGES.invalidInstallationDir(UpdateCommand.currentDir().toAbsolutePath()).getMessage());
    }

    @Test
    public void noChannelChangesFound() throws Exception {
        doLocalMock();

        int exitCode = commandLine.execute(getDefaultArguments());

        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        assertThat(getStandardOutput())
                .contains(CliMessages.MESSAGES.noChannelVersionUpdates());
    }

    @Test
    public void unsupportedChannelType() throws Exception {
        final ChannelsUpdateResult updates = new ChannelsUpdateResult(
                new ChannelsUpdateResult.ChannelResult(
                        "test-channel",
                        null));
        when(updateAction.findChannelUpdates(anyBoolean())).thenReturn(updates);

        int exitCode = commandLine.execute(getDefaultArguments());

        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        assertThat(getStandardOutput())
                .contains(CliMessages.MESSAGES.channelVersionListUnsupportedChannelType());
    }

    @Test
    public void mavenChannelUpdateList() throws Exception {
        final ChannelsUpdateResult updates = new ChannelsUpdateResult(
                new ChannelsUpdateResult.ChannelResult(
                        "test-channel",
                        new ChannelVersion.Builder().setPhysicalVersion("1.0.0").build(),
                        List.of(new ChannelVersion.Builder().setPhysicalVersion("1.0.1").build()))
        );
        when(updateAction.findChannelUpdates(anyBoolean())).thenReturn(updates);

        int exitCode = commandLine.execute(getDefaultArguments());

        Assert.assertEquals(ReturnCodes.SUCCESS, exitCode);
        assertThat(getStandardOutput())
                .contains(CliMessages.MESSAGES.channelVersionUpdateListHeader())
                .contains(CliMessages.MESSAGES.channelVersionUpdateListChannelName() + ": test-channel")
                .contains(CliMessages.MESSAGES.channelVersionUpdateListCurrentVersion() + ": 1.0.0")
                .contains(CliMessages.MESSAGES.channelVersionUpdateListAvailableVersions())
                .contains("- 1.0.1");
    }

    @Override
    protected MavenOptions getCapturedMavenOptions() throws Exception {
        Mockito.verify(actionFactory).update(any(), anyList(), mavenOptions.capture(), any());
        return mavenOptions.getValue();
    }

    @Override
    protected String[] getDefaultArguments() {
        return new String[] {CliConstants.Commands.UPDATE, CliConstants.Commands.LIST_CHANNELS, CliConstants.DIR, installationDir.toString()};
    }

    @Override
    protected ActionFactory createActionFactory() {
        return actionFactory;
    }

    @Override
    protected void doLocalMock() throws Exception {
        when(updateAction.findChannelUpdates(anyBoolean())).thenReturn(new ChannelsUpdateResult());
    }
}
