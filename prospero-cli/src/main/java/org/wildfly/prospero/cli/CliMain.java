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

import org.jboss.logging.Logger;
import org.jboss.logmanager.Configurator;
import org.jboss.logmanager.Level;
import org.jboss.logmanager.PropertyConfigurator;
import org.jboss.logmanager.config.LogContextConfiguration;
import org.wildfly.prospero.DistributionInfo;
import org.wildfly.prospero.VersionLogger;
import org.wildfly.prospero.cli.commands.ChannelCommand;
import org.wildfly.prospero.cli.commands.CliConstants;
import org.wildfly.prospero.cli.commands.CloneCommand;
import org.wildfly.prospero.cli.commands.CompletionCommand;
import org.wildfly.prospero.cli.commands.FeaturesCommand;
import org.wildfly.prospero.cli.commands.HistoryCommand;
import org.wildfly.prospero.cli.commands.InstallCommand;
import org.wildfly.prospero.cli.commands.MainCommand;
import org.wildfly.prospero.cli.commands.PrintLicensesCommand;
import org.wildfly.prospero.cli.commands.RevertCommand;
import org.wildfly.prospero.cli.commands.UpdateCommand;
import org.wildfly.prospero.cli.commands.channel.ChannelAddCommand;
import org.wildfly.prospero.cli.commands.channel.ChannelInitializeCommand;
import org.wildfly.prospero.cli.commands.channel.ChannelPromoteCommand;
import org.wildfly.prospero.cli.commands.channel.ChannelRemoveCommand;
import picocli.CommandLine;

import java.util.Arrays;

public class CliMain {

    static {
        enableJBossLogManager();
    }

    private static void enableJBossLogManager() {
        if (System.getProperty("java.util.logging.manager") == null) {
            System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
        }
    }

    static final Logger logger = Logger.getLogger(CliMain.class);

    public static void main(String[] args) {
        final boolean isDebug = Arrays.stream(args).anyMatch(CliConstants.DEBUG::equals);
        if (isDebug) {
            enableDebugLogging();
        }
        VersionLogger.logVersionOnStartup();

        try {
            int exitCode = execute(args);
            System.exit(exitCode);
        } catch (Exception e) {
            logException(e);
            System.exit(ReturnCodes.PROCESSING_ERROR);
        }
    }

    public static CommandLine createCommandLine(CliConsole console, String[] args) {
        return createCommandLine(console, args, new ActionFactory());
    }

    public static CommandLine createCommandLine(CliConsole console, String[] args, ActionFactory actionFactory) {
        CommandLine commandLine = new CommandLine(new MainCommand(console));
        // override main command name - this cannot be done via annotation as the value needs to be loaded at runtime
        commandLine.setCommandName(DistributionInfo.DIST_NAME);

        commandLine.addSubcommand(new InstallCommand(console, actionFactory));
        final UpdateCommand updateCommand = new UpdateCommand(console, actionFactory);
        commandLine.addSubcommand(updateCommand);
        updateCommand.addSubCommands(commandLine);
        commandLine.addSubcommand(new PrintLicensesCommand(console, actionFactory));
        commandLine.addSubcommand(new HistoryCommand(console, actionFactory));
        final RevertCommand revertCommand = new RevertCommand(console, actionFactory);
        commandLine.addSubcommand(revertCommand);
        revertCommand.addSubCommands(commandLine);
        commandLine.addSubcommand(new ChannelCommand(console, actionFactory));
        commandLine.addSubcommand(new CompletionCommand());

        CommandLine channelCmd = commandLine.getSubcommands().get(CliConstants.Commands.CHANNEL);
        channelCmd.addSubcommand(new ChannelAddCommand(console, actionFactory));
        channelCmd.addSubcommand(new ChannelRemoveCommand(console, actionFactory));
        channelCmd.addSubcommand(new ChannelCommand.ChannelListCommand(console, actionFactory));
        channelCmd.addSubcommand(new ChannelCommand.ChannelVersionCommand(console, actionFactory));
        channelCmd.addSubcommand(new ChannelInitializeCommand(console, actionFactory));
        channelCmd.addSubcommand(new ChannelPromoteCommand(console, actionFactory));

        CloneCommand cloneCommand = new CloneCommand(console, actionFactory);
        commandLine.addSubcommand(cloneCommand);
        cloneCommand.addSubCommands(commandLine);

        final FeaturesCommand featuresCommand = new FeaturesCommand(console, actionFactory);
        commandLine.addSubcommand(featuresCommand);
        featuresCommand.addSubCommands(commandLine);

        commandLine.setUsageHelpAutoWidth(true);
        final boolean isVerbose = Arrays.stream(args).anyMatch(s -> s.equals(CliConstants.VV) || s.equals(CliConstants.VERBOSE));
        final CommandLine.IParameterExceptionHandler rootParameterExceptionHandler = commandLine.getParameterExceptionHandler();
        commandLine.setExecutionExceptionHandler(new ExecutionExceptionHandler(console, isVerbose));

        commandLine.setParameterExceptionHandler(new UnknownCommandParameterExceptionHandler(rootParameterExceptionHandler, System.err, isVerbose));

        return commandLine;
    }

    private static void enableDebugLogging() {
        Configurator c = org.jboss.logmanager.Logger.getLogger("").getAttachment(Configurator.ATTACHMENT_KEY);
        if (c instanceof PropertyConfigurator) {
            LogContextConfiguration lcc = ((PropertyConfigurator) c).getLogContextConfiguration();
            lcc.getLoggerConfiguration("org.wildfly.prospero").setLevel(Level.DEBUG.getName());
            lcc.getHandlerConfiguration("CONSOLE").setLevel(Level.DEBUG.getName());
            if (!lcc.getLoggerConfiguration("").getHandlerNames().contains("CONSOLE")) {
                lcc.getLoggerConfiguration("").addHandlerName("CONSOLE");
            }
            lcc.commit();
        } else {
            logger.warn("Cannot change logging level, using default.");
        }
    }

    static int execute(String[] args) {
        try (CliConsole console = new CliConsole()) {
            CommandLine commandLine = createCommandLine(console, args);
            return commandLine.execute(args);
        }
    }

    static void logException(Exception e) {
        System.err.println(CliMessages.MESSAGES.errorWhenProcessingCommand() + e.getMessage());
        logger.error(CliMessages.MESSAGES.errorWhenProcessingCommand(), e);
    }

}
