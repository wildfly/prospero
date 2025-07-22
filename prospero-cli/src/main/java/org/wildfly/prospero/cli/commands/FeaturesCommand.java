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

package org.wildfly.prospero.cli.commands;

import org.jboss.galleon.config.ConfigId;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.actions.FeaturesAddAction;
import org.wildfly.prospero.api.FileConflict;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.RepositoryUtils;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.FileConflictPrinter;
import org.wildfly.prospero.cli.LicensePrinter;
import org.wildfly.prospero.cli.RepositoryDefinition;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.api.TemporaryFilesManager;
import org.wildfly.prospero.licenses.License;
import org.wildfly.prospero.model.FeaturePackTemplate;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.wildfly.prospero.cli.commands.CliConstants.Commands.FEATURE_PACKS_ALIAS;

@CommandLine.Command(name = CliConstants.Commands.FEATURE_PACKS, aliases = {FEATURE_PACKS_ALIAS})
public class FeaturesCommand extends AbstractParentCommand {

    @CommandLine.Command(name = CliConstants.Commands.ADD, sortOptions = false)
    public static class AddCommand extends AbstractMavenCommand {

        @CommandLine.Option(
                names = CliConstants.FPL,
                paramLabel = CliConstants.FEATURE_PACK_REFERENCE,
                required = true)
        private String fpl;


        @CommandLine.Option(names = CliConstants.LAYERS, split = ",", required = false)
        private final Set<String> layers = new HashSet<>();

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        @CommandLine.Option(names = CliConstants.TARGET_CONFIG)
        private Optional<String> config = Optional.empty();

        @CommandLine.Option(names = {CliConstants.Y, CliConstants.YES})
        boolean skipConfirmation;

        @CommandLine.Option(names = CliConstants.ACCEPT_AGREEMENTS)
        boolean acceptAgreements;

        public AddCommand(CliConsole console, ActionFactory actionFactory) {
            super(console, actionFactory);
        }

        @Override
        public Integer call() throws Exception {
            final long startTime = System.currentTimeMillis();

            if (fpl.split(":").length != 2) {
                throw CliMessages.MESSAGES.featurePackNameNotMavenCoordinate();
            }

            final Path installationDir = determineInstallationDirectory(directory);

            final MavenOptions mavenOptions = parseMavenOptions();

            try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
                final List<Repository> repositories = RepositoryUtils.unzipArchives(
                        RepositoryDefinition.from(temporaryRepositories), temporaryFiles);

                console.println(CliMessages.MESSAGES.featuresAddHeader(fpl, installationDir));

                final FeaturesAddAction featuresAddAction = actionFactory.featuresAddAction(installationDir, mavenOptions, repositories, console);

                final List<License> pendingLicenses = featuresAddAction.getRequiredLicenses(fpl);

                if (!pendingLicenses.isEmpty()) {
                    console.println(System.lineSeparator() + CliMessages.MESSAGES.featurePackRequiresLicense(fpl) + System.lineSeparator());
                    new LicensePrinter(console).print(pendingLicenses);

                    if (acceptAgreements) {
                        console.println(CliMessages.MESSAGES.agreementSkipped(CliConstants.ACCEPT_AGREEMENTS) + System.lineSeparator());
                    } else {
                        if (!console.confirm(CliMessages.MESSAGES.acceptAgreements() + " ", "", CliMessages.MESSAGES.installationCancelled())) {
                            return ReturnCodes.PROCESSING_ERROR;
                        }
                    }
                }

                final FeaturePackTemplate featurePackRecipe = featuresAddAction.getFeaturePackRecipe(fpl);

                if (featurePackRecipe != null) {
                    if (featurePackRecipe.isRequiresLayers() && layers.isEmpty()) {
                        console.error(CliMessages.MESSAGES.featurePackRequiresLayers(fpl));
                        return ReturnCodes.INVALID_ARGUMENTS;
                    } else if (!featurePackRecipe.isSupportsCustomization() && (!layers.isEmpty() || config.isPresent())) {
                        console.error(CliMessages.MESSAGES.featurePackDoesNotSupportCustomization(fpl));
                        return ReturnCodes.INVALID_ARGUMENTS;
                    }
                }

                if (!featuresAddAction.isFeaturePackAvailable(fpl)) {
                    console.error(CliMessages.MESSAGES.featurePackNotFound(fpl));
                    return ReturnCodes.INVALID_ARGUMENTS;
                }

                final boolean accepted;
                if (!skipConfirmation) {
                    accepted = console.confirm(CliMessages.MESSAGES.featuresAddPrompt(),
                            CliMessages.MESSAGES.featuresAddPromptAccepted(),
                            CliMessages.MESSAGES.featuresAddPromptCancelled());
                } else {
                    console.println(CliMessages.MESSAGES.featuresAddPromptAccepted());
                    accepted = true;
                }

                if (accepted) {
                    try (TemporaryFilesManager temporaryFilesManager = TemporaryFilesManager.newInstance()) {
                        final Path candidate = temporaryFilesManager.createTempDirectory("prospero-fp-candidate");
                        final ConfigId configId = parseConfigName(config.orElse(null));
                        if (layers.isEmpty()) {
                            featuresAddAction.addFeaturePack(fpl, configId == null ? Collections.emptySet() : Set.of(configId), candidate);
                        } else {
                            featuresAddAction.addFeaturePackWithLayers(fpl, layers, configId, candidate);
                        }

                        // list conflicts (e.g. config files) and apply the update
                        final ApplyCandidateAction applyCandidateAction = actionFactory.applyUpdate(installationDir, candidate);
                        if (confirmConflicts(applyCandidateAction.getConflicts())) {
                            applyCandidateAction.applyUpdate(ApplyCandidateAction.Type.FEATURE_ADD);
                        }
                    } catch (FeaturesAddAction.LayerNotFoundException e) {
                        if (!e.getSupportedLayers().isEmpty()) {
                            console.error(CliMessages.MESSAGES.layerNotSupported(fpl, e.getLayers(), e.getSupportedLayers()));
                        } else {
                            console.error(CliMessages.MESSAGES.layerNotSupported(fpl));
                        }
                        return ReturnCodes.INVALID_ARGUMENTS;
                    } catch (FeaturesAddAction.ModelNotDefinedException e) {
                        console.error(CliMessages.MESSAGES.modelNotSupported(fpl, e.getModel(), e.getSupportedModels()));
                        return ReturnCodes.INVALID_ARGUMENTS;
                    } catch (FeaturesAddAction.ConfigurationNotFoundException e) {
                        console.error(CliMessages.MESSAGES.galleonConfigNotSupported(fpl, e.getModel(), e.getName()));
                        return ReturnCodes.INVALID_ARGUMENTS;
                    }
                }

                final float totalTime = (System.currentTimeMillis() - startTime) / 1000f;
                console.println(CliMessages.MESSAGES.operationCompleted(totalTime));

                return ReturnCodes.SUCCESS;
            }
        }

        private boolean confirmConflicts(List<FileConflict> conflicts) {
            if (conflicts.isEmpty() || skipConfirmation) {
                console.println(CliMessages.MESSAGES.featuresAddPromptAccepted());
                return true;
            }

            FileConflictPrinter.print(conflicts, console);
            return skipConfirmation || console.confirm(CliMessages.MESSAGES.featuresAddPrompt(),
                    CliMessages.MESSAGES.featuresAddPromptAccepted(),
                    CliMessages.MESSAGES.featuresAddPromptCancelled());
        }

        private static ConfigId parseConfigName(String config) {
            if (config == null) {
                return null;
            }
            int i = config.indexOf("/");
            if (i < 0) {
                return new ConfigId(null, config.trim());
            }

            if (i == config.length() - 1) {
                return new ConfigId(config.substring(0, i).trim(), null);
            } else {
                return new ConfigId(config.substring(0, i).trim(), config.substring(i + 1).trim());
            }
        }
    }



    public FeaturesCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory, CliConstants.Commands.FEATURE_PACKS, List.of(new AddCommand(console, actionFactory)));
    }
}
