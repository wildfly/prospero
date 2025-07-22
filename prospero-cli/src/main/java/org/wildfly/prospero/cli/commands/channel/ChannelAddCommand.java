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

package org.wildfly.prospero.cli.commands.channel;

import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.Repository;
import org.wildfly.channel.InvalidChannelMetadataException;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.actions.MetadataAction;
import org.wildfly.prospero.api.ArtifactUtils;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.api.TemporaryFilesManager;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.cli.ArgumentParsingException;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.RepositoryDefinition;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.cli.commands.AbstractCommand;
import org.wildfly.prospero.cli.commands.CliConstants;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@CommandLine.Command(name = CliConstants.Commands.ADD, sortOptions = false)
public class ChannelAddCommand extends AbstractCommand {

    @CommandLine.Option(names = CliConstants.CHANNEL_NAME, required = true)
    private String channelName;

    @CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
    private ChannelParamsGroup channelOptions;

    @CommandLine.Option(names = CliConstants.DIR)
    private Optional<Path> directory;

    static class ChannelParamsGroup {
        @CommandLine.Option(names = CliConstants.CHANNEL)
        private Path channelLocation;
        @CommandLine.ArgGroup(exclusive = false, multiplicity = "1")
        private ChannelGroup channelGroup;

    }

    static class ChannelGroup {
        @CommandLine.Option(names = CliConstants.CHANNEL_MANIFEST, required = true)
        private String manifestLocation;

        @CommandLine.Option(names = CliConstants.REPOSITORIES, split = ",", paramLabel = CliConstants.REPO_URL, required = true)
        private List<String> repositoryDefs;
    }

    public ChannelAddCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory);
    }

    @Override
    public Integer call() throws Exception {
        final Path installationDirectory = determineInstallationDirectory(directory);
        final Channel channel;
        if (channelOptions.channelLocation != null) {
            channel = readChannelFromDefinition();
        } else {
            final ChannelManifestCoordinate manifest = ArtifactUtils.manifestCoordFromString(channelOptions.channelGroup.manifestLocation);
            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                final List<Repository> repositories = RepositoryUtils.unzipArchives(RepositoryDefinition.from(channelOptions.channelGroup.repositoryDefs), temporaryFiles);
                channel = new Channel(channelName, null, null, repositories, manifest, null, null);
            }
        }

        console.println(CliMessages.MESSAGES.subscribeChannel(installationDirectory, channelName));
        try (MetadataAction metadataAction = actionFactory.metadataActions(installationDirectory)) {
            metadataAction.addChannel(channel);

        }
        console.println(CliMessages.MESSAGES.channelAdded(channelName));
        return ReturnCodes.SUCCESS;

    }

    private Channel readChannelFromDefinition() throws OperationException, ArgumentParsingException {
        final Path channelFile = channelOptions.channelLocation.toAbsolutePath();
        try {
            final List<Channel> channels = ChannelMapper.fromString(Files.readString(channelFile));
            final Channel channel = channels.get(0);

            if (channels.size() > 1) {
                throw CliMessages.MESSAGES.sizeOfChannel(channelFile);
            }

            return new Channel(channelName, channel.getDescription(), channel.getVendor(), channel.getRepositories(),
                    channel.getManifestCoordinate(), channel.getBlocklistCoordinate(), channel.getNoStreamStrategy());
        } catch (InvalidChannelMetadataException e) {
            // keep the error in sync with other operations (e.g. InstallCommand)
            throw ProsperoLogger.ROOT_LOGGER.invalidChannel(e);
        } catch (IOException e) {
            throw CliMessages.MESSAGES.missingRequiresResource(channelFile.toString(), e);
        }
    }
}
