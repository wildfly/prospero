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

import org.wildfly.channel.Channel;
import org.wildfly.prospero.actions.ProvisioningAction;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.ProvisioningDefinition;
import org.wildfly.prospero.api.TemporaryFilesManager;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.LicensePrinter;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.cli.ChannelUtils;

import org.wildfly.prospero.licenses.License;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;

@CommandLine.Command(
        name = CliConstants.Commands.PRINT_LICENSES,
        sortOptions = false
)
public class PrintLicensesCommand extends AbstractInstallCommand {

    public PrintLicensesCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory);
    }

    @Override
    public Integer call() throws Exception {

        try (TemporaryFilesManager temporaryFiles = TemporaryFilesManager.newInstance()) {
            final ProvisioningDefinition provisioningDefinition = buildDefinition(temporaryFiles).build();
            final MavenOptions mavenOptions = getMavenOptions();
            final GalleonProvisioningConfig provisioningConfig = provisioningDefinition.toProvisioningConfig();
            final List<Channel> channels = ChannelUtils.resolveChannels(provisioningDefinition, mavenOptions);

            Path tempDirectory = temporaryFiles.createTempDirectory("tmp-installer");
            final ProvisioningAction provisioningAction = actionFactory.install(tempDirectory.toAbsolutePath(),
                    mavenOptions, console);

            final List<License> pendingLicenses = provisioningAction.getPendingLicenses(provisioningConfig, channels);
            if (!pendingLicenses.isEmpty()) {
                console.println("");
                console.println(CliMessages.MESSAGES.listAgreementsHeader());
                console.println("");
                new LicensePrinter(console).print(pendingLicenses);
            } else {
                console.println("");
                console.println(CliMessages.MESSAGES.noAgreementsNeeded());
            }
            return ReturnCodes.SUCCESS;
        }
    }
}
