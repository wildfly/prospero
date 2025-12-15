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
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;

import org.jboss.galleon.universe.FeaturePackLocation;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.channel.maven.ChannelCoordinate;
import org.wildfly.prospero.actions.OverrideBuilder;
import org.wildfly.prospero.actions.ProvisioningAction;
import org.wildfly.prospero.api.ArtifactUtils;
import org.wildfly.prospero.api.InstallationProfilesManager;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.api.TemporaryRepositoriesHandler;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.LicensePrinter;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.cli.ChannelUtils;
import org.wildfly.prospero.cli.RepositoryDefinition;
import org.wildfly.prospero.cli.ArgumentParsingException;
import org.wildfly.prospero.api.TemporaryFilesManager;
import org.wildfly.prospero.cli.commands.options.InstallationProfilesCandidates;
import org.wildfly.prospero.cli.printers.ChannelPrinter;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.licenses.License;
import org.wildfly.prospero.model.InstallationProfile;
import picocli.CommandLine;


@CommandLine.Command(
        name = CliConstants.Commands.INSTALL,
        sortOptions = false
)
public class InstallCommand extends AbstractInstallCommand {

    protected static final int PROFILES_INDENT = 4;
    protected static final String PROFILE_SUBHEADERS_INDENT = "  ";
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(
            names = CliConstants.DIR,
            required = false,
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

    protected static final List<String> STABILITY_LEVELS = List.of(Constants.STABILITY_EXPERIMENTAL,
            Constants.STABILITY_PREVIEW,
            Constants.STABILITY_DEFAULT,
            Constants.STABILITY_COMMUNITY);

    @CommandLine.ArgGroup(
            headingKey = "stability_level_header",
            exclusive = false,
            order = 9
    )
    StabilityLevels stabilityLevels = new StabilityLevels();

    @CommandLine.Option(
            names = CliConstants.VERSION,
            split = ","
    )
    List<String> versions = new ArrayList<>();

    static class FeaturePackOrDefinition {
        @CommandLine.Option(
                names = CliConstants.PROFILE,
                paramLabel = CliConstants.PROFILE_REFERENCE,
                completionCandidates = InstallationProfilesCandidates.class,
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

        @CommandLine.Option(
                names = CliConstants.LIST_PROFILES,
                order = 4
        )
        boolean listProfiles;

    }

    public InstallCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory);
    }

    @Override
    public Integer call() throws Exception {
        final long startTime = System.currentTimeMillis();

        if (featurePackOrDefinition.listProfiles) {
            return displayListOfProfiles();
        }
        if (directory == null) {
            throw CliMessages.MESSAGES.missingRequiredParameter(spec.commandLine(), CliConstants.DIR);
        }

        // following is checked by picocli, adding this to avoid IDE warnings
        assert featurePackOrDefinition.definition.isPresent() || featurePackOrDefinition.fpl.isPresent() || featurePackOrDefinition.profile.isPresent();

        if (featurePackOrDefinition.profile.isEmpty() && channelCoordinates.isEmpty() && manifestCoordinates.isEmpty()) {
            throw CliMessages.MESSAGES.channelsMandatoryWhenCustomFpl(String.join(",", InstallationProfilesManager.getNames()));
        }

        if (featurePackOrDefinition.profile.isPresent() && !isStandardFpl(featurePackOrDefinition.profile.get())) {
            throw CliMessages.MESSAGES.unknownInstallationProfile(featurePackOrDefinition.profile.get(), String.join(",", InstallationProfilesManager.getNames()));
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

        stabilityLevels.verify();

        if (featurePackOrDefinition.definition.isPresent()) {
            final Path definition = featurePackOrDefinition.definition.get().toAbsolutePath();
            checkFileExists(definition.toUri().toURL(), definition.toString());
        }

        verifyTargetDirectoryIsEmpty(directory);
        try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
            final ProvisioningDefinition provisioningDefinition = buildDefinition(temporaryFiles)
                    .setStabilityLevel(stabilityLevels.stabilityLevel == null ? null : stabilityLevels.stabilityLevel.toLowerCase(Locale.ROOT))
                    .setPackageStabilityLevel(stabilityLevels.packageStabilityLevel == null ? null : stabilityLevels.packageStabilityLevel.toLowerCase(Locale.ROOT))
                    .setConfigStabilityLevel(stabilityLevels.configStabilityLevel == null ? null : stabilityLevels.configStabilityLevel.toLowerCase(Locale.ROOT))
                    .build();
            final MavenOptions mavenOptions = getMavenOptions();
            final GalleonProvisioningConfig provisioningConfig = provisioningDefinition.toProvisioningConfig();
            final List<Channel> channels = ChannelUtils.resolveChannels(provisioningDefinition, mavenOptions);
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

            final List<Channel> overrideChannels = buildOverrideChannels(shadowRepositories, channels);

            provisioningAction.provisionWithChannels(provisioningConfig, channels, overrideChannels);

            console.println("");
            console.println(CliMessages.MESSAGES.installComplete(directory));

            final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
            console.println(CliMessages.MESSAGES.operationCompleted(totalTime));

            return ReturnCodes.SUCCESS;
        }
    }

    private List<Channel> buildOverrideChannels(List<Repository> shadowRepositories, List<Channel> channels) throws ArgumentParsingException {
        return OverrideBuilder
                .from(channels)
                .withRepositories(shadowRepositories)
                .withManifestVersions(versions)
                .build();
    }

    private void checkFileExists(URL resourceUrl, String argValue) throws ArgumentParsingException {
        if (resourceUrl != null) {
            try {
                // open stream to check if the resource exists
                resourceUrl.openStream().close();
            } catch (IOException e) {
                throw CliMessages.MESSAGES.missingRequiresResource(argValue, e);
            }
        }
    }

    private boolean isStandardFpl(String fpl) {
        return InstallationProfilesManager.isWellKnownName(fpl);
    }

    private static class StabilityCandidates implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return STABILITY_LEVELS.stream().map(String::toLowerCase).iterator();
        }
    }

    static class StabilityLevels {
        @CommandLine.Option(
                names = CliConstants.STABILITY_LEVEL,
                completionCandidates = StabilityCandidates.class,
                paramLabel = "stability-level"
        )
        String stabilityLevel;

        @CommandLine.Option(
                names = CliConstants.CONFIG_STABILITY_LEVEL,
                completionCandidates = StabilityCandidates.class,
                paramLabel = "stability-level"
        )
        String configStabilityLevel;

        @CommandLine.Option(
                names = CliConstants.PACKAGE_STABILITY_LEVEL,
                completionCandidates = StabilityCandidates.class,
                paramLabel = "stability-level"
        )
        String packageStabilityLevel;

        public void verify() {
            if (StringUtils.isNotEmpty(stabilityLevel) && StringUtils.isNotEmpty(configStabilityLevel)) {
                throw CliMessages.MESSAGES.exclusiveOptions(CliConstants.STABILITY_LEVEL, CliConstants.CONFIG_STABILITY_LEVEL);
            }
            if (StringUtils.isNotEmpty(stabilityLevel) && StringUtils.isNotEmpty(packageStabilityLevel)) {
                throw CliMessages.MESSAGES.exclusiveOptions(CliConstants.STABILITY_LEVEL, CliConstants.PACKAGE_STABILITY_LEVEL);
            }
            if (stabilityLevel != null && !STABILITY_LEVELS.contains(stabilityLevel.toLowerCase(Locale.ROOT))) {
                throw CliMessages.MESSAGES.unknownStabilityLevel(stabilityLevel, STABILITY_LEVELS);
            }
            if (configStabilityLevel != null && !STABILITY_LEVELS.contains(configStabilityLevel.toLowerCase(Locale.ROOT))) {
                throw CliMessages.MESSAGES.unknownStabilityLevel(configStabilityLevel, STABILITY_LEVELS);
            }
            if (packageStabilityLevel != null && !STABILITY_LEVELS.contains(packageStabilityLevel.toLowerCase(Locale.ROOT))) {
                throw CliMessages.MESSAGES.unknownStabilityLevel(packageStabilityLevel, STABILITY_LEVELS);
            }
        }
    }

    private int displayListOfProfiles() throws ProvisioningException, MetadataException {
        final Set<String> profiles = InstallationProfilesManager.getNames();
        if (profiles.isEmpty()) {
            console.println(CliMessages.MESSAGES.noAvailableProfiles() + "\n");
        } else {
            console.println(CliMessages.MESSAGES.availableProfiles() + "\n");
        }

        final ChannelPrinter channelPrinter = new ChannelPrinter(console, PROFILES_INDENT);
        for (String profileName : profiles){
            console.println("----------");
            console.println(CliMessages.MESSAGES.getProfile() + profileName);

            final InstallationProfile profile = InstallationProfilesManager.getByName(profileName);

            console.println(PROFILE_SUBHEADERS_INDENT + CliMessages.MESSAGES.subscribedChannels());
            for(Channel channel: profile.getChannels()){
                channelPrinter.print(channel);
            }

            console.println(PROFILE_SUBHEADERS_INDENT + CliMessages.MESSAGES.includedFeaturePacks());
            for (FeaturePackLocation featurePackLocation: getFeaturePacks(profile)) {
                console.println(" ".repeat(PROFILES_INDENT) + featurePackLocation.toString());
            }
        }

        return ReturnCodes.SUCCESS;
    }

    private List<FeaturePackLocation> getFeaturePacks(InstallationProfile profile) throws MetadataException, ProvisioningException {
        final GalleonProvisioningConfig config = GalleonUtils.readProvisioningConfig(profile.getGalleonConfiguration());
        if (config.getFeaturePackDeps().isEmpty()) {
            throw new ProvisioningException("At least one feature pack location must be specified in the provisioning configuration");
        }

        return config.getFeaturePackDeps().stream()
                .map(GalleonFeaturePackConfig::getLocation)
                .collect(Collectors.toList());
    }

}
