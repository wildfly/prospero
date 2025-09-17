package org.wildfly.prospero.actions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jboss.galleon.Constants;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;

/**
 * A temporary record of all files modified, removed or added during applying a candidate server.
 */
class ApplyStageBackup implements AutoCloseable {

    protected static final String BACKUP_FOLDER = ".update.old";
    private final Path backupRoot;
    private final Path serverRoot;
    private final Path candidateRoot;

    /**
     * create a record for server at {@code serverRoot}. The recorded files will be stored in {@tempRoot}
     *
     * @param serverRoot - root folder of the server that will be updated
     */
    ApplyStageBackup(Path serverRoot, Path candidateRoot) throws IOException {

        this.serverRoot = serverRoot;
        this.candidateRoot = candidateRoot;
        this.backupRoot = serverRoot.resolve(BACKUP_FOLDER);

        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.debug("Creating backup record in " + backupRoot);
        }

        if (!Files.exists(backupRoot)) {
            if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                ProsperoLogger.ROOT_LOGGER.trace("Creating backup directory in " + backupRoot);
            }

            Files.createDirectories(backupRoot);
        } else if (!Files.isDirectory(backupRoot) || !Files.isWritable(backupRoot)) {
            throw new RuntimeException(String.format(
                    "Unable to create backup in %s. It is not a directory or is not writable.",
                    backupRoot
            ));
        }

        final File[] files = backupRoot.toFile().listFiles();
        if (files != null) {
            for (File file : files) {
                if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                    ProsperoLogger.ROOT_LOGGER.trace("Removing existing backup files: " + file);
                }

                FileUtils.forceDelete(file);
            }
        }
    }

    /**
     * add all the server-managed files in the server to cache
     *
     * @throws IOException - if unable to backup the files
     */
    public void recordAll() throws IOException {
        ProsperoLogger.ROOT_LOGGER.debug("Starting building the update backup.");

        // if the server doesn't conatain a galleon provisioning record, skip building the backup
        final Path hashesRoot = serverRoot.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.HASHES);
        if (!Files.exists(hashesRoot)) {
            ProsperoLogger.ROOT_LOGGER.warn("Unable to perform the backup: No Galleon Hashes record found.");
            return;
        }


        // walk the hashes to record server-managed files while ignoring non-managed files
        final GalleonHashesFileWalker serverFS = new GalleonHashesFileWalker(serverRoot) {
            @Override
            void visitFile(Path file) throws IOException {
                final Path serverPath = serverRoot.resolve(file);
                final Path backupPath = backupRoot.resolve(file);

                if (!Files.exists(serverPath)) {
                    ProsperoLogger.ROOT_LOGGER.debug("Unable to find managed file: " + serverPath + ". File backup skipped.");
                } else {
                    backupFile(serverPath, backupPath);
                }
            }

            @Override
            void visitDirectory(Path relative) throws IOException {
                ProsperoLogger.ROOT_LOGGER.tracef("Creating a directory in the backup folder: %s", relative);
                Files.createDirectories(backupRoot.resolve(relative));
            }
        };
        serverFS.walk();


        // we need to walk the candidate tree as well and find files that might overwrite existing user files
        ProsperoLogger.ROOT_LOGGER.trace("Checking candidate folder for overwriting files.");
        final GalleonHashesFileWalker candidateFS = new GalleonHashesFileWalker(candidateRoot) {
            @Override
            void visitFile(Path file) throws IOException {
                // if the file exists in the candidate folder, and it exists in the server folder,
                // but is not backed up yet, it means there is a user modification that would be overwritten
                // in this case we're going to check if the permissions match and if they do we'll copy the backup
                // if not we're going to throw an exception and let user handle it

                final Path serverFile = serverRoot
                        .resolve(file);
                final Path backupFile = backupRoot
                        .resolve(file);

                if (Files.exists(serverFile) && !Files.exists(backupFile)) {
                    // the server file has to be read and write-able by the current user
                    if (!Files.isReadable(serverFile) || !Files.isWritable(serverFile)) {
                        throw new RuntimeException("The update is unable to modify file " + serverFile + " due to invalid file permissions.");
                    }

                    if (!Files.exists(backupFile.getParent())) {
                        ProsperoLogger.ROOT_LOGGER.tracef("Creating added directory based on the candidate folder:%s.", backupFile);
                        Files.createDirectories(backupFile.getParent());
                    }

                    backupFile(serverFile, backupFile);
                }
            }

            @Override
            void visitDirectory(Path dir) throws IOException {

            }
        };
        candidateFS.walk();

        // copy .installation and .hashes folders as they are
        if (Files.exists(serverRoot.resolve(Constants.PROVISIONED_STATE_DIR))) {
            ProsperoLogger.ROOT_LOGGER.trace("Copying the Galleon provisioned state directory.");
            FileUtils.copyDirectory(serverRoot.resolve(Constants.PROVISIONED_STATE_DIR).toFile(), backupRoot.resolve(Constants.PROVISIONED_STATE_DIR).toFile());
        }
        if (Files.exists(serverRoot.resolve(ProsperoMetadataUtils.METADATA_DIR))) {
            ProsperoLogger.ROOT_LOGGER.trace("Copying the Prospero installation directory.");
            FileUtils.copyDirectory(serverRoot.resolve(ProsperoMetadataUtils.METADATA_DIR).toFile(), backupRoot.resolve(ProsperoMetadataUtils.METADATA_DIR).toFile());
        }

        ProsperoLogger.ROOT_LOGGER.debug("Finished building the update backup.");
    }

    private static void backupFile(Path serverPath, Path backupPath) throws IOException {
        // we try to use hardlinks instead of copy to save disk space
        // fallback on copy if Filesystem doesn't support hardlinks
        ProsperoLogger.ROOT_LOGGER.tracef("Backing up file %s to %s.", serverPath, backupPath);

        try {
            Files.createLink(backupPath, serverPath);
        } catch (UnsupportedOperationException | FileSystemException e) {
            Files.copy(serverPath, backupPath);
        }
    }

    /**
     * clean up the cache
     */
    @Override
    public void close() {
        FileUtils.deleteQuietly(backupRoot.toFile());
    }

    /**
     * restore files and directories in {@code targetServer} to the recorded values.
     *
     * @throws IOException - if unable to perform operations of the filesystem
     */
    public void restore() throws IOException {
        if (backupRoot.toFile().listFiles() == null || backupRoot.toFile().listFiles().length == 0) {
            return;
        }

        if (ProsperoLogger.ROOT_LOGGER.isDebugEnabled()) {
            ProsperoLogger.ROOT_LOGGER.debug("Restoring server from the backup.");
        }

        if (!Files.exists(backupRoot)) {
            throw new RuntimeException("Backup root doesn't exist.");
        }

        // copy backed-up files back into the server
        Files.walkFileTree(backupRoot, restoreModifiedFiles());

        // remove all files added to recorded folders that were not handled by addedFiles
        Files.walkFileTree(serverRoot, deleteNewFiles());
    }

    private SimpleFileVisitor<Path> deleteNewFiles() {
        return new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return ignoreUserManagedFiles(file, exc);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isUserProtectedFile(file)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                final Path relativePath = serverRoot.relativize(file);
                // remove it only if it exists in the candidate but doesn't exist in the backup
                // note that doesn't handle the case of a pre-existing file being overwritten by an update...
                // in this case I think we need to compare the candidate before the update starts. Or record each replaced file separately
                if (!Files.exists(backupRoot.resolve(relativePath)) && Files.exists(candidateRoot.resolve(relativePath))) {
                    if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.trace("Removing added file " + relativePath);
                    }

                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                final Path relativePath = serverRoot.relativize(dir);
                if (!Files.exists(backupRoot.resolve(relativePath)) && Files.exists(candidateRoot.resolve(relativePath))) {
                    if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.trace("Removing added directory " + relativePath);
                    }

                    Files.delete(dir);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(backupRoot) || dir.equals(serverRoot.resolve(Constants.PROVISIONED_STATE_DIR))
                        || dir.equals(serverRoot.resolve(ProsperoMetadataUtils.METADATA_DIR))) {
                    return FileVisitResult.SKIP_SUBTREE;
                } else {
                    return FileVisitResult.CONTINUE;
                }
            }
        };
    }

    private FileVisitResult ignoreUserManagedFiles(Path file, IOException exc) throws IOException {
        // if the file in the installation is not readable AND it does not exist in the candidate
        // we assume it is user controlled file and we ignore it
        if (isUserProtectedFile(file)) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        throw exc;
    }

    private boolean isUserProtectedFile(Path file) {
        final Path relative = serverRoot.relativize(file);
        final Path candidatePath = candidateRoot.resolve(relative);
        return !Files.isReadable(file) && !Files.exists(candidatePath);
    }

    private SimpleFileVisitor<Path> restoreModifiedFiles() {
        return new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                final Path relativePath = backupRoot.relativize(file);

                final Path parentDir = relativePath.getParent();
                if (parentDir != null && !Files.exists(serverRoot.resolve(parentDir))) {
                    if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.trace("Recreating removed directory " + parentDir);
                    }

                    Files.createDirectories(serverRoot.resolve(parentDir));
                }

                final Path targetFile = serverRoot.resolve(relativePath);
                if (fileChanged(file, targetFile)) {
                    if (ProsperoLogger.ROOT_LOGGER.isTraceEnabled()) {
                        ProsperoLogger.ROOT_LOGGER.trace("Restoring changed file " + relativePath);
                    }

                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        };
    }

    private static boolean fileChanged(Path file, Path targetFile) throws IOException {
        if (!Files.exists(targetFile)) {
            return true;
        }

        try (FileInputStream fis1 = new FileInputStream(targetFile.toFile());
             FileInputStream fis2 = new FileInputStream(file.toFile())){
            return !IOUtils.contentEquals(fis1, fis2);
        }
    }
}
