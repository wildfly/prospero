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

import java.io.InputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.ChannelVersionChange;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.ProvisioningProgressEvent;
import org.wildfly.prospero.api.ArtifactChange;
import picocli.CommandLine;

import static org.jboss.galleon.Constants.TRACK_CONFIGS;
import static org.jboss.galleon.Constants.TRACK_LAYOUT_BUILD;
import static org.jboss.galleon.Constants.TRACK_PACKAGES;
import static org.wildfly.prospero.galleon.GalleonEnvironment.TRACK_JBEXAMPLES;
import static org.wildfly.prospero.galleon.GalleonEnvironment.TRACK_JBMODULES;
import static org.wildfly.prospero.galleon.GalleonEnvironment.TRACK_JB_ARTIFACTS_RESOLVE;
import static org.wildfly.prospero.galleon.GalleonEnvironment.TRACK_RESOLVING_VERSIONS;

@SuppressWarnings("PMD.TooManyStaticImports")
public class CliConsole implements Console {

    private static final int MAX_LENGTH = 120;

    private static class ProgressLogger {
        private final String starting;
        private  final String completed;
        private final String progress;

        ProgressLogger(String starting, String completed, String progress) {
            this.starting = starting;
            this.completed = completed;
            this.progress = progress;
        }

        ProgressLogger(String starting, String completed) {
            this.starting = starting;
            this.completed = completed;
            this.progress = starting;
        }

        String starting() {
            return starting;
        }
        String completed() {
            return completed;
        }
        String progress() {
            return progress;
        }
    }

    private class Cli {
        int lastLength;
        PrintStream out;

        Cli(PrintStream out) {
            this.out = out;
        }

        synchronized void  print(String msg) {
            final String eraser = StringUtils.repeat(' ', this.lastLength);
            out.print("\r" + eraser + "\r");
            lastLength = msg.length();
            out.print(msg);
        }

        synchronized void println(String msg) {
            final String eraser = StringUtils.repeat(' ', this.lastLength);
            out.print("\r" + eraser + "\r");
            lastLength = msg.length();
            out.println(msg);
        }
    }

    private static HashMap<String, ProgressLogger> loggers = new HashMap<>();

    public CliConsole() {
        loggers.put(TRACK_LAYOUT_BUILD, new ProgressLogger(CliMessages.MESSAGES.resolvingFeaturePack(), CliMessages.MESSAGES.featurePacksResolved()));
        loggers.put(TRACK_PACKAGES, new ProgressLogger(CliMessages.MESSAGES.installingPackages(), CliMessages.MESSAGES.packagesInstalled()));
        loggers.put(TRACK_CONFIGS, new ProgressLogger(CliMessages.MESSAGES.generatingConfiguration(), CliMessages.MESSAGES.configurationsGenerated()));
        loggers.put(TRACK_JBMODULES, new ProgressLogger(CliMessages.MESSAGES.installingJBossModules(), CliMessages.MESSAGES.jbossModulesInstalled()));
        loggers.put(TRACK_JBEXAMPLES, new ProgressLogger(CliMessages.MESSAGES.installingJBossExamples(), CliMessages.MESSAGES.jbossExamplesInstalled()));
        loggers.put(TRACK_JB_ARTIFACTS_RESOLVE, new ProgressLogger(CliMessages.MESSAGES.downloadingArtifacts(), CliMessages.MESSAGES.artifactsDownloaded()));
        loggers.put(TRACK_RESOLVING_VERSIONS, new ProgressLogger(CliMessages.MESSAGES.resolvingVersions(), CliMessages.MESSAGES.versionsResolved()));
    }

    private Cli cli = new Cli(getStdOut());

    @Override
    public void progressUpdate(ProvisioningProgressEvent update) {
        ProgressLogger logger = loggers.get(update.getStage());

        if (update.getEventType() == ProvisioningProgressEvent.EventType.STARTING) {
            cli.print(logger.starting());
        }

        if (update.getEventType() == ProvisioningProgressEvent.EventType.UPDATE) {

            final String item;
            if (update.isSlowPhase()) {
                item = " " + CliMessages.MESSAGES.installProgressWait() + "...";
            } else {
                 item = update.getCurrentItem();
            }

            final String progressMsg;
            final String details = item == null ? "" : item;

            if (update.getTotal() > 0) {
                progressMsg = String.format(" %d/%d(%.0f%%) ", update.getCompleted(), update.getTotal(), update.getProgress());
            } else {
                progressMsg = "";
            }

            final String text;
            if (logger.progress.length() + progressMsg.length() > MAX_LENGTH) {
                text = (logger.progress() + progressMsg).substring(0, MAX_LENGTH);
            } else if (logger.progress.length() + progressMsg.length() + details.length() > MAX_LENGTH) {
                int used = logger.progress.length() + progressMsg.length();
                int left = MAX_LENGTH - used;
                text = logger.progress() + progressMsg + "..." + details.substring(details.length() - left);
            } else {
                text = logger.progress() + progressMsg + details;
            }

            cli.print(text);
        }
        if (update.getEventType() == ProvisioningProgressEvent.EventType.COMPLETED) {
            cli.println(logger.completed());
        }
    }

    public void updatesFound(List<ArtifactChange> artifactUpdates, List<ChannelVersionChange> manifestUpdates) {
        if (artifactUpdates.isEmpty() && manifestUpdates.isEmpty()) {
            println(CliMessages.MESSAGES.noUpdatesFound());
            return;
        }

        if (!manifestUpdates.isEmpty()) {
            println(CliMessages.MESSAGES.manifestUpdatesFound());
            for (ChannelVersionChange manifestUpdate : manifestUpdates) {
                String channelName = manifestUpdate.channelName();
                ChannelVersion original = manifestUpdate.oldVersion();
                ChannelVersion updated = manifestUpdate.newVersion();
                String channelDescription = String.format("%s (%s)", original.getLocation(), channelName);

                printf("  %s%-40s    %20s  ==>  %-20s%n", downgradeMarker(manifestUpdate.isDowngrade()),
                        channelDescription, original.getPhysicalVersion(), updated.getPhysicalVersion(), channelName);
            }
            println("");
        }

        if (!artifactUpdates.isEmpty()) {
            println(CliMessages.MESSAGES.artifactUpdatesFound());
            for (ArtifactChange artifactUpdate : artifactUpdates) {
                final Optional<String> newVersion = artifactUpdate.getNewVersion();
                final Optional<String> oldVersion = artifactUpdate.getOldVersion();
                final String artifactName = artifactUpdate.getArtifactName();
                final String channelName = artifactUpdate.getChannelName().map(name -> "[" + name + "]")
                        .orElse("");

                printf("  %s%-50s    %20s  ==>  %-20s   %-20s%n",
                        downgradeMarker(artifactUpdate.isDowngrade()), artifactName, oldVersion.orElse("[]"),
                        newVersion.orElse("[]"), channelName);
            }

            if (artifactUpdates.stream().anyMatch(ArtifactChange::isDowngrade)) {
                printf(CliMessages.MESSAGES.possibleDowngrade());
            }
        }
    }

    private static String downgradeMarker(boolean downgrade) {
        return downgrade ? "@|fg(yellow) [*]|@" : "";
    }

    public void printArtifactChanges(List<ArtifactChange> artifactUpdates) {
        if (!artifactUpdates.isEmpty()) {
            getStdOut().println(CliMessages.MESSAGES.changesFound());
            for (ArtifactChange artifactUpdate : artifactUpdates) {
                final Optional<String> newVersion = artifactUpdate.getNewVersion();
                final Optional<String> oldVersion = artifactUpdate.getOldVersion();
                final String artifactName = artifactUpdate.getArtifactName();
                final String channelName = artifactUpdate.getChannelName().map(name -> "[" + name + "]")
                        .orElse("");

                getStdOut().printf("  %-50s    %-20s ==>  %-20s   %-20s%n", artifactName, oldVersion.orElse("[]"),
                        newVersion.orElse("[]"),channelName);
            }
        }
    }

    public boolean confirmUpdates() {
        return confirm(CliMessages.MESSAGES.continueWithUpdate(),
                CliMessages.MESSAGES.buildingUpdates(),
                CliMessages.MESSAGES.updateCancelled());
    }

    public boolean confirmBuildUpdates() {
        return confirm(CliMessages.MESSAGES.continueWithBuildUpdate(),
                CliMessages.MESSAGES.buildingUpdates(),
                CliMessages.MESSAGES.buildUpdateCancelled());
    }

    public boolean confirm(String prompt, String accepted, String cancelled) {
        getStdOut().print(prompt);
        Scanner sc = new Scanner(getInput());
        while (true) {
            String resp = sc.nextLine();
            if (resp.equalsIgnoreCase(CliMessages.MESSAGES.noShortcut()) || resp.isBlank()) {
                println(cancelled);
                return false;
            } else if (resp.equalsIgnoreCase(CliMessages.MESSAGES.yesShortcut())) {
                println(accepted);
                return true;
            } else {
                getStdOut().print(CliMessages.MESSAGES.chooseYN());
            }
        }
    }

    public void updatesComplete() {
        println(CliMessages.MESSAGES.updateComplete());
    }

    public void buildUpdatesComplete() {
        println(CliMessages.MESSAGES.buildUpdateComplete());
    }

    public PrintStream getStdOut() {
        return System.out;
    }

    public PrintStream getErrOut() {
        return System.err;
    }

    public InputStream getInput() {
        return System.in;
    }

    public void error(String message, String... args) {
        getErrOut().println(String.format(message, (Object[]) args));
    }

    @Override
    public void println(String text) {
        if (text == null) {
            getStdOut().println();
        } else {
            final CommandLine.Help.Ansi.Text formatted = CommandLine.Help.Ansi.AUTO.new Text(text);
            getStdOut().println(formatted.toString());
        }
    }

    public void printf(String text, String... args) {
        if (text == null) {
            getStdOut().println();
        } else {
            final String formatted = String.format(text, (String[]) args);
            getStdOut().print(CommandLine.Help.Ansi.AUTO.new Text(formatted));
        }
    }

}
