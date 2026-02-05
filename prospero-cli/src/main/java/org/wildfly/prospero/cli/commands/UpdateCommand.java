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
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.diff.FsDiff;
import org.jboss.galleon.diff.FsEntry;
import org.jboss.galleon.diff.FsEntryFactory;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.logging.Logger;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.DistributionInfo;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.actions.OverrideBuilder;
import org.wildfly.prospero.actions.SubscribeNewServerAction;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.ChannelVersionChange;
import org.wildfly.prospero.api.FileConflict;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.InstallationProfilesManager;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.api.TemporaryRepositoriesHandler;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.ArgumentParsingException;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.FileConflictPrinter;
import org.wildfly.prospero.cli.RepositoryDefinition;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.api.TemporaryFilesManager;
import org.wildfly.prospero.cli.printers.ChannelVersionChangesPrinter;
import org.wildfly.prospero.galleon.FeaturePackLocationParser;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.InstallationProfile;
import org.wildfly.prospero.updates.ChannelsUpdateResult;
import org.wildfly.prospero.updates.UpdateSet;
import picocli.CommandLine;

import org.jboss.galleon.api.config.GalleonProvisioningConfig;

@CommandLine.Command(
        name = CliConstants.Commands.UPDATE,
        sortOptions = false
)
public class UpdateCommand extends AbstractParentCommand {

    private static final Logger log = Logger.getLogger(UpdateCommand.class);

    public static final String JBOSS_MODULE_PATH = "module.path";
    public static final String PROSPERO_FP_GA = "org.wildfly.prospero:prospero-standalone-galleon-pack";
    public static final String PROSPERO_FP_ZIP = PROSPERO_FP_GA + "::zip";

    @CommandLine.Command(name = CliConstants.Commands.PERFORM, sortOptions = false)
    public static class PerformCommand extends UpdateBuildCommand {

        @CommandLine.Option(names = CliConstants.SELF)
        boolean self;

        @CommandLine.Option(names = {CliConstants.NO_CONFLICTS_ONLY})
        boolean noConflictsOnly;

        public PerformCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        @Override
        public Integer call() throws Exception {
            final long startTime = System.currentTimeMillis();
            final Path installationDir;

            if (self) {
                if (directory.isPresent()) {
                    installationDir = directory.get().toAbsolutePath();
                } else {
                    installationDir = detectProsperoInstallationPath().toAbsolutePath();
                }
                verifyInstallationContainsOnlyProspero(installationDir);
            } else {
                installationDir = determineInstallationDirectory(directory);
            }

            final MavenOptions mavenOptions = parseMavenOptions();
            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                final List<Repository> repositories = RepositoryUtils.unzipArchives(
                        RepositoryDefinition.from(temporaryRepositories), temporaryFiles);

                log.tracef("Perform full update");

                console.println(CliMessages.MESSAGES.updateHeader(installationDir));

                final List<Channel> overrideChannels;
                try (InstallationMetadata im = InstallationMetadata.loadInstallation(installationDir)) {
                    overrideChannels = OverrideBuilder
                            .from(im.getProsperoConfig().getChannels())
                            .withRepositories(repositories)
                            .withManifestVersions(versions)
                            .build();
                }

                try (UpdateAction updateAction = actionFactory.update(installationDir, overrideChannels, mavenOptions, console)) {
                    if (!performUpdate(updateAction, console, installationDir, noConflictsOnly)) {
                        return ReturnCodes.PROCESSING_ERROR;
                    }
                }
            }

            final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
            console.println(CliMessages.MESSAGES.operationCompleted(totalTime));

            return ReturnCodes.SUCCESS;
        }

        private boolean performUpdate(UpdateAction updateAction, CliConsole console, Path installDir, boolean noConflictsOnly) throws OperationException, ProvisioningException {
            Path targetDir = null;
            try {
                targetDir = Files.createTempDirectory("update-candidate");
                if (buildUpdate(updateAction, targetDir, console::confirmUpdates)) {
                    console.println("");
                    console.buildUpdatesComplete();

                    ApplyCandidateAction applyCandidateAction = actionFactory.applyUpdate(installDir, targetDir);
                    final List<FileConflict> conflicts = applyCandidateAction.getConflicts();
                    if (!conflicts.isEmpty()) {
                        FileConflictPrinter.print(conflicts, console);

                        if (noConflictsOnly) {
                            throw CliMessages.MESSAGES.cancelledByConfilcts();
                        }

                        if (!yes && !console.confirm(CliMessages.MESSAGES.continueWithUpdate(), "", CliMessages.MESSAGES.updateCancelled())) {
                            return false;
                        }
                    }

                    console.println(CliMessages.MESSAGES.applyingUpdates());
                    applyCandidateAction.applyUpdate(ApplyCandidateAction.Type.UPDATE);
                } else {
                    return false;
                }
            } catch (IOException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToCreateTemporaryDirectory(e);
            } finally {
                if (targetDir != null) {
                    FileUtils.deleteQuietly(targetDir.toFile());
                }
            }

            console.updatesComplete();
            return true;
        }
    }

    @CommandLine.Command(name = CliConstants.Commands.PREPARE, sortOptions = false)
    public static class PrepareCommand extends UpdateBuildCommand {

        @CommandLine.Option(names = CliConstants.CANDIDATE_DIR, required = true)
        Path candidateDirectory;

        public PrepareCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        @Override
        public Integer call() throws Exception {
            final long startTime = System.currentTimeMillis();
            final Path installationDir = determineInstallationDirectory(directory);

            final MavenOptions mavenOptions = parseMavenOptions();
            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                final List<Repository> repositories = RepositoryUtils.unzipArchives(
                        RepositoryDefinition.from(temporaryRepositories), temporaryFiles);

                log.tracef("Generate update in %s", candidateDirectory);

                console.println(CliMessages.MESSAGES.buildUpdateCandidateHeader(installationDir));


                verifyTargetDirectoryIsEmpty(candidateDirectory);

                final List<Channel> overrideChannels;
                try (InstallationMetadata im = InstallationMetadata.loadInstallation(installationDir)) {
                    overrideChannels = OverrideBuilder
                            .from(im.getProsperoConfig().getChannels())
                            .withRepositories(repositories)
                            .withManifestVersions(versions)
                            .build();
                }

                try (UpdateAction updateAction = actionFactory.update(installationDir, overrideChannels,
                        mavenOptions, console)) {
                    if (buildUpdate(updateAction, candidateDirectory, console::confirmBuildUpdates)) {
                        console.println("");
                        console.buildUpdatesComplete();
                        console.println(CliMessages.MESSAGES.updateCandidateGenerated(candidateDirectory));
                    }
                }
            }

            final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
            console.println(CliMessages.MESSAGES.operationCompleted(totalTime));

            return ReturnCodes.SUCCESS;
        }
    }

    @CommandLine.Command(name = CliConstants.Commands.APPLY, sortOptions = false)
    public static class ApplyCommand extends AbstractCommand {

        @CommandLine.Option(names = CliConstants.DIR)
        Optional<Path> directory;

        @CommandLine.Option(names = CliConstants.CANDIDATE_DIR, required = true)
        Path candidateDir;

        @CommandLine.Option(names = CliConstants.REMOVE)
        boolean remove;

        @CommandLine.Option(names = {CliConstants.Y, CliConstants.YES})
        boolean yes;

        @CommandLine.Option(names = {CliConstants.NO_CONFLICTS_ONLY})
        boolean noConflictsOnly;

        @CommandLine.Option(names = {CliConstants.DRY_RUN})
        boolean dryRun;

        public ApplyCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        @Override
        public Integer call() throws Exception {
            final long startTime = System.currentTimeMillis();

            final Path installationDir = determineInstallationDirectory(directory);

            if(!verifyDirectoryContainsInstallation(candidateDir)){
                throw CliMessages.MESSAGES.invalidInstallationDir(candidateDir);
            }

            console.println(CliMessages.MESSAGES.updateHeader(installationDir));

            final ApplyCandidateAction applyCandidateAction = actionFactory.applyUpdate(installationDir.toAbsolutePath(), candidateDir.toAbsolutePath());

            final ApplyCandidateAction.ValidationResult result = applyCandidateAction.verifyCandidate(ApplyCandidateAction.Type.UPDATE);
            if (ApplyCandidateAction.ValidationResult.STALE == result) {
                throw CliMessages.MESSAGES.updateCandidateStateNotMatched(installationDir, candidateDir.toAbsolutePath());
            } else if (ApplyCandidateAction.ValidationResult.WRONG_TYPE == result) {
                throw CliMessages.MESSAGES.updateCandidateWrongType(installationDir, ApplyCandidateAction.Type.UPDATE);
            } else if (ApplyCandidateAction.ValidationResult.NOT_CANDIDATE == result) {
                throw CliMessages.MESSAGES.notCandidate(candidateDir.toAbsolutePath());
            }

            UpdateSet updates = applyCandidateAction.findUpdates();

            console.updatesFound(updates.getArtifactUpdates(), updates.getChannelVersionChanges());
            final List<FileConflict> conflicts = applyCandidateAction.getConflicts();
            FileConflictPrinter.print(conflicts, console);

            if (dryRun) {
                return ReturnCodes.SUCCESS;
            }

            if (noConflictsOnly && !conflicts.isEmpty()) {
                throw CliMessages.MESSAGES.cancelledByConfilcts();
            }

            // there always should be updates, so confirm update
            if (!yes && !console.confirm(CliMessages.MESSAGES.continueWithUpdate(), CliMessages.MESSAGES.applyingUpdates(), CliMessages.MESSAGES.updateCancelled())) {
                return ReturnCodes.SUCCESS;
            }

            applyCandidateAction.applyUpdate(ApplyCandidateAction.Type.UPDATE);
            console.updatesComplete();

            if(remove) {
                applyCandidateAction.removeCandidate(candidateDir.toFile());
            }
            final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
            console.println(CliMessages.MESSAGES.operationCompleted(totalTime));


            return ReturnCodes.SUCCESS;
        }
    }

    @CommandLine.Command(name = CliConstants.Commands.LIST, sortOptions = false)
    public static class ListCommand extends AbstractMavenCommand {

        @CommandLine.Option(
                names = CliConstants.MANIFEST_VERSIONS,
                split = ","
        )
        protected List<String> versions = new ArrayList<>();

        public ListCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }
        @Override
        public Integer call() throws Exception {
            final long startTime = System.currentTimeMillis();
            final Path installationDir = determineInstallationDirectory(directory);

            final MavenOptions mavenOptions = parseMavenOptions();

            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                final List<Repository> repositories = RepositoryUtils.unzipArchives(
                        RepositoryDefinition.from(temporaryRepositories), temporaryFiles);
                console.println(CliMessages.MESSAGES.checkUpdatesHeader(installationDir));
                final List<Channel> overrideChannels;
                try (InstallationMetadata im = InstallationMetadata.loadInstallation(installationDir)) {
                    overrideChannels = OverrideBuilder
                            .from(im.getProsperoConfig().getChannels())
                            .withRepositories(repositories)
                            .withManifestVersions(versions)
                            .build();
                }
                try (UpdateAction updateAction = actionFactory.update(installationDir, overrideChannels, mavenOptions, console)) {
                    final UpdateSet updateSet = updateAction.findUpdates();
                    console.updatesFound(updateSet.getArtifactUpdates(), updateSet.getChannelVersionChanges());
                }

                final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
                console.println("");
                console.println(CliMessages.MESSAGES.operationCompleted(totalTime));
                return ReturnCodes.SUCCESS;
            }
        }
    }

    @CommandLine.Command(name = CliConstants.Commands.LIST_CHANNELS, sortOptions = false)
    public static class ListChannelsCommand extends AbstractMavenCommand {

        @CommandLine.Option(names = CliConstants.ALL)
        boolean all;

        public ListChannelsCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }
        @Override
        public Integer call() throws Exception {
            final long startTime = System.currentTimeMillis();
            final Path installationDir = determineInstallationDirectory(directory);

            final MavenOptions mavenOptions = parseMavenOptions();

            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                final List<Repository> repositories = RepositoryUtils.unzipArchives(
                        RepositoryDefinition.from(temporaryRepositories), temporaryFiles);
                console.println(CliMessages.MESSAGES.checkUpdatesHeader(installationDir));
                final List<Channel> overrideChannels;
                try (InstallationMetadata im = InstallationMetadata.loadInstallation(installationDir)) {
                    overrideChannels = OverrideBuilder
                            .from(im.getProsperoConfig().getChannels())
                            .withRepositories(repositories)
                            .build();
                }

                try (UpdateAction updateAction = actionFactory.update(installationDir, overrideChannels, mavenOptions, console)) {
                    final ChannelsUpdateResult result = updateAction.findChannelUpdates(all);
                    new ChannelVersionChangesPrinter(console).printAvailableChannelChanges(result, installationDir.toString());
                }

                final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
                console.println("");
                console.println(CliMessages.MESSAGES.operationCompleted(totalTime));
                return ReturnCodes.SUCCESS;
            }
        }
    }

    @CommandLine.Command(name = CliConstants.Commands.SUBSCRIBE, sortOptions = false)
    public static class SubscribeCommand extends AbstractMavenCommand {

        @CommandLine.Option(names = CliConstants.PRODUCT)
        String product;

        @CommandLine.Option(names = CliConstants.VERSION)
        String version;

        @CommandLine.Option(names = {CliConstants.Y, CliConstants.YES})
        boolean yes;

        public SubscribeCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        @Override
        public Integer call() throws Exception {
            final Path installDir = directory.orElse(currentDir()).toAbsolutePath();
            if (Files.exists(ProsperoMetadataUtils.manifestPath(installDir))
              || Files.exists(ProsperoMetadataUtils.configurationPath(installDir))) {
                console.println(CliMessages.MESSAGES.metadataExistsAlready(installDir, DistributionInfo.DIST_NAME));
                return ReturnCodes.INVALID_ARGUMENTS;
            }
            if (product == null || version == null) {
                console.println(CliMessages.MESSAGES.productAndVersionNotNull());
                return ReturnCodes.INVALID_ARGUMENTS;
            }
            InstallationProfile installationProfile = InstallationProfilesManager.getByName(product);
            if (installationProfile == null) {
                console.println(CliMessages.MESSAGES.unknownProduct(product));
                return ReturnCodes.INVALID_ARGUMENTS;
            }
            List<Channel> channels = installationProfile.getChannels();
            FeaturePackLocation loc = getFpl(installationProfile, version);
            log.debugf("Will generate FeaturePackLocation %s.", loc.toString());

            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                final List<Repository> repositories = RepositoryUtils.unzipArchives(
                        RepositoryDefinition.from(temporaryRepositories), temporaryFiles);
                final List<Channel> tempChannels = TemporaryRepositoriesHandler.overrideRepositories(channels, repositories);

                SubscribeNewServerAction subscribeNewServerAction = actionFactory.subscribeNewServerAction(parseMavenOptions(), console);
                SubscribeNewServerAction.GenerateResult generateResult = subscribeNewServerAction.generateServerMetadata(tempChannels, loc);
                generateMeta(installDir, generateResult);
            }

            return ReturnCodes.SUCCESS;
        }

        private void generateMeta(Path installDir, SubscribeNewServerAction.GenerateResult generateResult) throws IOException, ProvisioningException {
            // compare hashes
            FsEntryFactory fsEntryFactory = FsEntryFactory.getInstance()
              .filterGalleonPaths()
              .filter(ProsperoMetadataUtils.METADATA_DIR);
            final FsEntry originalState = fsEntryFactory.forPath(generateResult.getProvisionDir());
            final FsEntry currentState = fsEntryFactory.forPath(installDir);
            final FsDiff diff = FsDiff.diff(originalState, currentState);

            if (!yes && hasChangedEntries(diff)
                    && !console.confirm(CliMessages.MESSAGES.conflictsWhenGenerating(diff.toString()),
                    CliMessages.MESSAGES.continueGenerating(), CliMessages.MESSAGES.quitGenerating())) {
                return;
            }

            Path galleonDir = installDir.resolve(InstallationMetadata.GALLEON_INSTALLATION_DIR);
            if (Files.notExists(galleonDir)) {
                Files.createDirectories(galleonDir);
            }
            Path provisionDir = generateResult.getProvisionDir();
            Path sourceGalleonDir = provisionDir.resolve(InstallationMetadata.GALLEON_INSTALLATION_DIR);
            if (!Files.exists(galleonDir.getParent())) {
                Files.createDirectories(galleonDir.getParent());
            }
            // copy .galleon dir
            FileUtils.copyDirectory(sourceGalleonDir.toFile(), galleonDir.toFile());
            ChannelManifest manifest = generateResult.getManifest();
            Path manifestPath = ProsperoMetadataUtils.manifestPath(installDir);
            if (!Files.exists(manifestPath.getParent())) {
                Files.createDirectories(manifestPath.getParent());
            }
            console.println(CliMessages.MESSAGES.writeManifest(manifestPath));
            ProsperoMetadataUtils.writeManifest(manifestPath, manifest);

            List<Channel> channels = generateResult.getChannels();
            if (!generateResult.isManifestCoordDefined()) {
                // if manifest is not defined, make a copy of manifest
                Path manifestPathCopy = manifestPath.getParent().resolve("manifest-" + product + "-" + version + ".yaml");
                Files.copy(manifestPath, manifestPathCopy, StandardCopyOption.REPLACE_EXISTING);
                channels = channels.stream().map(c -> {
                    try {
                        return new Channel(c.getName(), c.getDescription(), c.getVendor(), c.getRepositories(),
                          new ChannelManifestCoordinate(manifestPathCopy.toUri().toURL()), c.getBlocklistCoordinate(), c.getNoStreamStrategy());
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toList());
            }
            Path channelsPath = ProsperoMetadataUtils.configurationPath(installDir);
            console.println(CliMessages.MESSAGES.writeChannelsConfiguration(channelsPath));
            ProsperoMetadataUtils.writeChannelsConfiguration(channelsPath, channels);
        }

        private static boolean hasChangedEntries(FsDiff diff) {
            return diff.hasAddedEntries() || diff.hasModifiedEntries() || diff.hasRemovedEntries();
        }

        private FeaturePackLocation getFpl(InstallationProfile knownFeaturePack, String version) throws MetadataException, ProvisioningException {
            GalleonProvisioningConfig config = GalleonUtils.readProvisioningConfig(knownFeaturePack.getGalleonConfiguration());
            if (config.getFeaturePackDeps().isEmpty()) {
                throw new ProvisioningException("At least one feature pack location must be specified in the provisioning configuration");
            }
            FeaturePackLocation fpl = config.getFeaturePackDeps().iterator().next().getLocation();
            String[] split = fpl.toString().split(":");
            return FeaturePackLocationParser.resolveFpl(split[0] + ":" + split[1] + ":" + version);
        }

    }

    public abstract static class UpdateBuildCommand extends AbstractMavenCommand {

        @CommandLine.Option(names = {CliConstants.Y, CliConstants.YES})
        boolean yes;

        @CommandLine.Spec
        protected CommandLine.Model.CommandSpec spec;

        @CommandLine.Option(
                names = CliConstants.MANIFEST_VERSIONS,
                split = ","
        )
        protected List<String> versions = new ArrayList<>();

        public UpdateBuildCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        protected boolean buildUpdate(UpdateAction updateAction, Path updateDirectory,
                                           Supplier<Boolean> confirmation) throws OperationException, ProvisioningException {
            // Log version overrides being applied
            if (!versions.isEmpty()) {
                log.infof("Applying version overrides: %s", versions);
                console.println(CliMessages.MESSAGES.applyingVersionOverrides(versions.size()));
            }

            final UpdateSet updateSet = updateAction.findUpdates();

            final List<ChannelVersionChange> channelChanges = updateSet.getChannelVersionChanges();
            log.debugf("Found %d channel version changes", channelChanges.size());

            final List<ChannelVersionChange> downgrades = channelChanges.stream().filter(ChannelVersionChange::isDowngrade).toList();
            if (!downgrades.isEmpty()) {
                log.warnf("Detected %d potential downgrade(s)", downgrades.size());
                for (ChannelVersionChange downgrade : downgrades) {
                    log.warnf("Downgrade detected: %s: %s -> %s",
                        downgrade.channelName(),
                        downgrade.oldVersion().getPhysicalVersion(),
                        downgrade.newVersion().getPhysicalVersion());
                }

                // check that each flagged downgrade is listed specifically in the versions
                final ChannelVersionChangesPrinter printer = new ChannelVersionChangesPrinter(console);
                printer.printDowngrades(downgrades);

                if (hasUnexpectedDowngrade(downgrades)) {
                    log.errorf("Found unexpected downgrade(s) - user must explicitly specify version overrides");
                    printer.printUnexpectedDowngradesError(downgrades, spec);
                    return false;
                } else {
                    log.infof("All downgrades are explicitly requested via version overrides");
                }

                if (!yes && !console.confirm(CliMessages.MESSAGES.continueWithUpdate(), "", CliMessages.MESSAGES.updateCancelled())) {
                    log.infof("Update cancelled by user due to downgrades");
                    return false;
                }
                log.infof("User confirmed downgrade operation");
            }

            console.updatesFound(updateSet.getArtifactUpdates(), updateSet.getChannelVersionChanges());
            if (updateSet.isEmpty()) {
                return false;
            }

            if (!yes && !confirmation.get()) {
                return false;
            }

            updateAction.buildUpdate(updateDirectory.toAbsolutePath());

            return true;
        }

        private boolean hasUnexpectedDowngrade(List<ChannelVersionChange> downgrades) {
            boolean unexpectedDowngrade = false;
            Map<String, String> allowedVersions = new HashMap<>();
            versions.stream().map(v -> v.split("::")).forEach(p -> allowedVersions.put(p[0], p[1]));
            for (ChannelVersionChange cvc : downgrades) {
                if (!allowedVersions.containsKey(cvc.channelName())) {
                    unexpectedDowngrade = true;
                    break;
                }
                final String allowedVersion = allowedVersions.get(cvc.channelName());
                if (!allowedVersion.equals(cvc.newVersion().getPhysicalVersion())) {
                    unexpectedDowngrade = true;
                    break;
                }
            }
            return unexpectedDowngrade;
        }
    }

    public UpdateCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory, CliConstants.Commands.UPDATE,
                List.of(
                    new UpdateCommand.PrepareCommand(console, actionFactory),
                    new UpdateCommand.ApplyCommand(console, actionFactory),
                    new UpdateCommand.PerformCommand(console, actionFactory),
                    new UpdateCommand.ListCommand(console, actionFactory),
                    new ListChannelsCommand(console, actionFactory),
                    new SubscribeCommand(console, actionFactory))
        );
    }

    public static void verifyInstallationContainsOnlyProspero(Path dir) throws ArgumentParsingException {
        verifyDirectoryContainsInstallation(dir);

        try {
            final List<String> fpNames = GalleonUtils.getInstalledPacks(dir.toAbsolutePath());
            if (fpNames.size() != 1) {
                throw CliMessages.MESSAGES.unexpectedPackageInSelfUpdate(dir.toString());
            }
            if (!fpNames.stream().allMatch(PROSPERO_FP_ZIP::equals)) {
                throw CliMessages.MESSAGES.unexpectedPackageInSelfUpdate(dir.toString());
            }
        } catch (ProvisioningException e) {
            throw CliMessages.MESSAGES.unableToParseSelfUpdateData(e);
        }
    }

    public static Path detectProsperoInstallationPath() throws ArgumentParsingException {
        final String modulePath = System.getProperty(JBOSS_MODULE_PATH);
        if (modulePath == null) {
            throw CliMessages.MESSAGES.unableToLocateProsperoInstallation();
        }
        return Paths.get(modulePath).toAbsolutePath().getParent();
    }

}
