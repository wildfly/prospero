package org.wildfly.prospero.spi;

import org.jboss.galleon.ProvisioningException;
import org.jboss.logging.Logger;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.MavenCoordinate;
import org.wildfly.channel.version.VersionMatcher;
import org.wildfly.common.annotation.Nullable;
import org.wildfly.installationmanager.ArtifactChange;
import org.wildfly.installationmanager.AvailableManifestVersions;
import org.wildfly.installationmanager.CandidateType;
import org.wildfly.installationmanager.Channel;
import org.wildfly.installationmanager.ChannelChange;
import org.wildfly.installationmanager.FileConflict;
import org.wildfly.installationmanager.HistoryResult;
import org.wildfly.installationmanager.InstallationChanges;
import org.wildfly.installationmanager.InstallationUpdates;
import org.wildfly.installationmanager.ManifestVersion;
import org.wildfly.installationmanager.ManifestVersionChange;
import org.wildfly.installationmanager.ManifestVersionPair;
import org.wildfly.installationmanager.MavenOptions;
import org.wildfly.installationmanager.OperationNotAvailableException;
import org.wildfly.installationmanager.Repository;
import org.wildfly.installationmanager.spi.InstallationManager;
import org.wildfly.installationmanager.spi.OsShell;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.actions.InstallationExportAction;
import org.wildfly.prospero.actions.InstallationHistoryAction;
import org.wildfly.prospero.actions.MetadataAction;
import org.wildfly.prospero.actions.OverrideBuilder;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.ChannelVersion;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.MavenOptions.Builder;
import org.wildfly.prospero.api.exceptions.InvalidUpdateCandidateException;
import org.wildfly.prospero.galleon.GalleonCallbackAdapter;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.spi.internal.CliProvider;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.updates.ChannelsUpdateResult;
import org.wildfly.prospero.updates.UpdateSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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

    private static final Comparator<ManifestVersionPair> MANIFEST_VERSION_PAIR_COMPARATOR =
            (p1, p2) -> VersionMatcher.COMPARATOR.compare(p1.getPhysicalVersion(), p2.getPhysicalVersion());

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
        final org.wildfly.prospero.api.InstallationChanges changes = historyAction.getRevisionChanges(new SavedState(revision));

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
    public boolean prepareUpdate(Path candidatePath, List<Repository> repositories) throws Exception {
        return prepareUpdate(candidatePath, repositories, false);
    }

    @Override
    public boolean prepareUpdate(Path targetDir, List<Repository> repositories, boolean allowDowngrade) throws Exception {
        try (UpdateAction prepareUpdateAction = actionFactory.getUpdateAction(map(repositories, ProsperoInstallationManager::mapRepository), allowDowngrade)) {
            return prepareUpdateAction.buildUpdate(targetDir);
        }
    }

    @Override
    public boolean prepareUpdate(Path targetDir, List<Repository> repositories, List<ManifestVersion> manifestVersions, boolean allowDowngrade)
            throws Exception {
        var mappedRepositories = map(repositories, ProsperoInstallationManager::mapRepository);
        try (UpdateAction updateAction = actionFactory.getUpdateAction(mappedRepositories, manifestVersions, allowDowngrade)) {
            return updateAction.buildUpdate(targetDir);
        }
    }

    @Override
    public Collection<FileConflict> verifyCandidate(Path candidatePath, CandidateType candidateType) throws Exception {
        final ApplyCandidateAction applyCandidateAction = actionFactory.getApplyCandidateAction(candidatePath);
        final ApplyCandidateAction.Type operation;
        switch (candidateType) {
            case UPDATE:
                operation = ApplyCandidateAction.Type.UPDATE;
                break;
            case REVERT:
                operation = ApplyCandidateAction.Type.REVERT;
                break;
            default:
                throw new IllegalArgumentException("Unsupported candidate type: " + candidateType);
        }

        final ApplyCandidateAction.ValidationResult validationResult = applyCandidateAction.verifyCandidate(operation);
        switch (validationResult) {
            case OK:
                // we're good, continue
                break;
            case STALE:
                throw ProsperoLogger.ROOT_LOGGER.staleCandidate(installationDir, candidatePath);
            case NO_CHANGES:
                throw ProsperoLogger.ROOT_LOGGER.noChangesAvailable(installationDir, candidatePath);
            case NOT_CANDIDATE:
                throw ProsperoLogger.ROOT_LOGGER.notCandidate(candidatePath);
            case WRONG_TYPE:
                throw ProsperoLogger.ROOT_LOGGER.wrongCandidateOperation(candidatePath, operation);
            default:
                // unexpected validation type - include the error in the description
                throw new InvalidUpdateCandidateException(String.format("The candidate server %s is invalid - %s.", candidatePath, validationResult));
        }

        return map(applyCandidateAction.getConflicts(), ProsperoInstallationManager::mapFileConflict);
    }

    private static FileConflict mapFileConflict(org.wildfly.prospero.api.FileConflict fileConflict) {
        return new FileConflict(Path.of(fileConflict.getRelativePath()), map(fileConflict.getUserChange()), map(fileConflict.getUpdateChange()), fileConflict.getResolution() == org.wildfly.prospero.api.FileConflict.Resolution.UPDATE);
    }

    private static FileConflict.Status map(org.wildfly.prospero.api.FileConflict.Change change) {
        switch (change) {
            case MODIFIED:
                return FileConflict.Status.MODIFIED;
            case ADDED:
                return FileConflict.Status.ADDED;
            case REMOVED:
                return FileConflict.Status.REMOVED;
            case NONE:
                return FileConflict.Status.NONE;
            default:
                throw new IllegalArgumentException("Unknown file conflict change: " + change);
        }
    }

    public List<ArtifactChange> findUpdates(List<Repository> repositories) throws Exception {
        return findInstallationUpdates(repositories, null).artifactUpdates();
    }

    @Override
    public InstallationUpdates findInstallationUpdates(List<Repository> repositories) throws Exception {
        return findInstallationUpdates(repositories, null);
    }

    @Override
    public InstallationUpdates findInstallationUpdates(List<Repository> repositories, List<ManifestVersion> manifestVersions)
            throws Exception {
        var mappedRepositories = map(repositories, ProsperoInstallationManager::mapRepository);
        try (UpdateAction updateAction = actionFactory.getUpdateAction(mappedRepositories, manifestVersions, true)) {
            final UpdateSet updates = updateAction.findUpdates();
            List<ArtifactChange> artifactUpdates = updates.getArtifactUpdates().stream()
                    .map(ProsperoInstallationManager::mapArtifactChange)
                    .toList();
            List<ManifestVersionChange> channelUpdates = updates.getChannelVersionChanges().stream().map(ProsperoInstallationManager::mapChannelVersionChange).toList();
            return new InstallationUpdates(artifactUpdates, channelUpdates);
        }
    }

    @Override
    public List<AvailableManifestVersions> findAvailableManifestVersions(List<Repository> repositories, boolean includeDowngrades) throws Exception {
        var mappedRepositories = map(repositories, ProsperoInstallationManager::mapRepository);
        try (UpdateAction updateAction = actionFactory.getUpdateAction(mappedRepositories, true)) {
            ChannelsUpdateResult channelUpdates = updateAction.findChannelUpdates(includeDowngrades);
            ArrayList<AvailableManifestVersions> results = new ArrayList<>();

            for (String channelName: channelUpdates.getUpdatedChannels()) {
                ChannelsUpdateResult.ChannelResult channelUpdate = channelUpdates.getUpdatedVersion(channelName);

                // Only include channels with updates
                if (ChannelsUpdateResult.Status.UpdatesFound.equals(channelUpdate.getStatus())) {
                    List<ManifestVersionPair> availableVersions = channelUpdate.getAvailableVersions().stream()
                            // Exclude current version from the list of available versions
                            .filter(v -> !v.getPhysicalVersion().equals(channelUpdate.getCurrentVersion().getPhysicalVersion()))
                            .map(ProsperoInstallationManager::mapChannelVersion)
                            // Sort in ascending order
                            .sorted(MANIFEST_VERSION_PAIR_COMPARATOR).toList();

                    String location = channelUpdate.getAvailableVersions().stream()
                            .map(ChannelVersion::getLocation).findFirst().orElse(null);

                    ChannelVersion currentVersion = channelUpdate.getCurrentVersion();
                    ManifestVersionPair mappedCurrentVersions = new ManifestVersionPair(currentVersion.getPhysicalVersion(),
                            currentVersion.getLogicalVersion());

                    AvailableManifestVersions availableManifestVersions =
                            new AvailableManifestVersions(channelName, location, mappedCurrentVersions, availableVersions);
                    results.add(availableManifestVersions);
                }
            }
            return results;
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
        return generateApplyUpdateCommand(scriptHome, candidatePath, shell, false);
    }

    @Override
    public String generateApplyRevertCommand(Path scriptHome, Path candidatePath, OsShell shell) throws OperationNotAvailableException {
        return generateApplyRevertCommand(scriptHome, candidatePath, shell, false);
    }

    @Override
    public String generateApplyUpdateCommand(Path scriptHome, Path candidatePath, OsShell shell, boolean noConflictsOnly) throws OperationNotAvailableException {
        final Optional<CliProvider> cliProviderLoader = ServiceLoader.load(CliProvider.class).findFirst();
        if (cliProviderLoader.isEmpty()) {
            throw new OperationNotAvailableException("Installation manager does not support CLI operations.");
        }

        final CliProvider cliProvider = cliProviderLoader.get();
        return escape(scriptHome.resolve(cliProvider.getScriptName(shell))) + " " + cliProvider.getApplyUpdateCommand(installationDir, candidatePath, false);
    }

    @Override
    public String generateApplyRevertCommand(Path scriptHome, Path candidatePath, OsShell shell, boolean noConflictsOnly) throws OperationNotAvailableException {
        final Optional<CliProvider> cliProviderLoader = ServiceLoader.load(CliProvider.class).findFirst();
        if (cliProviderLoader.isEmpty()) {
            throw new OperationNotAvailableException("Installation manager does not support CLI operations.");
        }

        final CliProvider cliProvider = cliProviderLoader.get();
        return escape(scriptHome.resolve(cliProvider.getScriptName(shell))) + " " + cliProvider.getApplyRevertCommand(installationDir, candidatePath, noConflictsOnly);
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

    private static ManifestVersionChange mapChannelVersionChange(org.wildfly.prospero.api.ChannelVersionChange change) {
        ManifestVersionPair oldVersion = new ManifestVersionPair(change.oldVersion().getPhysicalVersion(),
                change.oldVersion().getLogicalVersion());
        ManifestVersionPair newVersion = new ManifestVersionPair(change.newVersion().getPhysicalVersion(),
                change.newVersion().getLogicalVersion());
        return new ManifestVersionChange(change.channelName(), change.oldVersion().getLocation(),
                oldVersion, newVersion, change.isDowngrade());
    }

    private static ManifestVersionPair mapChannelVersion(ChannelVersion channelVersion) {
        return new ManifestVersionPair(channelVersion.getPhysicalVersion(), channelVersion.getLogicalVersion());
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

        protected UpdateAction getUpdateAction(List<org.wildfly.channel.Repository> repositories, boolean allowManifestDowngrades) throws OperationException, ProvisioningException {
            return getUpdateAction(repositories, null, allowManifestDowngrades);
        }

        protected UpdateAction getUpdateAction(List<org.wildfly.channel.Repository> repositories, @Nullable List<ManifestVersion> manifestVersions, boolean allowManifestDowngrades) throws OperationException, ProvisioningException {
            final List<org.wildfly.channel.Channel> overrideChannels;
            List<String> manifestVersionsStrings = null;

            try (InstallationMetadata im = InstallationMetadata.loadInstallation(server)) {
                if (manifestVersions != null && !allowManifestDowngrades) {
                    // The manifest versions to update to has been specified *and* manifest downgrades are not allowed.
                    // => Fail the operation if there is a downgrade present.
                    for (ChannelVersion channelVersion : im.getChannelVersions()) {
                        Optional<ManifestVersion> manifestVersionOptional = manifestVersions.stream()
                                .filter(mv -> mv.getChannelId().equals(channelVersion.getChannelName()))
                                .findFirst();
                        if (manifestVersionOptional.isPresent()) {
                            ManifestVersion manifestVersion = manifestVersionOptional.get();
                            if (!ManifestVersion.Type.MAVEN.equals(manifestVersion.getType())) {
                                // Only check MAVEN versions, comparing URL versions makes no sense.
                                continue;
                            }
                            String newVersion = manifestVersion.getVersion();
                            String oldVersion = channelVersion.getPhysicalVersion();
                            if (VersionMatcher.COMPARATOR.compare(newVersion, channelVersion.getPhysicalVersion()) < 0) {
                                String message = String.format("Manifest %s is being downgraded from %s to %s. Manifest downgrades need to be explicitly allowed in the command invocation.",
                                        channelVersion.getLocation(), oldVersion, newVersion);
                                throw new RuntimeException(message);
                            }
                        }
                    }
                }

                if (manifestVersions != null) {
                    manifestVersionsStrings = manifestVersions.stream()
                            .map(mv -> mv.getChannelId() + "::" + mv.getVersion())
                            .toList();
                }

                overrideChannels = OverrideBuilder
                        .from(im.getProsperoConfig().getChannels())
                        .withRepositories(repositories)
                        .withManifestVersions(manifestVersionsStrings)
                        .build();
            }
            return new UpdateAction(server, overrideChannels, mavenOptions, null);
        }

        protected MetadataAction getMetadataAction() throws MetadataException {
            return new MetadataAction(server);
        }

        protected InstallationExportAction getInstallationExportAction() {
            return new InstallationExportAction(server);
        }

        protected ApplyCandidateAction getApplyCandidateAction(Path candidateDir) throws ProvisioningException, OperationException {
            return new ApplyCandidateAction(server, candidateDir);
        }

        org.wildfly.prospero.api.MavenOptions getMavenOptions() {
            return mavenOptions;
        }
    }
}
