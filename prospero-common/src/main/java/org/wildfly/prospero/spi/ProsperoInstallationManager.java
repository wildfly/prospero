package org.wildfly.prospero.spi;

import org.jboss.galleon.ProvisioningException;
import org.jboss.logging.Logger;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.MavenCoordinate;
import org.wildfly.installationmanager.ArtifactChange;
import org.wildfly.installationmanager.Channel;
import org.wildfly.installationmanager.ChannelChange;
import org.wildfly.installationmanager.HistoryResult;
import org.wildfly.installationmanager.InstallationChanges;
import org.wildfly.installationmanager.ManifestVersion;
import org.wildfly.installationmanager.MavenOptions;
import org.wildfly.installationmanager.OperationNotAvailableException;
import org.wildfly.installationmanager.Repository;
import org.wildfly.installationmanager.spi.InstallationManager;
import org.wildfly.installationmanager.spi.OsShell;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.actions.InstallationExportAction;
import org.wildfly.prospero.actions.InstallationHistoryAction;
import org.wildfly.prospero.actions.MetadataAction;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.MavenOptions.Builder;
import org.wildfly.prospero.galleon.GalleonCallbackAdapter;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.spi.internal.CliProvider;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.updates.UpdateSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProsperoInstallationManager implements InstallationManager {

    private static final Logger logger = Logger.getLogger(GalleonCallbackAdapter.class);

    private final ActionFactory actionFactory;
    private Path installationDir;

    public ProsperoInstallationManager(Path installationDir, MavenOptions mavenOptions) throws Exception {
        final Builder options = org.wildfly.prospero.api.MavenOptions.builder()
                .setOffline(mavenOptions.isOffline());
        if (mavenOptions.getLocalRepository() != null) {
            options.setNoLocalCache(false);
            options.setLocalCachePath(mavenOptions.getLocalRepository());
        }
        actionFactory = new ActionFactory(installationDir, options.build());
        this.installationDir = installationDir;
    }

    // Used for tests to mock up action creation
    protected ProsperoInstallationManager(ActionFactory actionFactory) {
        this.actionFactory = actionFactory;
    }

    @Override
    public List<HistoryResult> history() throws Exception {
        logger.info("Listing installation history");
        final InstallationHistoryAction historyAction = actionFactory.getHistoryAction();
        final List<SavedState> revisions = historyAction.getRevisions();
        final List<HistoryResult> results = new ArrayList<>();

        for (SavedState savedState : revisions) {
            results.add(new HistoryResult(savedState.getName(), savedState.getTimestamp(), savedState.getType().toString(),
                    savedState.getMsg(),
                    map(savedState.getManifestVersions(), ProsperoInstallationManager::mapManifestVersion)));
        }
        return results;
    }

    @Override
    public InstallationChanges revisionDetails(String revision) throws MetadataException {
        Objects.requireNonNull(revision);
        final InstallationHistoryAction historyAction = actionFactory.getHistoryAction();
        final org.wildfly.prospero.api.InstallationChanges changes = historyAction.compare(new SavedState(revision));

        if (changes.isEmpty()) {
            return new InstallationChanges(Collections.emptyList(), Collections.emptyList());
        } else {
            final List<ArtifactChange> artifacts = changes.getArtifactChanges().stream()
                    .map(ProsperoInstallationManager::mapArtifactChange)
                    .collect(Collectors.toList());

            final List<ChannelChange> channels = changes.getChannelChanges().stream()
                    .map(ProsperoInstallationManager::mapChannelChange)
                    .collect(Collectors.toList());
            return new InstallationChanges(artifacts, channels);
        }
    }

    @Override
    public void prepareRevert(String revision, Path targetDir, List<Repository> repositories) throws Exception {
        Objects.requireNonNull(revision);
        Objects.requireNonNull(targetDir);
        final InstallationHistoryAction historyAction = actionFactory.getHistoryAction();
        historyAction.prepareRevert(new SavedState(revision), actionFactory.mavenOptions,
                map(repositories, ProsperoInstallationManager::mapRepository), targetDir);
    }

    @Override
    public boolean prepareUpdate(Path targetDir, List<Repository> repositories) throws Exception {
        try (UpdateAction prepareUpdateAction = actionFactory.getUpdateAction(map(repositories, ProsperoInstallationManager::mapRepository))) {
            return prepareUpdateAction.buildUpdate(targetDir);
        }
    }

    @Override
    public List<ArtifactChange> findUpdates(List<Repository> repositories) throws Exception {
        try (UpdateAction updateAction = actionFactory.getUpdateAction(map(repositories, ProsperoInstallationManager::mapRepository))) {
            final UpdateSet updates = updateAction.findUpdates();
            return updates.getArtifactUpdates().stream()
                    .map(ProsperoInstallationManager::mapArtifactChange)
                    .collect(Collectors.toList());
        }
    }

    @Override
    public Collection<Channel> listChannels() throws OperationException {
        try (MetadataAction metadataAction = actionFactory.getMetadataAction()) {
            return metadataAction.getChannels().stream()
                    .map(ProsperoInstallationManager::mapChannel)
                    .collect(Collectors.toList());
        }
    }

    @Override
    public void removeChannel(String channelName) throws OperationException {
        try (MetadataAction metadataAction = actionFactory.getMetadataAction()) {
            metadataAction.removeChannel(channelName);
        }
    }

    @Override
    public void addChannel(Channel channel) throws OperationException {
        try (MetadataAction metadataAction = actionFactory.getMetadataAction()) {
            metadataAction.addChannel(mapChannel(channel));
        }
    }

    @Override
    public void changeChannel(Channel newChannel) throws OperationException {
        if (newChannel.getName() == null || newChannel.getName().isEmpty()) {
            throw ProsperoLogger.ROOT_LOGGER.emptyChannelName();
        }
        try (MetadataAction metadataAction = actionFactory.getMetadataAction()) {
            metadataAction.changeChannel(newChannel.getName(), mapChannel(newChannel));
        }
    }

    @Override
    public Path createSnapshot(Path targetPath) throws Exception {
        final Path snapshotPath;
        if (!Files.exists(targetPath)) {
            if (targetPath.toString().toLowerCase().endsWith(".zip")) {
                snapshotPath = targetPath.toAbsolutePath();
            } else {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                snapshotPath = targetPath.resolve("im-snapshot-" + timestamp + ".zip").toAbsolutePath();
            }
        } else if (Files.isDirectory(targetPath)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            snapshotPath = targetPath.resolve("im-snapshot-" + timestamp + ".zip").toAbsolutePath();
        } else {
            throw ProsperoLogger.ROOT_LOGGER.fileAlreadyExists(targetPath);
        }

        final InstallationExportAction installationExportAction = actionFactory.getInstallationExportAction();
        installationExportAction.export(snapshotPath);

        return snapshotPath;
    }

    @Override
    public String generateApplyUpdateCommand(Path scriptHome, Path candidatePath) throws OperationNotAvailableException {
        final boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows");
        return this.generateApplyUpdateCommand(scriptHome, candidatePath, isWindows?OsShell.WindowsBash:OsShell.Linux);
    }

    @Override
    public String generateApplyRevertCommand(Path scriptHome, Path candidatePath) throws OperationNotAvailableException {
        final boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows");
        return this.generateApplyRevertCommand(scriptHome, candidatePath, isWindows?OsShell.WindowsBash:OsShell.Linux);
    }

    @Override
    public String generateApplyUpdateCommand(Path scriptHome, Path candidatePath, OsShell shell) throws OperationNotAvailableException {
        final Optional<CliProvider> cliProviderLoader = ServiceLoader.load(CliProvider.class).findFirst();
        if (cliProviderLoader.isEmpty()) {
            throw new OperationNotAvailableException("Installation manager does not support CLI operations.");
        }

        final CliProvider cliProvider = cliProviderLoader.get();
        return escape(scriptHome.resolve(cliProvider.getScriptName(shell))) + " " + cliProvider.getApplyUpdateCommand(installationDir, candidatePath);
    }

    @Override
    public String generateApplyRevertCommand(Path scriptHome, Path candidatePath, OsShell shell) throws OperationNotAvailableException {
        final Optional<CliProvider> cliProviderLoader = ServiceLoader.load(CliProvider.class).findFirst();
        if (cliProviderLoader.isEmpty()) {
            throw new OperationNotAvailableException("Installation manager does not support CLI operations.");
        }

        final CliProvider cliProvider = cliProviderLoader.get();
        return escape(scriptHome.resolve(cliProvider.getScriptName(shell))) + " " + cliProvider.getApplyRevertCommand(installationDir, candidatePath);
    }

    @Override
    public Collection<ManifestVersion> getInstalledVersions() throws MetadataException {
        try (MetadataAction metadataAction = actionFactory.getMetadataAction()) {
            final ManifestVersionRecord versionRecord = metadataAction.getChannelVersions();
            return Stream.concat(
                    versionRecord.getMavenManifests().stream()
                            .map(m->new ManifestVersion(m.getGroupId()+":"+m.getArtifactId(), m.getDescription(), m.getVersion(), ManifestVersion.Type.MAVEN)),
                    versionRecord.getUrlManifests().stream()
                            .map(m->new ManifestVersion(m.getUrl(), m.getDescription(), m.getHash(), ManifestVersion.Type.URL))
                    )
                    .collect(Collectors.toList());
        }
    }

    private String escape(Path absolutePath) {
        return "\"" + absolutePath.toString() + "\"";
    }

    private static Channel mapChannel(org.wildfly.channel.Channel channel) {
        if (channel.getManifestCoordinate() == null) {
            return new Channel(channel.getName(), map(channel.getRepositories(), ProsperoInstallationManager::mapRepository));
        } else if (channel.getManifestCoordinate().getUrl() != null) {
            return new Channel(channel.getName(), map(channel.getRepositories(), ProsperoInstallationManager::mapRepository), channel.getManifestCoordinate().getUrl());
        } else if (channel.getManifestCoordinate().getMaven() != null) {
            return new Channel(channel.getName(), map(channel.getRepositories(), ProsperoInstallationManager::mapRepository), toGav(channel.getManifestCoordinate().getMaven()));
        } else {
            return new Channel(channel.getName(), map(channel.getRepositories(), ProsperoInstallationManager::mapRepository));
        }
    }

    private static String toGav(MavenCoordinate coord) {
        final String ga = coord.getGroupId() + ":" + coord.getArtifactId();
        if (coord.getVersion() != null && !coord.getVersion().isEmpty()) {
            return ga + ":" + coord.getVersion();
        }
        return ga;
    }

    private static org.wildfly.channel.Channel mapChannel(Channel channel) {
        return new org.wildfly.channel.Channel(channel.getName(), null, null,
                map(channel.getRepositories(), ProsperoInstallationManager::mapRepository), toManifestCoordinate(channel),
                null, null);
    }

    private static ChannelManifestCoordinate toManifestCoordinate(Channel c) {
        if (c.getManifestUrl().isPresent()) {
            return new ChannelManifestCoordinate(c.getManifestUrl().get());
        } else if (c.getManifestCoordinate().isPresent()) {
            final String[] coordinate = c.getManifestCoordinate().get().split(":");
            if (coordinate.length == 3) {
                return new ChannelManifestCoordinate(coordinate[0], coordinate[1], coordinate[2]);
            } else {
                return new ChannelManifestCoordinate(coordinate[0], coordinate[1]);
            }
        } else {
            return null;
        }
    }

    private static <T, R> List<R> map(Collection<T> subject, Function<T,R> mapper) {
        if (subject == null) {
            return Collections.emptyList();
        }
        return subject.stream().map(mapper::apply).collect(Collectors.toList());
    }

    private static org.wildfly.channel.Repository mapRepository(Repository repository) {
        return new org.wildfly.channel.Repository(repository.getId(), repository.getUrl());
    }

    private static Repository mapRepository(org.wildfly.channel.Repository repository) {
        return new Repository(repository.getId(), repository.getUrl());
    }

    private static ArtifactChange mapArtifactChange(org.wildfly.prospero.api.ArtifactChange change) {
        if (change.isInstalled()) {
            return new ArtifactChange(null, change.getNewVersion().get(), change.getArtifactName(), ArtifactChange.Status.INSTALLED);
        } else if (change.isRemoved()) {
            return new ArtifactChange(change.getOldVersion().get(), null, change.getArtifactName(), ArtifactChange.Status.REMOVED);
        } else {
            return new ArtifactChange(change.getOldVersion().get(), change.getNewVersion().get(), change.getArtifactName(), ArtifactChange.Status.UPDATED);
        }
    }

    private static ChannelChange mapChannelChange(org.wildfly.prospero.api.ChannelChange change) {
        final Channel oldChannel = change.getOldChannel() == null ? null : mapChannel(change.getOldChannel());
        final Channel newChannel = change.getNewChannel() == null ? null : mapChannel(change.getNewChannel());

        switch (change.getStatus()) {
            case ADDED:
                return new ChannelChange(oldChannel, newChannel, ChannelChange.Status.ADDED);
            case REMOVED:
                return new ChannelChange(oldChannel, newChannel, ChannelChange.Status.REMOVED);
            default:
                return new ChannelChange(oldChannel, newChannel, ChannelChange.Status.MODIFIED);
        }
    }

    private static ManifestVersion mapManifestVersion(SavedState.Version version) {
        return new ManifestVersion(version.getIdentifier(), version.getLogicalVersion(), version.getPhysicalVersion(), ManifestVersion.Type.MAVEN);
    }

    ActionFactory getActionFactory() {
        return actionFactory;
    }

    protected static class ActionFactory {

        private final Path server;
        private final org.wildfly.prospero.api.MavenOptions mavenOptions;

        private ActionFactory(Path server, org.wildfly.prospero.api.MavenOptions mavenOptions) {
            this.server = server;
            this.mavenOptions = mavenOptions;
        }

        protected InstallationHistoryAction getHistoryAction() {
            return new InstallationHistoryAction(server, null);
        }

        protected UpdateAction getUpdateAction(List<org.wildfly.channel.Repository> repositories) throws OperationException, ProvisioningException {
            return new UpdateAction(server, mavenOptions, null, repositories);
        }

        protected MetadataAction getMetadataAction() throws MetadataException {
            return new MetadataAction(server);
        }

        protected InstallationExportAction getInstallationExportAction() {
            return new InstallationExportAction(server);
        }

        org.wildfly.prospero.api.MavenOptions getMavenOptions() {
            return mavenOptions;
        }
    }
}
