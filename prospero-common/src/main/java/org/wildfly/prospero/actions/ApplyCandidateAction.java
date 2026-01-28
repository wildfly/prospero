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
package org.wildfly.prospero.actions;

import static org.jboss.galleon.diff.FsDiff.ADDED;
import static org.jboss.galleon.diff.FsDiff.CONFLICT;
import static org.jboss.galleon.diff.FsDiff.CONFLICTS_WITH_THE_UPDATED_VERSION;
import static org.jboss.galleon.diff.FsDiff.FORCED;
import static org.jboss.galleon.diff.FsDiff.HAS_BEEN_REMOVED_FROM_THE_UPDATED_VERSION;
import static org.jboss.galleon.diff.FsDiff.HAS_CHANGED_IN_THE_UPDATED_VERSION;
import static org.jboss.galleon.diff.FsDiff.MODIFIED;
import static org.jboss.galleon.diff.FsDiff.REMOVED;
import static org.jboss.galleon.diff.FsDiff.formatMessage;
import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.CURRENT_VERSION_FILE;
import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.METADATA_DIR;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.artifact.Artifact;
import org.jboss.galleon.BaseErrors;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.diff.FsDiff;
import org.jboss.galleon.diff.FsEntry;
import org.jboss.galleon.layout.SystemPaths;
import org.jboss.galleon.util.HashUtils;
import org.jboss.galleon.util.IoUtils;
import org.jboss.galleon.util.PathsUtils;
import org.jboss.logging.Logger;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.FileConflict;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.exceptions.ApplyCandidateException;
import org.wildfly.prospero.api.exceptions.InvalidUpdateCandidateException;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.ArtifactCache;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.installation.git.GitStorage;
import org.wildfly.prospero.licenses.LicenseManager;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.updates.CandidateProperties;
import org.wildfly.prospero.updates.CandidatePropertiesParser;
import org.wildfly.prospero.updates.MarkerFile;
import org.wildfly.prospero.updates.UpdateSet;

/**
 * Merges a "candidate" server into base server. The "candidate" can be an update or revert.
 */
@SuppressWarnings("PMD.TooManyStaticImports")
public class ApplyCandidateAction {
    public static final Path STANDALONE_STARTUP_MARKER = Path.of("standalone", "tmp", "startup-marker");
    public static final Path DOMAIN_STARTUP_MARKER = Path.of("domain", "tmp", "startup-marker");
    public static final String CANDIDATE_CHANNEL_NAME_LIST = "candidate_properties.yaml";
    private final Path updateDir;
    private final Path installationDir;
    private final SystemPaths systemPaths;

    private static final Logger log = Logger.getLogger(ApplyCandidateAction.class);

    public enum Type {
        UPDATE("UPDATE"), REVERT("REVERT"), FEATURE_ADD("FEATURE_ADD");

        private final String text;

        Type(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public static Type from (final String text) {
            switch (text) {
                case "UPDATE":
                    return ApplyCandidateAction.Type.UPDATE;
                case "REVERT":
                    return ApplyCandidateAction.Type.REVERT;
                case "FEATURE_ADD":
                    return ApplyCandidateAction.Type.FEATURE_ADD;
                default:
                    throw ProsperoLogger.ROOT_LOGGER.invalidMarkerFileOperation(text);
            }
        }
    }

    public ApplyCandidateAction(Path installationDir, Path updateDir)
            throws ProvisioningException, OperationException {
        this.updateDir = InstallFolderUtils.toRealPath(updateDir);
        this.installationDir = InstallFolderUtils.toRealPath(installationDir);

        try {
            this.systemPaths = SystemPaths.load(this.updateDir);
        } catch (IOException ex) {
            throw new ProvisioningException(ex);
        }
        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.debug("System paths " + this.systemPaths.getPaths());
        }
    }

    /**
     * Applies changes from prepare update at {@code updateDir} to {@code installationDir}. The update candidate has to
     * contain a marker file {@code .installation/.update.txt}.
     * <p>
     * If the Operation is a Revert, the content of the installation dir is compared with the content of the updated dir
     * to verify there are changes where revert to. This content check is done using .galleon/hashes files and
     * ./installation/installer-channels.yaml file.
     * <p>
     * Any update files from {@code updateDir} are copied to {@code installationDir}. If any of the updates
     * (apart from {@code system-paths}) conflict with user changes, the user changes are preserved and the updated file
     * is added with {@code'.glnew'} suffix.
     *
     *
     * @return list of solved {@code FileConflict}s
     * @throws ProvisioningException - if unable to apply the changes from {@code updateDir} to {@code installationDir}
     * @throws InvalidUpdateCandidateException - if the folder at {@code updateDir} is not a valid update
     * @throws MetadataException - if unable to read or write the installation of update metadata
     */
    public List<FileConflict> applyUpdate(Type operation) throws ProvisioningException, OperationException {
        ValidationResult validationResult = verifyCandidate(operation);
        if (operation == Type.REVERT && ValidationResult.NO_CHANGES == validationResult) {
            final InvalidUpdateCandidateException ex = ProsperoLogger.ROOT_LOGGER.noChangesAvailable(updateDir, installationDir);
            ProsperoLogger.ROOT_LOGGER.warn("", ex);
            throw ex;
        }

        if (ValidationResult.OK != validationResult) {
            final InvalidUpdateCandidateException ex = ProsperoLogger.ROOT_LOGGER.invalidUpdateCandidate(updateDir, installationDir);
            ProsperoLogger.ROOT_LOGGER.warn("", ex);
            throw ex;
        }

        if (targetServerIsRunning()) {
            final ProvisioningException ex = ProsperoLogger.ROOT_LOGGER.serverRunningError();
            ProsperoLogger.ROOT_LOGGER.warn("", ex);
            throw ex;
        }

        final FsDiff diffs = GalleonUtils.findChanges(installationDir);
        ApplyStageBackup backup = null;
        try {
            backup = new ApplyStageBackup(installationDir, updateDir);
            backup.recordAll();

            ProsperoLogger.ROOT_LOGGER.debug("Update backup generated in " + installationDir.resolve(ApplyStageBackup.BACKUP_FOLDER));

            ProsperoLogger.ROOT_LOGGER.applyingCandidate(operation.text.toLowerCase(Locale.ROOT), updateDir);
            ProsperoLogger.ROOT_LOGGER.candidateChanges(
                    findUpdates().getArtifactUpdates().stream().map(ArtifactChange::prettyPrint).collect(Collectors.joining("; "))
            );

            final List<FileConflict> conflicts = doApplyUpdate(diffs);

            if (conflicts.isEmpty()) {
                ProsperoLogger.ROOT_LOGGER.noCandidateConflicts();
            } else {
                ProsperoLogger.ROOT_LOGGER.candidateConflicts(
                        conflicts.stream().map(FileConflict::prettyPrint).collect(Collectors.joining("; "))
                );
                for (FileConflict conflict : conflicts) {
                    ProsperoLogger.ROOT_LOGGER.info(conflict.prettyPrint());
                }
            }

            updateMetadata(operation);
            ProsperoLogger.ROOT_LOGGER.candidateApplied(operation.text, installationDir);

            // remove the backup if the apply operation was successful
            backup.close();
            return conflicts;
        } catch (IOException ex) {
            boolean backupRestored = false;
            try {
                if (backup != null) {
                    backup.restore();
                    // remove close the backup if the restore was successful. If there were any errors, we want to keep the backup untouched.
                    backup.close();
                    backupRestored = true;
                }
            } catch (IOException e) {
                ProsperoLogger.ROOT_LOGGER.error("Unable to restore the server from a backup, preserving the backup.", e);
            }
            final String msg = ex.getLocalizedMessage() == null ? ex.getMessage() : ex.getLocalizedMessage();
            throw new ApplyCandidateException(ProsperoLogger.ROOT_LOGGER.failedToApplyCandidate(msg),
                    backupRestored, installationDir.resolve(ApplyStageBackup.BACKUP_FOLDER), ex);
        }
    }

    public enum ValidationResult {
        OK, NOT_CANDIDATE, STALE, WRONG_TYPE, NO_CHANGES;
    }

    /**
     * checks that the candidate is an update of a current state of installation
     *
     * @return ValidationResult that represents the result of the verification
     * @throws MetadataException - if the metadata of candidate or installation cannot be read
     */
    public ValidationResult verifyCandidate(Type operation) throws MetadataException {
        final Path updateMarkerPath = updateDir.resolve(MarkerFile.UPDATE_MARKER_FILE);
        if (!Files.exists(updateMarkerPath)) {
            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debugf("The candidate [%s] doesn't have a marker file", updateDir);
            }
            return ValidationResult.NOT_CANDIDATE;
        }

        final MarkerFile marker;
        try {
            marker = MarkerFile.read(updateDir);
            final String hash = marker.getState();
            try(InstallationMetadata metadata = InstallationMetadata.loadInstallation(installationDir)) {
                if (!metadata.getRevisions().get(0).getName().equals(hash)) {
                    if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.debugf("The installation state has changed from the candidate [%s].", updateDir);
                    }
                    return ValidationResult.STALE;
                }
            }
        } catch (IOException e) {
            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debugf("Unable to read marker file [%s].", updateDir);
            }
            throw ProsperoLogger.ROOT_LOGGER.unableToReadFile(updateMarkerPath, e);
        }

        if (marker.getOperation() != operation) {
            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debugf("The candidate server has been prepared for different operation [%s].", marker.getOperation().getText());
            }
            return ValidationResult.WRONG_TYPE;
        }

        try {
            if (operation == Type.REVERT && compareContent(installationDir, updateDir)) {
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debugf(
                            "There are no changes to apply to the installation [%s] from the candidate installation [%s].",
                            installationDir, updateDir);
                }
                return ValidationResult.NO_CHANGES;
            }
        } catch (IOException e) {
            if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                ProsperoLogger.ROOT_LOGGER.debugf("IO Error comparing [%s] and [%s] hashes content.", installationDir, updateDir);
            }
            throw ProsperoLogger.ROOT_LOGGER.unableToCompareHashDirs(installationDir, updateDir, e);
        }

        return ValidationResult.OK;
    }

    /**
     * list conflicts between the candidate ({@code installationDir} and target server {@code updateDir}.
     *
     *
     * @return list of {@code FileConflict} or empty list if no conflicts found.
     * @throws ProvisioningException
     * @throws OperationException
     */
    public List<FileConflict> getConflicts() throws ProvisioningException, OperationException {
        try {
            return compareServers(GalleonUtils.findChanges(installationDir));
        } catch (IOException ex) {
            throw new ProvisioningException(ex);
        }
    }

    public boolean removeCandidate(File updateDir) {
        File[] allContents = updateDir.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                removeCandidate(file);
            }
        }
        return updateDir.delete();
    }

    /**
     * list artifacts changed between base and candidate servers.
     *
     * @return list of changes
     * @throws OperationException
     */
    public UpdateSet findUpdates() throws OperationException {
        final Map<String, Artifact> baseMap = new HashMap<>();
        final Map<String, Artifact> candidateMap = new HashMap<>();
        final List<Artifact> base;
        final List<Artifact> candidate;

        try (InstallationMetadata metadata = InstallationMetadata.loadInstallation(installationDir)) {
            base = metadata.getArtifacts();
        }
        try (InstallationMetadata metadata = InstallationMetadata.loadInstallation(updateDir)) {
            candidate = metadata.getArtifacts();
        }

        for (Artifact artifact : base) {
            baseMap.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact);
        }
        for (Artifact artifact : candidate) {
            candidateMap.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact);
        }
        List<ArtifactChange> changes = new ArrayList<>();

        final CandidateProperties candidateProperties = readCandidateProperties();

        for (String key : baseMap.keySet()) {
            if (candidateMap.containsKey(key)) {
                if (!baseMap.get(key).getVersion().equals(candidateMap.get(key).getVersion())) {
                    final String updateChannelName = candidateProperties.getUpdateChannel(key);
                    changes.add(ArtifactChange.updated(baseMap.get(key), candidateMap.get(key), updateChannelName));
                }
            } else {
                changes.add(ArtifactChange.removed(baseMap.get(key)));
            }
        }

        for (String key : candidateMap.keySet()) {
            if (!baseMap.containsKey(key)) {
                changes.add(ArtifactChange.added(candidateMap.get(key)));
            }
        }

        return new UpdateSet(changes);
    }

    private CandidateProperties readCandidateProperties() {
        final Path candidatePropertiesPath = updateDir
                .resolve(METADATA_DIR).resolve(CANDIDATE_CHANNEL_NAME_LIST);
        if (Files.exists(candidatePropertiesPath)) {
            try {
                return CandidatePropertiesParser.read(candidatePropertiesPath);
            } catch (IOException | MetadataException e) {
                ProsperoLogger.ROOT_LOGGER.unableToReadChannelNames(candidatePropertiesPath.toString(), e);
            }
        }

        // return default properties if not able to read the file
        return new CandidateProperties(Collections.emptyList());
    }

    /**
     * returns the revision of the candidate server
     * @return {@code SavedState}
     * @throws MetadataException - if unable to read the candidate server metadata
     */
    public SavedState getCandidateRevision() throws MetadataException {
        try (InstallationMetadata metadata = InstallationMetadata.loadInstallation(updateDir)) {
            return metadata.getRevisions().get(0);
        }
    }

    private boolean targetServerIsRunning() {
        return Files.exists(installationDir.resolve(STANDALONE_STARTUP_MARKER)) || Files.exists(installationDir.resolve(DOMAIN_STARTUP_MARKER));
    }

    private void updateMetadata(Type operation) throws IOException, MetadataException {
        // add all files in .installation folder to the backup set
        copyCurrentVersions();
        Path installationGalleonPath = PathsUtils.getProvisionedStateDir(installationDir);
        Path updateGalleonPath = PathsUtils.getProvisionedStateDir(updateDir);
        // add all files in .galleon folder to the backup set
        IoUtils.recursiveDelete(installationGalleonPath);
        IoUtils.copy(updateGalleonPath, installationGalleonPath, true);
        // after the galleon data is copied, persist a copy of provisioning.xml and record it
        ProsperoMetadataUtils.recordProvisioningDefinition(installationDir);
        writeProsperoMetadata(operation);
        updateInstallationCache();
        updateAcceptedLicences();
    }

    private void updateAcceptedLicences() throws MetadataException {
        try {
            new LicenseManager().copyIfExists(updateDir, installationDir);
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToWriteFile(installationDir.resolve(LicenseManager.LICENSES_FOLDER), e);
        }
    }

    private void copyCurrentVersions() throws IOException {
        Path sourceVersions = updateDir.resolve(METADATA_DIR).resolve(CURRENT_VERSION_FILE);
        if (Files.exists(sourceVersions)) {
            Files.copy(sourceVersions, installationDir.resolve(METADATA_DIR).resolve(CURRENT_VERSION_FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeProsperoMetadata(Type operation) throws MetadataException, IOException {
        Path updateMetadataDir = updateDir.resolve(METADATA_DIR);
        Path updateManifest = updateMetadataDir.resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME);

        Path installationMetadataDir = installationDir.resolve(METADATA_DIR);
        Path installationManifest = installationMetadataDir.resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME);
        copyFiles(updateManifest, installationManifest);

        try (GitStorage git = new GitStorage(installationDir)) {
            switch (operation) {
                case UPDATE:
                    git.recordChange(SavedState.Type.UPDATE);
                    break;
                case REVERT:
                    git.recordChange(SavedState.Type.ROLLBACK);

                    final Path updateChannels = updateMetadataDir.resolve(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME);
                    final Path installationChannels = installationMetadataDir.resolve(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME);
                    copyFiles(updateChannels, installationChannels);

                    break;
                case FEATURE_ADD:
                    git.recordChange(SavedState.Type.FEATURE_PACK);
                    break;
            }
        }
    }

    private static void copyFiles(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            // need to remove the existing file, because we use a hardlink to provide a backup
            FileUtils.deleteQuietly(target.toFile());
        }
        IoUtils.copy(source, target);
    }

    private void updateInstallationCache() throws IOException {
        Path updateCacheDir = updateDir.resolve(ArtifactCache.CACHE_FOLDER);


        Path installationCacheDir = installationDir.resolve(ArtifactCache.CACHE_FOLDER);
        if (Files.exists(installationCacheDir)) {
            IoUtils.recursiveDelete(installationCacheDir);
        }
        if (Files.exists(updateCacheDir)) {
            copyFiles(updateCacheDir, installationCacheDir);
        }
    }

    private List<FileConflict> handleRemovedFiles(FsDiff fsDiff) throws IOException {
        final List<FileConflict> conflictList = new ArrayList<>();
        if (fsDiff.hasRemovedEntries()) {
            for (FsEntry removed : fsDiff.getRemovedEntries()) {
                final Path target = updateDir.resolve(removed.getRelativePath());
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debug(formatMessage(REMOVED, removed.getRelativePath(), null));
                }
                if (Files.exists(target)) {
                    if (systemPaths.isSystemPath(Paths.get(removed.getRelativePath()))) {
                        conflictList.add(FileConflict.userRemoved(removed.getRelativePath()).updateModified().overwritten());
                        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                            ProsperoLogger.ROOT_LOGGER.debug(formatMessage(FORCED, removed.getRelativePath(), HAS_CHANGED_IN_THE_UPDATED_VERSION));
                        }
                    }
                } else {
                    if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.debug(formatMessage(REMOVED, removed.getRelativePath(),
                                HAS_BEEN_REMOVED_FROM_THE_UPDATED_VERSION));
                    }
                }
            }
        }
        return conflictList;
    }

    private List<FileConflict> handleAddedFiles(FsDiff fsDiff) throws IOException, ProvisioningException {
        final List<FileConflict> conflictList = new ArrayList<>();
        if (fsDiff.hasAddedEntries()) {
            for (FsEntry added : fsDiff.getAddedEntries()) {
                Path p = Paths.get(added.getRelativePath());
                // Ignore .installation owned by prospero
                if (p.getNameCount() > 0 && p.getName(0).toString().equals(METADATA_DIR)) {
                    continue;
                }
                addFsEntry(updateDir, added, systemPaths, conflictList);
            }
        }
        return conflictList;
    }

    private void addFsEntry(Path updateDir, FsEntry added, SystemPaths systemPaths,
                            List<FileConflict> conflictList)
            throws ProvisioningException {
        final Path target = updateDir.resolve(added.getRelativePath());
        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.debug(formatMessage(ADDED, added.getRelativePath(), null));
        }
        if (Files.exists(target)) {
            if (added.isDir()) {
                for (FsEntry child : added.getChildren()) {
                    addFsEntry(updateDir, child, systemPaths, conflictList);
                }
                return;
            }
            final byte[] targetHash;
            try {
                targetHash = HashUtils.hashPath(target);
            } catch (IOException e) {
                throw new ProvisioningException(BaseErrors.hashCalculation(target), e);
            }

            if (Arrays.equals(added.getHash(), targetHash)) {
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debug(formatMessage(ADDED, added.getRelativePath(), "Added file matches the update."));
                }
            } else {
                if (systemPaths.isSystemPath(Paths.get(added.getRelativePath()))) {
                    if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.debug(formatMessage(FORCED, added.getRelativePath(), CONFLICTS_WITH_THE_UPDATED_VERSION));
                    }
                    conflictList.add(FileConflict.userAdded(added.getRelativePath()).updateAdded().overwritten());
                } else {
                    if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.debug(formatMessage(CONFLICT, added.getRelativePath(), CONFLICTS_WITH_THE_UPDATED_VERSION));
                    }
                    conflictList.add(FileConflict.userAdded(added.getRelativePath()).updateAdded().userPreserved());
                }
            }
        }
    }

    private List<FileConflict> handleModifiedFiles(FsDiff fsDiff) throws IOException, ProvisioningException {
        final List<FileConflict> conflictList = new ArrayList<>();
        if (fsDiff.hasModifiedEntries()) {
            for (FsEntry[] modified : fsDiff.getModifiedEntries()) {
                FsEntry installation = modified[1];
                FsEntry original = modified[0];
                final Path file = updateDir.resolve(modified[1].getRelativePath());
                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.debug(formatMessage(MODIFIED, installation.getRelativePath(), null));
                }
                if (Files.exists(file)) {
                    byte[] updateHash;
                    try {
                        updateHash = HashUtils.hashPath(file);
                    } catch (IOException e) {
                        throw new ProvisioningException(BaseErrors.hashCalculation(file), e);
                    }
                    // Case where the modified file is equal to the hash of the update. Do nothing
                    if (Arrays.equals(installation.getHash(), updateHash)) {
                        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                            ProsperoLogger.ROOT_LOGGER.debug(formatMessage(MODIFIED, installation.getRelativePath(), "Modified file matches the update"));
                        }
                    } else {
                        if (!Arrays.equals(original.getHash(), updateHash)) {
                            if (systemPaths.isSystemPath(Paths.get(installation.getRelativePath()))) {
                                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                                    ProsperoLogger.ROOT_LOGGER.debug(formatMessage(FORCED, installation.getRelativePath(), HAS_CHANGED_IN_THE_UPDATED_VERSION));
                                }
                                conflictList.add(FileConflict.userModified(installation.getRelativePath()).updateModified().overwritten());
                            } else {
                                if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                                    ProsperoLogger.ROOT_LOGGER.debug(formatMessage(CONFLICT, installation.getRelativePath(), HAS_CHANGED_IN_THE_UPDATED_VERSION));
                                }
                                conflictList.add(FileConflict.userModified(installation.getRelativePath()).updateModified().userPreserved());
                            }
                        }
                    }
                } else {
                    // The file doesn't exist in the update, we keep the file in the installation
                    if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.debug(formatMessage(MODIFIED, installation.getRelativePath(), HAS_BEEN_REMOVED_FROM_THE_UPDATED_VERSION));
                    }
                    conflictList.add(FileConflict.userModified(installation.getRelativePath()).updateRemoved().userPreserved());
                }
            }
        }
        return conflictList;
    }

    private List<FileConflict> compareServers(FsDiff fsDiff) throws IOException, ProvisioningException {
        List<FileConflict> conflicts = new ArrayList<>();
        // Handles user added/removed/modified files
        conflicts.addAll(handleRemovedFiles(fsDiff));
        conflicts.addAll(handleAddedFiles(fsDiff));
        conflicts.addAll(handleModifiedFiles(fsDiff));
        return Collections.unmodifiableList(conflicts);
    }

    public void deleteWithStreams(Path path) throws IOException {
        Files.walk(path).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }

    private List<FileConflict> doApplyUpdate(FsDiff fsDiff) throws IOException, ProvisioningException {
        List<FileConflict> conflicts = new ArrayList<>();
        // Handles user added/removed/modified files
        conflicts.addAll(handleRemovedFiles(fsDiff));
        conflicts.addAll(handleAddedFiles(fsDiff));
        conflicts.addAll(handleModifiedFiles(fsDiff));

        resolveFileConflicts(conflicts);

        // Handles files added/removed/modified in the update.
        Path skipUpdateGalleon = PathsUtils.getProvisionedStateDir(updateDir);
        Path skipUpdateInstallation = updateDir.resolve(METADATA_DIR);
        Path skipInstallationGalleon = PathsUtils.getProvisionedStateDir(installationDir);
        Path skipInstallationInstallation = installationDir.resolve(METADATA_DIR);

        // Copy the new/modified files that the update brings that are not in the installation and not removed/modified by the user.
        Files.walkFileTree(updateDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path relative = updateDir.relativize(file);
                Path installationFile = installationDir.resolve(relative);
                // Not a file added or modified by the user
                final String pathKey = getFsDiffKey(relative, false);
                if (fsDiff.getModifiedEntry(pathKey) == null &&
                        fsDiff.getAddedEntry(pathKey) == null && !isParentAdded(fsDiff, relative)) {
                    byte[] updateHash = HashUtils.hashPath(file);
                    // The file could be new or updated in the installation
                    if (!Files.exists(installationFile) || !Arrays.equals(updateHash, HashUtils.hashPath(installationFile))) {
                        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                            ProsperoLogger.ROOT_LOGGER.debug("Copying updated file " + relative + " to the installation");
                        }
                        copyFiles(file, installationFile);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            private boolean isParentAdded(FsDiff fsDiff, Path relative) {
                Path parent = relative.getParent();
                while (parent != null) {
                    // FsDiff always uses UNIX separators
                    if (fsDiff.getAddedEntry(parent + "/") != null) {
                        return true;
                    }
                    parent = parent.getParent();
                }
                return false;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (dir.equals(skipUpdateGalleon) || dir.equals(skipUpdateInstallation)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e)
                    throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });

        // Delete the files in the installation that are not present in the update and not added by the user
        // We need to skip .glnew and .glold.
        // JBEAP-30585: We need to keep the Symbolic files
        Files.walkFileTree(installationDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path relative = installationDir.relativize(file);
                Path updateFile = updateDir.resolve(relative);
                final String fsDiffKey = getFsDiffKey(relative, false);
                if (isNotAddedOrModified(fsDiffKey, fsDiff) && fileNotPresent(updateFile) && !Files.isSymbolicLink(file)) {
                    if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.debug("Deleting the file " + relative + " that doesn't exist in the update");
                    }
                    try {
                        deleteWithStreams(file);
                    } catch (Exception e2) {
                        ProsperoLogger.ROOT_LOGGER.debug("Problem during delete the file: " + relative + " with: " + e2.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            private boolean fileNotPresent(Path updateFile) {
                return !Files.exists(updateFile) &&
                        !updateFile.toString().endsWith(Constants.DOT_GLNEW) &&
                        !updateFile.toString().endsWith(Constants.DOT_GLOLD);
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(skipInstallationGalleon) || dir.equals(skipInstallationInstallation) || dir.equals(installationDir.resolve(ApplyStageBackup.BACKUP_FOLDER))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!Files.isReadable(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!Files.isReadable(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!dir.equals(installationDir)) {
                    Path relative = installationDir.relativize(dir);
                    Path target = updateDir.resolve(relative);
                    String pathKey = getFsDiffKey(relative, true);
                    if (isAdded(pathKey, fsDiff) && !Files.exists(target)) {
                        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                            ProsperoLogger.ROOT_LOGGER.debug("The directory " + relative + " that doesn't exist in the update is a User changes, skipping it");
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (!dir.equals(installationDir)) {
                    Path relative = installationDir.relativize(dir);
                    Path target = updateDir.resolve(relative);
                    String pathKey = getFsDiffKey(relative, true);
                    if (!isAdded(pathKey, fsDiff) && !Files.exists(target) && isEmpty(dir)) {
                        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
                            ProsperoLogger.ROOT_LOGGER.debug("Deleting the directory " + relative + " that doesn't exist in the update");
                        }
                        IoUtils.recursiveDelete(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (exc instanceof AccessDeniedException) {
                    Path relative = installationDir.relativize(file);
                    Path target = updateDir.resolve(relative);
                    // TODO: check it's not in the Galleon hashes
                    if (Files.exists(target)) {
                        throw exc;
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                } else {
                    throw exc;
                }
            }
        });
        return Collections.unmodifiableList(conflicts);
    }

    private void resolveFileConflicts(List<FileConflict> conflicts) throws IOException, ProvisioningException {
        // apply conflict resolution
        for (FileConflict conflict : conflicts) {
            final Path target = updateDir.resolve(conflict.getRelativePath());
            final Path current = installationDir.resolve(conflict.getRelativePath());
            if (conflict.getUserChange() == FileConflict.Change.REMOVED && conflict.getResolution() == FileConflict.Resolution.UPDATE) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Resolving file conflict: restoring files removed by the user: " + conflict);
                }
                Files.createDirectories(current.getParent());
                IoUtils.copy(target, current);
            } else if (conflict.getUpdateChange() == FileConflict.Change.ADDED && conflict.getResolution() == FileConflict.Resolution.UPDATE) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Resolving file conflict: backing up user changes and applying update changes: " + conflict);
                }
                glold(current, target);
            } else if (conflict.getUpdateChange() == FileConflict.Change.ADDED && conflict.getResolution() == FileConflict.Resolution.USER) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Resolving file conflict: preserving user changes and backing up update changes: " + conflict);
                }
                glnew(target, current);
            } else if (conflict.getUpdateChange() == FileConflict.Change.MODIFIED && conflict.getResolution() == FileConflict.Resolution.UPDATE) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Resolving file conflict: backing up user changes and applying update changes: " + conflict);
                }
                glold(current, target);
            } else if (conflict.getUpdateChange() == FileConflict.Change.MODIFIED && conflict.getResolution() == FileConflict.Resolution.USER) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Resolving file conflict: preserving user changes and backing up update changes: " + conflict);
                }
                glnew(target, current);
            } else {
                ProsperoLogger.ROOT_LOGGER.debug("Unknown conflict type: " + conflict);
            }
        }
    }

    private static boolean isEmpty(Path dir) {
        final String[] children = dir.toFile().list();
        if (children == null) {
            throw new RuntimeException("Unable to list children of " + dir);
        }
        return children.length == 0;
    }

    private static boolean isAdded(String pathKey, FsDiff fsDiff) {
        return fsDiff.getAddedEntry(pathKey) != null;
    }

    private static boolean isNotAddedOrModified(String fsDiffKey, FsDiff fsDiff) {
        return !isAdded(fsDiffKey, fsDiff) && fsDiff.getModifiedEntry(fsDiffKey) == null;
    }

    private String getFsDiffKey(Path relative, boolean appendSeparator) {
        String pathKey = relative.toString().replace(File.separator, "/");
        if (appendSeparator) {
            // FsDiff always uses UNIX separators
            pathKey = pathKey.endsWith("/") ? pathKey : pathKey + "/";
        }
        return pathKey;
    }



    private static void glnew(final Path updateFile, Path installationFile) throws ProvisioningException {
        final Path glnewFile = installationFile.getParent().resolve(installationFile.getFileName() + Constants.DOT_GLNEW);
        try {
            copyFiles(updateFile, glnewFile);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to persist " + glnewFile, e);
        }
    }

    private static void glold(Path installationFile, final Path target) throws ProvisioningException {
        final Path gloldFile = installationFile.getParent().resolve(installationFile.getFileName() + Constants.DOT_GLOLD);
        try {
            copyFiles(installationFile, gloldFile);
            copyFiles(target, installationFile);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to persist " + gloldFile, e);
        }
    }

    private static boolean compareContent(Path installationDir, Path updateDir) throws IOException {
        Path instGalleonHashPath = PathsUtils.getProvisionedStateDir(installationDir).resolve(Constants.HASHES);
        Path updateGalleonHashPath = PathsUtils.getProvisionedStateDir(updateDir).resolve(Constants.HASHES);

        Set<Path> instDirsPaths;
        try (Stream<Path> instDirs = Files.walk(instGalleonHashPath)) {
            instDirsPaths = instDirs.map(instGalleonHashPath::relativize).collect(Collectors.toUnmodifiableSet());
        }

        Set<Path> updateDirsPaths;
        try (Stream<Path> instDirs = Files.walk(updateGalleonHashPath)) {
            updateDirsPaths = instDirs.map(updateGalleonHashPath::relativize).collect(Collectors.toUnmodifiableSet());
        }

        if (instDirsPaths.size() != updateDirsPaths.size() || !instDirsPaths.containsAll(updateDirsPaths)) {
            return false;
        }

        for (Path path : instDirsPaths) {
            Path sourcePath = instGalleonHashPath.resolve(path);
            if (Files.isRegularFile(sourcePath)) {
                Path targetPath = updateGalleonHashPath.resolve(path);
                if (!FileUtils.contentEquals(sourcePath.toFile(), targetPath.toFile())) {
                    return false;
                }
            }
        }
        Path instConfPath = ProsperoMetadataUtils.configurationPath(installationDir);
        Path updatePath = ProsperoMetadataUtils.configurationPath(updateDir);

        if (!Files.exists(instConfPath) && Files.exists(updatePath)
                || Files.exists(instConfPath) && !Files.exists(updatePath)) {
            return false;
        }

        if (Files.exists(instConfPath) && Files.exists(updatePath)) {
            return FileUtils.contentEquals(instConfPath.toFile(), updatePath.toFile());
        }

        return true;
    }
}
