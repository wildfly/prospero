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

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.galleon.config.ProvisioningConfig;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.channel.maven.ChannelCoordinate;
import org.wildfly.prospero.actions.ProvisioningAction;
import org.wildfly.prospero.api.ArtifactUtils;
import org.wildfly.prospero.api.KnownFeaturePacks;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.api.TemporaryRepositoriesHandler;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.ArgumentParsingException;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.LicensePrinter;
import org.wildfly.prospero.cli.RepositoryDefinition;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.api.TemporaryFilesManager;
import org.wildfly.prospero.cli.commands.options.FeaturePackCandidates;
import org.wildfly.prospero.cli.printers.ChannelPrinter;
import org.wildfly.prospero.licenses.License;
import picocli.CommandLine;

@CommandLine.Command(
        name = CliConstants.Commands.INSTALL,
        sortOptions = false
)
public class InstallCommand extends AbstractInstallCommand {

    @CommandLine.Option(
            names = CliConstants.DIR,
            required = true,
            order = 2
    )
    Path directory;

    @CommandLine.Option(
            names = CliConstants.ACCEPT_AGREEMENTS,
            order = 8
    )
    boolean acceptAgreements;

    @CommandLine.Option(
            names = CliConstants.SHADE_REPOSITORIES,
            split = ",",
            hidden = true
    )
    List<String> shadowRepositories = new ArrayList<>();


    static class FeaturePackOrDefinition {
        @CommandLine.Option(
                names = CliConstants.PROFILE,
                paramLabel = CliConstants.PROFILE_REFERENCE,
                completionCandidates = FeaturePackCandidates.class,
                required = true,
                order = 1
        )
        Optional<String> profile;

        @CommandLine.Option(
                names = CliConstants.FPL,
                paramLabel = CliConstants.FEATURE_PACK_REFERENCE,
                required = true,
                order = 2
        )
        Optional<String> fpl;

        @CommandLine.Option(
                names = CliConstants.DEFINITION,
                paramLabel = CliConstants.PATH,
                required = true,
                order = 3
        )
        Optional<Path> definition;
    }

    public InstallCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory);
    }

    @Override
    public Integer call() throws Exception {
        final long startTime = System.currentTimeMillis();

        // following is checked by picocli, adding this to avoid IDE warnings
        assert featurePackOrDefinition.definition.isPresent() || featurePackOrDefinition.fpl.isPresent() || featurePackOrDefinition.profile.isPresent();

        if (featurePackOrDefinition.profile.isEmpty() && channelCoordinates.isEmpty() && manifestCoordinates.isEmpty()) {
            throw CliMessages.MESSAGES.channelsMandatoryWhenCustomFpl(String.join(",", KnownFeaturePacks.getNames()));
        }

        if (featurePackOrDefinition.profile.isPresent() && !isStandardFpl(featurePackOrDefinition.profile.get())) {
            throw CliMessages.MESSAGES.unknownInstallationProfile(featurePackOrDefinition.profile.get(), String.join(",", KnownFeaturePacks.getNames()));
        }

        if (!channelCoordinates.isEmpty() && !manifestCoordinates.isEmpty()) {
            throw CliMessages.MESSAGES.exclusiveOptions(CliConstants.CHANNELS, CliConstants.CHANNEL_MANIFEST);
        }

        if (!manifestCoordinates.isEmpty()) {
            for (String coordinate : manifestCoordinates) {
                final ChannelManifestCoordinate manifest = ArtifactUtils.manifestCoordFromString(coordinate);
                checkFileExists(manifest.getUrl(), coordinate);
            }
        }

        if (!channelCoordinates.isEmpty()) {
            for (String coordStr : channelCoordinates) {
                final ChannelCoordinate coord = ArtifactUtils.channelCoordFromString(coordStr);
                checkFileExists(coord.getUrl(), coordStr);
            }
        }

        if (featurePackOrDefinition.definition.isPresent()) {
            final Path definition = featurePackOrDefinition.definition.get().toAbsolutePath();
            checkFileExists(definition.toUri().toURL(), definition.toString());
        }

        verifyTargetDirectoryIsEmpty(directory);

        try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
            final ProvisioningDefinition provisioningDefinition = buildDefinition(temporaryFiles);
            final MavenOptions mavenOptions = getMavenOptions();
            final ProvisioningConfig provisioningConfig = provisioningDefinition.toProvisioningConfig();
            final List<Channel> channels = resolveChannels(provisioningDefinition, mavenOptions);
            List<Repository> repositories = RepositoryDefinition.from(this.shadowRepositories);
            final List<Repository> shadowRepositories = RepositoryUtils.unzipArchives(repositories, temporaryFiles);

            final ProvisioningAction provisioningAction = actionFactory.install(directory.toAbsolutePath(), mavenOptions,
                    console);

            if (featurePackOrDefinition.fpl.isPresent()) {
                console.println(CliMessages.MESSAGES.installingFpl(featurePackOrDefinition.fpl.get()));
            } else if (featurePackOrDefinition.profile.isPresent()) {
                console.println(CliMessages.MESSAGES.installingProfile(featurePackOrDefinition.profile.get()));
            } else if (featurePackOrDefinition.definition.isPresent()) {
                console.println(CliMessages.MESSAGES.installingDefinition(featurePackOrDefinition.definition.get()));
            }


            final List<Channel> effectiveChannels = TemporaryRepositoriesHandler.overrideRepositories(channels, shadowRepositories);
            console.println(CliMessages.MESSAGES.usingChannels());
            final ChannelPrinter channelPrinter = new ChannelPrinter(console);
            for (Channel channel : effectiveChannels) {
                channelPrinter.print(channel);
            }

            console.println("");

            final List<License> pendingLicenses = provisioningAction.getPendingLicenses(provisioningConfig,
                    effectiveChannels);
            if (!pendingLicenses.isEmpty()) {
                new LicensePrinter(console).print(pendingLicenses);
                console.println("");
                if (acceptAgreements) {
                    console.println(CliMessages.MESSAGES.agreementSkipped(CliConstants.ACCEPT_AGREEMENTS));
                    console.println("");
                } else {
                    if (!console.confirm(CliMessages.MESSAGES.acceptAgreements(), "", CliMessages.MESSAGES.installationCancelled())) {
                        return ReturnCodes.PROCESSING_ERROR;
                    }
                }
            }

            provisioningAction.provision(provisioningConfig, channels, shadowRepositories);

            console.println("");
            console.println(CliMessages.MESSAGES.installComplete(directory));

            final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
            console.println(CliMessages.MESSAGES.operationCompleted(totalTime));

            return ReturnCodes.SUCCESS;
        }
    }

    private void checkFileExists(URL resourceUrl, String argValue) throws ArgumentParsingException {
        if (resourceUrl != null) {
            try {
                // open stream to check if the resource exists
                resourceUrl.openStream().close();
            } catch (IOException e) {
                throw CliMessages.MESSAGES.missingRequiresResource(argValue);
            }
        }
    }

    private boolean isStandardFpl(String fpl) {
        return KnownFeaturePacks.isWellKnownName(fpl);
    }

}
