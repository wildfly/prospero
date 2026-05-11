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

import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.ArgumentParsingException;
import org.wildfly.prospero.cli.CliConsole;
import org.wildfly.prospero.cli.commands.options.LocalRepoOptions;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class AbstractMavenCommand extends AbstractCommand {
    @CommandLine.Option(names = CliConstants.DIR)
    Optional<Path> directory;

    @CommandLine.Option(names = CliConstants.REPOSITORIES, split = CliConstants.Others.VALUE_DELIMETER)
    List<String> temporaryRepositories = new ArrayList<>();

    @CommandLine.ArgGroup(exclusive = true, headingKey = "localRepoOptions.heading")
    LocalRepoOptions localRepoOptions = new LocalRepoOptions();

    @CommandLine.Option(names = CliConstants.OFFLINE)
    Optional<Boolean> offline = Optional.empty();

    public AbstractMavenCommand(CliConsole console, ActionFactory actionFactory) {
        super(console, actionFactory);
    }

    protected MavenOptions parseMavenOptions() throws ArgumentParsingException {
        final MavenOptions.Builder builder = localRepoOptions.toOptions();
        offline.map(builder::setOffline);
        return builder.build();
    }
}
