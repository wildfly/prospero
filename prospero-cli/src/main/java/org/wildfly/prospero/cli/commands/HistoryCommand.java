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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.wildfly.prospero.actions.InstallationHistoryAction;
import org.wildfly.prospero.api.InstallationChanges;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.DiffPrinter;
import org.wildfly.prospero.cli.ReturnCodes;
import picocli.CommandLine;

@CommandLine.Command(
        name = CliConstants.Commands.HISTORY,
        sortOptions = false
)
public class HistoryCommand extends AbstractCommand {

    @CommandLine.Option(names = CliConstants.DIR)
    Optional<Path> directory;

    @CommandLine.Option(names = CliConstants.REVISION)
    Optional<String> revision;

    public HistoryCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory);
    }

    @Override
    public Integer call() throws Exception {
        Path installationDirectory = determineInstallationDirectory(directory);
        InstallationHistoryAction historyAction = actionFactory.history(installationDirectory, console);

        if (revision.isEmpty()) {
            List<SavedState> revisions = historyAction.getRevisions();
            for (SavedState savedState : revisions) {
                console.println(savedState.shortDescription());
                for (SavedState.Version manifestVersion: savedState.getManifestVersions()) {
                    console.println("  * " + manifestVersion.getDisplayVersion());
                }
            }
        } else {
            InstallationChanges changes = historyAction.getRevisionChanges(new SavedState(revision.get()));
            if (changes.isEmpty()) {
                console.println(CliMessages.MESSAGES.noChangesFound());
            } else {
                final DiffPrinter diffPrinter = new DiffPrinter(console, "  ");
                boolean needsLineBreak = false;
                if (!changes.getArtifactChanges().isEmpty()) {
                    console.println(CliMessages.MESSAGES.diffUpdates()+ ":");
                    changes.getArtifactChanges().forEach(diffPrinter::print);
                    needsLineBreak = true;
                }
                if (!changes.getChannelChanges().isEmpty()) {
                    if (needsLineBreak) {
                        console.println("");
                    }
                    console.println(CliMessages.MESSAGES.diffConfigChanges()+ ":");
                    changes.getChannelChanges().forEach(diffPrinter::print);
                    needsLineBreak = true;
                }
                if (!changes.getFeatureChanges().isEmpty()) {
                    if (needsLineBreak) {
                        console.println("");
                    }
                    console.println(CliMessages.MESSAGES.diffFeaturesChanges() + ":");
                    changes.getFeatureChanges().forEach(diffPrinter::print);
                }
            }
        }

        return ReturnCodes.SUCCESS;
    }
}
