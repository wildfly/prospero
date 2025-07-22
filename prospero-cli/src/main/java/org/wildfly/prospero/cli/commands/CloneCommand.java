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

package org.wildfly.prospero.cli.commands;

import org.wildfly.channel.Channel;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.ArgumentParsingException;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.RepositoryDefinition;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.api.TemporaryFilesManager;
import org.wildfly.prospero.cli.commands.options.LocalRepoOptions;
import org.wildfly.prospero.cli.printers.ChannelPrinter;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;

@CommandLine.Command(name = CliConstants.Commands.CLONE)
public class CloneCommand extends AbstractCommand {

    @CommandLine.Spec
    protected CommandLine.Model.CommandSpec spec;

    public CloneCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory);
    }

    public void addSubCommands(CommandLine rootCmd) {
        CommandLine cloneCmd = rootCmd.getSubcommands().get(CliConstants.Commands.CLONE);
        cloneCmd.addSubcommand(new CloneExportCommand(console, actionFactory))
          .addSubcommand(new CloneRecreateCommand(console, actionFactory));
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(console.getErrOut());
        return ReturnCodes.INVALID_ARGUMENTS;
    }

    @CommandLine.Command(name = CliConstants.Commands.EXPORT)
    private static class CloneExportCommand extends AbstractCommand {

        @CommandLine.Option(names = CliConstants.DIR, order = 1)
        Optional<Path> directory;

        @CommandLine.Option(names = CliConstants.ARG_PATH, required = true, paramLabel = CliConstants.PATH, order = 2)
        Path outPath;

        CloneExportCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        @Override
        public Integer call() throws Exception {
            if (Files.exists(outPath)) {
                throw ProsperoLogger.ROOT_LOGGER.outFileExists(outPath);
            }
            verifyTargetDirectoryIsEmpty(outPath);
            final Path installationDir = determineInstallationDirectory(directory);
            console.println(CliMessages.MESSAGES.exportInstallationDetailsHeader(installationDir, outPath));
            actionFactory
              .exportAction(installationDir)
              .export(outPath);

            console.println(CliMessages.MESSAGES.exportInstallationDetailsDone());
            return ReturnCodes.SUCCESS;
        }
    }

    @CommandLine.Command(name = CliConstants.Commands.RECREATE)
    private static class CloneRecreateCommand extends AbstractCommand {

        @CommandLine.Option(names = CliConstants.DIR, order = 1)
        Optional<Path> directory;

        @CommandLine.Option(names = CliConstants.ARG_PATH, required = true, paramLabel = CliConstants.PATH, order = 2)
        Path inPath;

        @CommandLine.Option(
          names = CliConstants.REPOSITORIES, paramLabel = CliConstants.REPO_URL, split = ",", order = 3
        )
        List<String> remoteRepositories = new ArrayList<>();

        @CommandLine.ArgGroup(headingKey = "localRepoOptions.heading", order = 4)
        LocalRepoOptions localRepoOptions = new LocalRepoOptions();

        @CommandLine.Option(names = CliConstants.OFFLINE, order = 5)
        Optional<Boolean> offline = Optional.empty();

        CloneRecreateCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        @Override
        public Integer call() throws Exception {
            final long startTime = System.currentTimeMillis();
            if (Files.notExists(inPath.toAbsolutePath())) {
                throw new ArgumentParsingException(CliMessages.MESSAGES.restoreFileNotExisted(inPath.toAbsolutePath()));
            }

            final MavenOptions.Builder mavenOptions = localRepoOptions.toOptions();
            offline.map(mavenOptions::setOffline);
            Path installationDirectory = directory.orElse(currentDir()).toAbsolutePath();

            console.println(CliMessages.MESSAGES.recreatingServer(installationDirectory, inPath));

            try (InstallationMetadata metadataBundle = InstallationMetadata.fromMetadataBundle(inPath.toAbsolutePath())) {
                console.println(CliMessages.MESSAGES.provisioningConfigHeader());
                for (GalleonFeaturePackConfig featurePackDep : metadataBundle.getGalleonProvisioningConfig().getFeaturePackDeps()) {
                    console.println(" * " + featurePackDep.getLocation().toString());
                }
                console.println(CliMessages.MESSAGES.subscribedChannelsHeader());
                final ChannelPrinter channelPrinter = new ChannelPrinter(console);
                for (Channel channel : metadataBundle.getProsperoConfig().getChannels()) {
                    channelPrinter.print(channel);
                }
            }
            console.println("");

            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                List<Repository> repositories = RepositoryDefinition.from(remoteRepositories);
                actionFactory
                        .restoreAction(installationDirectory, mavenOptions.build(), console)
                        .restore(inPath, RepositoryUtils.unzipArchives(repositories, temporaryFiles));

                console.println("");
                console.println(CliMessages.MESSAGES.installationMetaRestored());
                final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
                console.println(CliMessages.MESSAGES.operationCompleted(totalTime));
                return ReturnCodes.SUCCESS;
            }
        }
    }
}
