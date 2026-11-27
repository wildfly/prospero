package org.wildfly.prospero.cli.printers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.wildfly.prospero.DistributionInfo;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.ChannelVersionChange;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.commands.CliConstants;
import org.wildfly.prospero.updates.ChannelsUpdateResult;
import picocli.CommandLine;

public class ChannelVersionChangesPrinter {

    private static final ProsperoLogger logger = ProsperoLogger.ROOT_LOGGER;

    private final Console console;


    public ChannelVersionChangesPrinter(Console console) {
        this.console = console;
    }

    public void printDowngrades(Collection<ChannelVersionChange> downgrades) {
        logger.infof("Displaying %d downgrade(s) to user", downgrades.size());
        console.println(CliMessages.MESSAGES.channelDowngradeWarningHeader());
        ListPrinter.unordered(console)
                .printItems(downgrades, this::formatDowngrade);
        console.println("");
        logger.infof("Downgrade warning displayed to user");
    }

    private String formatDowngrade(ChannelVersionChange downgrade) {
        logger.debugf("Preparing downgrade display: %s: %s -> %s",
                downgrade.channelName(),
                downgrade.oldVersion().getPhysicalVersion(),
                downgrade.newVersion().getPhysicalVersion());

        final StringBuilder sb = new StringBuilder();
        sb.append(downgrade.channelName()).append(": ");
        sb.append(downgrade.oldVersion().getPhysicalVersion());
        if (downgrade.oldVersion().getLogicalVersion() != null) {
            sb.append(" (").append(downgrade.oldVersion().getLogicalVersion()).append(")");
        }

        sb.append("  ->  ").append(downgrade.newVersion().getPhysicalVersion());
        if (downgrade.newVersion().getLogicalVersion() != null) {
            sb.append(" (").append(downgrade.newVersion().getLogicalVersion()).append(")");
        }
        return sb.toString();
    }

    public void printUnexpectedDowngradesError(Collection<ChannelVersionChange> downgrades, CommandLine.Model.CommandSpec spec) {
        logger.errorf("Displaying unexpected downgrade error to user for %d channel(s)", downgrades.size());
        List<String> formattedDowngrades = downgrades.stream().map(downgrade -> {
            logger.debugf("Building version arg for unexpected downgrade: %s::%s",
                    downgrade.channelName(), downgrade.newVersion().getPhysicalVersion());
            return downgrade.channelName() + "::" + downgrade.newVersion().getPhysicalVersion();
        }).toList();
        String versionArg = buildManifestVersionsSuggestion(formattedDowngrades);
        String suggestedCommand = buildCommandString(spec) + " " + versionArg;
        console.println(CliMessages.MESSAGES.unexpectedVersionsHeader(suggestedCommand));
    }

    public void printAvailableChannelChanges(ChannelsUpdateResult result, String serverDir) {
        if (!result.getUnsupportedChannels().isEmpty()) {
            console.println(CliMessages.MESSAGES.channelVersionListUnsupportedChannelType());
            return;
        }

        if (!result.hasUpdates()) {
            console.println(CliMessages.MESSAGES.noChannelVersionUpdates());
            return;
        }

        final List<String> currentManifestVersions = new ArrayList<>();
        console.println(CliMessages.MESSAGES.channelVersionUpdateListHeader());
        final ListPrinter channelList = ListPrinter.unordered(console);
        for (String channelName : result.getUpdatedChannels()) {
            final ChannelsUpdateResult.ChannelResult channelResult = result.getUpdatedVersion(channelName);
            if (channelResult.getStatus() == ChannelsUpdateResult.Status.UpdatesFound) {
                final Set<ChannelVersion> availableVersions = channelResult.getAvailableVersions();

                channelList.printItem("%s: %s".formatted(CliMessages.MESSAGES.channelVersionUpdateListChannelName(), channelName));
                ListPrinter details = channelList.unorderedSubList();
                details.printText("%s: %s".formatted(CliMessages.MESSAGES.channelVersionUpdateListCurrentVersion(), channelResult.getCurrentVersion().getPhysicalVersion()));
                details.printText("%s:".formatted(CliMessages.MESSAGES.channelVersionUpdateListAvailableVersions()));
                details.unorderedSubList()
                        .printItems(availableVersions, channelVersion -> {
                            final String logicalVersion = channelVersion.getLogicalVersion() == null ? "" : " (%s)".formatted(channelVersion.getLogicalVersion());
                            return "%s%s".formatted(channelVersion.getPhysicalVersion(), logicalVersion);
                        });
            }
            currentManifestVersions.add(channelName + "::" + channelResult.getCurrentVersion().getPhysicalVersion());
        }

        console.println("");

        final String updateCmd = "  %s %s %s %s %s %s".formatted(
                DistributionInfo.DIST_NAME, CliConstants.Commands.UPDATE, CliConstants.Commands.PERFORM,
                CliConstants.DIR, serverDir, buildManifestVersionsSuggestion(currentManifestVersions));
        console.println(CliMessages.MESSAGES.channelVersionUpdateListUpdateCommandSuggestion(updateCmd));
    }

    private String buildCommandString(CommandLine.Model.CommandSpec spec) {
        final List<String> args = spec.commandLine().getParseResult().originalArgs();
        final StringBuilder sb = new StringBuilder();

        sb.append(spec.root().name()).append(" ");
        args.forEach(a->sb.append(a).append(" "));

        return sb.toString().trim();
    }

    private String buildManifestVersionsSuggestion(List<String> manifestVersions) {
        return CliConstants.MANIFEST_VERSIONS + CliConstants.Others.FLAG_SEPARATOR
                + String.join(CliConstants.Others.VALUE_DELIMETER, manifestVersions);
    }
}
