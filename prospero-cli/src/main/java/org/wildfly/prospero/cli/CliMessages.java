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

package org.wildfly.prospero.cli;

import org.apache.commons.lang3.StringUtils;
import org.jboss.galleon.Constants;
import org.jboss.logging.annotations.Cause;
import org.wildfly.prospero.DistributionInfo;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.cli.commands.CliConstants;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

import static java.lang.String.format;

public interface CliMessages {

    CliMessages MESSAGES = new CliMessages() {
    };

    ResourceBundle bundle = ResourceBundle.getBundle("UsageMessages", Locale.getDefault());

    //
    // CliConsole strings
    //
    default String resolvingFeaturePack() {
        return bundle.getString("prospero.install.progress.feature-pack.started");
    }

    default String installingPackages() {
        return bundle.getString("prospero.install.progress.packages");
    }

    default String generatingConfiguration() {
        return bundle.getString("prospero.install.progress.config");
    }

    default String installingJBossModules() {
        return bundle.getString("prospero.install.progress.modules");
    }

    default String downloadingArtifacts() {
        return bundle.getString("prospero.install.progress.download");
    }

    default String resolvingVersions() {
        return bundle.getString("prospero.install.progress.versions");
    }

    default String installingJBossExamples() {
        return bundle.getString("prospero.install.progress.examples");
    }

    default String featurePacksResolved() {
        return bundle.getString("prospero.install.progress.feature-pack.done");
    }

    default String packagesInstalled() {
        return bundle.getString("prospero.install.progress.packages.done");
    }

    default String configurationsGenerated() {
        return bundle.getString("prospero.install.progress.config.done");
    }

    default String jbossModulesInstalled() {
        return bundle.getString("prospero.install.progress.modules.done");
    }

    default String jbossExamplesInstalled() {
        return bundle.getString("prospero.install.progress.examples.done");
    }

    default String artifactsDownloaded() {
        return bundle.getString("prospero.install.progress.download.done");
    }

    default String versionsResolved() {
        return bundle.getString("prospero.install.progress.versions.done");
    }

    default String installProgressWait() {
        return bundle.getString("prospero.install.progress.applying_changes");
    }

    default String noUpdatesFound() {
        return bundle.getString("prospero.updates.no_updates");
    }

    default String updatesFound() {
        return bundle.getString("prospero.updates.header");
    }

    default String changesFound() {
        return bundle.getString("prospero.revert.changes.header");
    }

    default String continueWithUpdate() {
        return bundle.getString("prospero.updates.prompt") + " ";
    }

    default String continueWithRevert() {
        return bundle.getString("prospero.revert.prompt") + " ";
    }

    default String continueWithBuildUpdate() {
        return bundle.getString("prospero.updates.build.prompt") + " ";
    }

    default String updateCancelled() {
        return bundle.getString("prospero.updates.cancelled");
    }

    default String revertCancelled() {
        return bundle.getString("prospero.revert.cancelled");
    }

    default String buildUpdateCancelled() {
        return bundle.getString("prospero.updates.build.cancelled");
    }

    default String applyingUpdates() {
        return bundle.getString("prospero.updates.apply.header");
    }

    default String applyingChanges() {
        return bundle.getString("prospero.revert.apply.header");
    }

    default String buildingUpdates() {
        return bundle.getString("prospero.updates.build.header");
    }

    default String chooseYN() {
        return bundle.getString("prospero.general.prompt.reminder") + " ";
    }

    default String updateComplete() {
        return bundle.getString("prospero.updates.complete");
    }

    default String revertComplete(String revision) {
        return format(bundle.getString("prospero.revert.complete"), revision);
    }

    default String buildUpdateComplete() {
        return bundle.getString("prospero.updates.build.complete");
    }

    // this would be used to determine user answer to [y/n] questions
    default String yesShortcut() {
        return bundle.getString("prospero.general.prompt.yes");
    }

    // this would be used to determine user answer to [y/n] questions
    default String noShortcut() {
        return bundle.getString("prospero.general.prompt.no");
    }

    default String noChangesFound() {
        return bundle.getString("prospero.history.no_updates");
    }

    default String errorWhenProcessingCommand() {
        return bundle.getString("prospero.general.processing_error") + " ";
    }

    default String unexpectedExitWhenProcessingCommand(int exitCode) {
        return format(bundle.getString("prospero.general.processing_exit error"), exitCode);
    }

    default String possibleDowngrade() {
        return bundle.getString("prospero.updates.downgrade.warning");
    }

    default String channelAdded(String urlOrGav) {
        return format(bundle.getString("prospero.channels.added"), urlOrGav);
    }

    default String channelRemoved(String urlOrGav) {
        return format(bundle.getString("prospero.channels.removed"), urlOrGav);
    }

    default String channelNotFound() {
        return bundle.getString("prospero.channels.error.notfound");
    }

    /**
     * @see #invalidInstallationDir(Path)
     */
    default String invalidInstallationDir(Path path, String distName) {
        return format(bundle.getString("prospero.update.invalid.path"), path, distName);
    }

    default ArgumentParsingException invalidInstallationDir(Path path) {
        return new ArgumentParsingException(invalidInstallationDir(path, DistributionInfo.DIST_NAME), requiredMetadata());
    }

    default String requiredMetadata() {
        return format(bundle.getString("prospero.update.invalid.path.details"),
                List.of(ProsperoMetadataUtils.METADATA_DIR + File.separator + ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME,
                        ProsperoMetadataUtils.METADATA_DIR + File.separator + ProsperoMetadataUtils.MANIFEST_FILE_NAME,
                        Constants.PROVISIONED_STATE_DIR + File.separator + Constants.PROVISIONING_XML));
    }

    default String forgottenDirArgQuestion() {
        return format(bundle.getString("prospero.general.argument.dir.validation.detail"), CliConstants.DIR);
    }

    default String addChannels(String channel) {
        return format(bundle.getString("prospero.general.argument.channel.validation.nochannel.detail"), channel);
    }

    default String operationCompleted(float time) {
        return format(bundle.getString("prospero.general.operation.completed.time"), time);
    }

    default String customizationRepoExist(String repositoryId) {
        return format(bundle.getString("prospero.channels.custom.validation.exists"), repositoryId);
    }

    default String illegalChannel(String name) {
        return format(bundle.getString("prospero.channels.custom.validation.format"), name);
    }

    default String unableToCreateLocalRepository(Path repositoryPath) {
        return format(bundle.getString("prospero.channels.custom.validation.local_repo_create"), repositoryPath);
    }

    default String wrongChannelCoordinateFormat() {
        return bundle.getString("prospero.channels.promote.validation.format");
    }

    default String noCustomizationConfigFound(String channelParam, String repoParam) {
        return format(bundle.getString("prospero.channels.promote.validation.no_channel_or_repo"), channelParam, repoParam);
    }

    default String continuePromote() {
        return bundle.getString("prospero.channels.promote.prompt") + " ";
    }

    default String continuePromoteAccepted() {
        return bundle.getString("prospero.channels.promote.prompt.confirm");
    }

    default String continuePromoteRejected() {
        return bundle.getString("prospero.channels.promote.prompt.cancelled");
    }

    default String customizationChannelAlreadyExists() {
        return bundle.getString("prospero.channels.custom.validation.channel.exists");
    }

    default String registeringCustomChannel(String name) {
        return format(bundle.getString("prospero.channels.custom.confirmation.channel"), name);
    }

    // start - changes diff

    default String manifest() {
        return bundle.getString("prospero.changes.diff.manifest");
    }

    default String repositories() {
        return bundle.getString("prospero.changes.diff.repositories");
    }

    default String changeUpdated() {
        return bundle.getString("prospero.changes.diff.updated");
    }

    default String changeAdded() {
        return bundle.getString("prospero.changes.diff.added");
    }

    default String changeRemoved() {
        return bundle.getString("prospero.changes.diff.removed");
    }

    default String diffUpdates() {
        return bundle.getString("prospero.changes.diff.updates");
    }

    default String diffConfigChanges() {
        return bundle.getString("prospero.changes.diff.conf_changes");
    }

    default String artifactChangeType() {
        return bundle.getString("prospero.changes.diff.artifact");
    }

    default String channelChangeType() {
        return bundle.getString("prospero.changes.diff.channel");
    }
    // end diff printer

    default String conflictingChangesDetected() {
        return bundle.getString("prospero.changes.conflict.header");
    }

    default String acceptAgreements() {
        return bundle.getString("prospero.install.agreement.prompt");
    }

    default String installationCancelled() {
        return bundle.getString("prospero.install.agreement.prompt.cancelled");
    }

    default String listAgreementsHeader() {
        return bundle.getString("prospero.install.agreement.header");
    }

    default String noAgreementsNeeded() {
        return bundle.getString("prospero.install.agreement.no_agreement");
    }

    default String agreementSkipped(String optionName) {
        return format(bundle.getString("prospero.install.agreement.skipped"), optionName);
    }

    default String errorHeader(String msg) {
        return format(bundle.getString("prospero.general.error.header"), msg);
    }

    default String errorSSL() {
        return format(bundle.getString("prospero.general.error.ssl"));
    }

    default String errorUnknownHost() {
        return format(bundle.getString("prospero.general.error.host"));
    }

    default String unableToResolveChannelMetadata() {
        return bundle.getString("prospero.general.error.resolve.metadata.header");
    }

    default String unableToResolveArtifacts() {
        return bundle.getString("prospero.general.error.resolve.artifacts.header");
    }

    default String streamsNotFound() {
        return bundle.getString("prospero.general.error.resolve.streams.header");
    }

    default String attemptedRepositories() {
        return bundle.getString("prospero.general.error.resolve.artifacts.repositories");
    }

    default String missing() {
        return bundle.getString("prospero.general.error.resolve.missing");
    }

    default String checksumFailed() {
        return bundle.getString("prospero.general.error.resolve.checksum_failed");
    }

    default String offline() {
        return bundle.getString("prospero.general.error.resolve.offline");
    }

    default String restoreFileNotExisted(Path path) {
        return format(bundle.getString("prospero.clone.error.missing_file"), path);
    }

    default String installationMetaRestored() {
        return bundle.getString("prospero.clone.success");
    }

    //
    // Exceptions
    //
    default ArgumentParsingException unexpectedPackageInSelfUpdate(String path) {
        return new ArgumentParsingException(format(bundle.getString("prospero.update.self.validation.feature_pack"), path));
    }

    default ArgumentParsingException unableToLocateProsperoInstallation() {
        return new ArgumentParsingException(bundle.getString("prospero.update.self.validation.dir.not_found"));
    }

    default ArgumentParsingException unableToParseSelfUpdateData(Exception e) {
        return new ArgumentParsingException(bundle.getString("prospero.update.self.validation.unknown.installation"), e);
    }

    default ArgumentParsingException channelsMandatoryWhenCustomFpl(String knownCombintaions) {
        return new ArgumentParsingException(format(bundle.getString("prospero.install.validation.unknown_fpl"), knownCombintaions),
                bundle.getString("prospero.install.validation.unknown_fpl.details"));
    }

    default ArgumentParsingException invalidInstallationDirMaybeUseDirOption(Path path) {
        return new ArgumentParsingException(invalidInstallationDir(path, DistributionInfo.DIST_NAME), forgottenDirArgQuestion(), requiredMetadata());
    }

    default IllegalArgumentException exclusiveOptions(String option1, String option2) {
        return new IllegalArgumentException(
                format(bundle.getString("prospero.general.validation.conflicting_options"), option1, option2));
    }

    default ArgumentParsingException repositoryIsNotDirectory(Path repo) {
        return new ArgumentParsingException(format(bundle.getString("prospero.general.validation.local_repo.not_directory"), repo));
    }

    default ArgumentParsingException invalidRepositoryDefinition(String repoKey) {
        return new ArgumentParsingException(format(bundle.getString("prospero.general.validation.repo_format"), repoKey));
    }

    default ArgumentParsingException invalidFilePath(String invalidPath, @Cause Exception cause) {
        return new ArgumentParsingException(format(bundle.getString("prospero.general.validation.file_path.invalid"), invalidPath), cause);
    }

    default ArgumentParsingException nonExistingFilePath(Path nonExistingPath) {
        return new ArgumentParsingException(format(bundle.getString("prospero.general.validation.file_path.not_exists"), nonExistingPath));
    }

    default ArgumentParsingException unsupportedRemoteScheme(String url) {
        return new ArgumentParsingException(format(bundle.getString("prospero.general.validation.url.unsupported_remote_scheme"), url));
    }

    default ArgumentParsingException unsupportedScheme(String url) {
        return new ArgumentParsingException(format(bundle.getString("prospero.general.validation.url.unsupported_scheme"), url));
    }

    default IllegalArgumentException updateCandidateStateNotMatched(Path targetDir, Path updateDir) {
        return new IllegalArgumentException(format(bundle.getString("prospero.updates.apply.validation.candidate.outdated"), targetDir, updateDir));
    }

    default IllegalArgumentException updateCandidateWrongType(Path updateDir, ApplyCandidateAction.Type operation) {
        return new IllegalArgumentException(format(bundle.getString("prospero.updates.apply.validation.candidate.wrong_type"), updateDir, operation));
    }

    default IllegalArgumentException notCandidate(Path updateDir) {
        return new IllegalArgumentException(format(bundle.getString("prospero.updates.apply.validation.candidate.not_candidate"), updateDir));
    }

    default IllegalArgumentException noChanges(Path targetDir, Path updateDir) {
        return new IllegalArgumentException(format(bundle.getString("prospero.revert.apply.validation.candidate.no_changes"), targetDir, updateDir));
    }

    default IllegalArgumentException nonEmptyTargetFolder(Path installationDir) {
        return new IllegalArgumentException(
                format(bundle.getString("prospero.updates.build.validation.dir.not_empty"), installationDir));
    }

    default ArgumentParsingException unknownInstallationProfile(String profileName, String candidates) {
        return new ArgumentParsingException(format(bundle.getString("prospero.install.validation.unknown_profile"), profileName),
                format(bundle.getString("prospero.install.validation.unknown_profile.details"), candidates));
    }

    default String installingFpl(String fpl) {
        return format(bundle.getString("prospero.install.header.install.fpl"), fpl);
    }

    default String installingProfile(String profile) {
        return format(bundle.getString("prospero.install.header.install.profile"), profile);
    }

    default String installingDefinition(Path definitionPath) {
        return format(bundle.getString("prospero.install.header.install.definition"), definitionPath);
    }

    default String usingChannels() {
        return bundle.getString("prospero.install.header.channels");
    }

    default String installComplete(Path path) {
        return format(bundle.getString("prospero.install.complete"), path.toAbsolutePath());
    }

    default String updateHeader(Path installationDir) {
        return format(bundle.getString("prospero.updates.started.header"), installationDir.toAbsolutePath());
    }

    default String buildUpdateCandidateHeader(Path installationDir) {
        return format(bundle.getString("prospero.updates.build.candidate.header"), installationDir.toAbsolutePath());
    }

    default String updateCandidateGenerated(Path candidateDirectory) {
        return format(bundle.getString("prospero.updates.build.candidate.complete"), candidateDirectory.toAbsolutePath());
    }

    default String checkUpdatesHeader(Path installationDir) {
        return format(bundle.getString("prospero.updates.list.header"), installationDir.toAbsolutePath());
    }

    default String revertStart(Path installationDir, String revision) {
        return format(bundle.getString("prospero.revert.started.header"), installationDir, revision);
    }

    default String comparingChanges() {
        return bundle.getString("prospero.revert.comparing.changes");
    }

    default String buildRevertCandidateHeader(Path installationDir) {
        return format("Building revert candidate for %s%n", installationDir.toAbsolutePath());
    }

    default String revertCandidateGenerated(Path candidateDir) {
        return format("Update candidate generated in %s", candidateDir.toAbsolutePath());
    }

    default String unsubscribeChannel(Path installationDir, String channelName) {
        return format(bundle.getString("prospero.channels.remove.header"), installationDir.toAbsolutePath(), channelName);
    }

    default String subscribeChannel(Path installationDir, String channelName) {
        return format(bundle.getString("prospero.channels.add.header"), installationDir.toAbsolutePath(), channelName);
    }

    default String listChannels(Path installationDir) {
        return format(bundle.getString("prospero.channels.list.header"), installationDir.toAbsolutePath());
    }

    default String recreatingServer(Path installationDir, Path exportedZip) {
        return format(bundle.getString("prospero.clone.start.header"), installationDir.toAbsolutePath(), exportedZip.toAbsolutePath());
    }

    default String provisioningConfigHeader() {
        return bundle.getString("prospero.clone.config.provisioning");
    }

    default String subscribedChannelsHeader() {
        return bundle.getString("prospero.clone.config.channels");
    }

    default String exportInstallationDetailsHeader(Path installationDir, Path outPath) {
        return format(bundle.getString("prospero.export.start.header"), installationDir.toAbsolutePath(), outPath.toAbsolutePath());
    }

    default String exportInstallationDetailsDone() {
        return bundle.getString("prospero.export.done");
    }

    default ArgumentParsingException missingRequiresResource(String resource, Exception cause) {
        return new ArgumentParsingException(format(bundle.getString("prospero.general.error.missing_file"), resource), cause);
    }

    default String parsingError(String path) {
        return format(bundle.getString("prospero.general.error.galleon.parse"), path);
    }

    default String featurePackNotFound(String featurePackName) {
        return format(bundle.getString("prospero.general.error.feature_pack.not_found"), featurePackName);
    }

    default String unknownCommand(String commandString) {
        return format(bundle.getString("prospero.general.error.unknown_command"), commandString);
    }

    default String commandSuggestions(List<String> suggestions) {
        if (suggestions.size() == 1) {
            return format(bundle.getString("prospero.general.error.unknown_command.suggestion_single"),
                    suggestions.iterator().next());
        } else {
            String connector = format(" %s ", bundle.getString("prospero.general.error.unknown_command.or"));
            return format(bundle.getString("prospero.general.error.unknown_command.suggestion_multiple"),
                    StringUtils.join(suggestions, connector));
        }
    }

    default ArgumentParsingException featurePackNameNotMavenCoordinate() {
        return new ArgumentParsingException(format(bundle.getString("prospero.features.add.validation.fpl_name")));
    }

    default String layerNotSupported(String fpl, Set<String> layerName, Set<String> supportedLayers) {
        return format(bundle.getString("prospero.features.add.validation.layer.not_supported"),
                fpl, StringUtils.join(layerName, ", "), StringUtils.join(supportedLayers, ", "));
    }

    default String layerNotSupported(String fpl) {
        return format(bundle.getString("prospero.features.add.validation.layer.no_layers"), fpl);
    }

    default String modelNotSupported(String fpl, String model, Set<String> supportedModels) {
        return format(bundle.getString("prospero.features.add.validation.model.not_supported"),
                fpl, model, StringUtils.join(supportedModels, ", "));
    }

    default String galleonConfigNotSupported(String fpl, String model, String name) {
        return format(bundle.getString("prospero.features.add.validation.configuration.not_supported"),
                fpl, model, name);
    }

    default String featuresAddHeader(String fpl, Path dir) {
        return format(bundle.getString("prospero.features.add.header"), fpl, dir);
    }

    default String featuresAddPrompt() {
        return bundle.getString("prospero.features.add.prompt");
    }

    default String featuresAddPromptAccepted() {
        return bundle.getString("prospero.features.add.prompt.yes");
    }

    default String featuresAddPromptCancelled() {
        return bundle.getString("prospero.features.add.prompt.no");
    }

    default String featurePackTitle() {
        return bundle.getString("prospero.history.feature_pack.title");
    }

    default String configurationModel() {
        return bundle.getString("prospero.history.configuration_model.title");
    }

    default String diffFeaturesChanges() {
        return bundle.getString("prospero.changes.diff.features_changes");
    }

    default String productAndVersionNotNull() {
        return bundle.getString("prospero.update.subscribe.product.version.required");
    }

    default String unknownProduct(String product) {
        return format(bundle.getString("prospero.update.subscribe.unknown.product"), product);
    }

    default String writeManifest(Path manifestPath) {
        return format(bundle.getString("prospero.update.subscribe.write.manifest"), manifestPath);
    }

    default String writeChannelsConfiguration(Path channelsPath) {
        return format(bundle.getString("prospero.update.subscribe.write.channels"), channelsPath);
    }

    default String conflictsWhenGenerating(String diff) {
        return format(bundle.getString("prospero.update.subscribe.conflict.prompt"), diff);
    }

    default String continueGenerating() {
        return bundle.getString("prospero.update.subscribe.conflict.prompt.continue");
    }

    default String quitGenerating() {
        return bundle.getString("prospero.update.subscribe.conflict.prompt.cancel");
    }

    default String metadataExistsAlready(Path path, String distName) {
        return format(bundle.getString("prospero.update.subscribe.meta.exists"), path, distName);
    }

    default String serverVersionsHeader() {
        return bundle.getString("prospero.channels.versions.header");
    }

    default IllegalArgumentException unknownStabilityLevel(String stabilityLevel, List<String> supportedStabilityLevels) {
        return new IllegalArgumentException(format(bundle.getString("prospero.install.unknown_stability_level"),
                stabilityLevel, String.join(",", supportedStabilityLevels)));
    }

    default String featurePackRequiresLayers(String featurePackName) {
        return format(bundle.getString("prospero.features.add.validation.layers_required"), featurePackName, CliConstants.LAYERS);
    }

    default String featurePackDoesNotSupportCustomization(String featurePackName) {
        return format(bundle.getString("prospero.features.add.validation.customization_not_supported"), featurePackName,
                String.join(",", List.of(CliConstants.LAYERS, CliConstants.TARGET_CONFIG)));
    }

    default String featurePackRequiresLicense(String featurePackName) {
        return format(bundle.getString("prospero.features.add.required_licences"), featurePackName);
    }

    default CommandLine.ParameterException missingRequiredParameter(CommandLine spec, String param) {
        return new CommandLine.ParameterException(spec, format(bundle.getString("prospero.general.error.missing.parameter"), param));
    }

    default String availableProfiles() {
        return bundle.getString("prospero.install.list.profile.header");
    }
    default String noAvailableProfiles() {
        return bundle.getString("prospero.install.list.profile.no_profiles.header");
    }

    default String subscribedChannels() {
        return bundle.getString("prospero.install.list.profile.subscribe.channels");
    }
    default String includedFeaturePacks() {
        return bundle.getString("prospero.install.list.profile.featurePacks");
    }

    default String getProfile() {
        return bundle.getString("prospero.install.list.profile");
    }

    default OperationException sizeOfChannel(Path channelFile) {
        return new OperationException(format(bundle.getString("prospero.channels.error.morethanonechannel.found"), channelFile));
    }

    default String candidateApplyRollbackSuccess() {
        return bundle.getString("prospero.candidate.apply.error.rolled_back.desc");
    }

    default String candidateApplyRollbackFailure(Path backup) {
        return format(bundle.getString("prospero.candidate.apply.error.rollback_error.desc"), backup);
    }

    default OperationException cancelledByConfilcts() {
        return new OperationException(format(
                bundle.getString("prospero.updates.apply.candidate.cancel_conflicts"),
                CliConstants.NO_CONFLICTS_ONLY));
    }

    default String channelDowngradeWarningHeader() {
        return bundle.getString("prospero.downgrade.warning.header");
    }

    default String unexpectedVersionsHeader(String command) {
        return bundle.getString("prospero.downgrade.unexpected_versions").formatted(command);
    }

    default String noChannelVersionUpdates() {
        return bundle.getString("prospero.update.channel-list.no_updates");
    }

    default String channelVersionListUnsupportedChannelType() {
        return bundle.getString("prospero.update.channel-list.unsupported_channel");
    }

    default String channelVersionUpdateListHeader() {
        return bundle.getString("prospero.update.channel-list.updates_found.header");
    }

    default String channelVersionUpdateListChannelName() {
        return bundle.getString("prospero.update.channel-list.updates.channel_name");
    }

    default String channelVersionUpdateListCurrentVersion() {
        return bundle.getString("prospero.update.channel-list.updates.current_version");
    }

    default String channelVersionUpdateListAvailableVersions() {
        return bundle.getString("prospero.update.channel-list.updates.available_versions");
    }

    default String channelVersionUpdateListUpdateCommandSuggestion(String cmd) {
        return bundle.getString("prospero.update.channel-list.updates.update_command_suggestion")
                .formatted(cmd);
    }

    default String applyingVersionOverrides(int count) {
        return format(bundle.getString("prospero.general.version_overrides.applying"), count);
    }
}
