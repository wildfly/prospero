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

package org.wildfly.prospero.installation.git;

import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.SystemReader;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.FeatureChange;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.api.ChannelChange;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.wildfly.channel.Stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.CURRENT_VERSION_FILE;

public class GitStorage implements AutoCloseable {

    public static final String GIT_HISTORY_USER = "Wildfly Installer";
    private final Git git;
    private Path base;

    static {
        // override the SystemReader to ignore git configuration files
        final SystemReader systemReader = SystemReader.getInstance();
        SystemReader.setInstance(new NonPersistingSystemReader(systemReader));
    }

    public GitStorage(Path base) throws MetadataException {
        this.base = base.resolve(ProsperoMetadataUtils.METADATA_DIR);
        try {
            git = initGit();
        } catch (GitAPIException | IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToCreateHistoryStorage(base, e);
        }
    }

    public List<SavedState> getRevisions() throws MetadataException {
        try {
            final Iterable<RevCommit> call = git.log().call();
            List<SavedState> history = new ArrayList<>();
            for (RevCommit revCommit : call) {
                final String shortMessage = revCommit.getShortMessage().trim();
                final int endOfTypeIndex = shortMessage.indexOf(' ');
                final String type;
                String msg;
                if (endOfTypeIndex < 0) {
                    type = shortMessage;
                    msg = "";
                } else {
                    type = shortMessage.substring(0, endOfTypeIndex).trim();
                    msg = shortMessage.substring(endOfTypeIndex + 1).trim();

                }
                final SavedState.Type recordType = SavedState.Type.fromText(type.toUpperCase(Locale.ROOT));
                if (recordType == SavedState.Type.INTERNAL_UPDATE) {
                    // hide internal records
                    continue;
                }
                if (recordType == SavedState.Type.UNKNOWN) {
                    msg = shortMessage;
                }
                history.add(new SavedState(revCommit.getName().substring(0,8),
                        Instant.ofEpochSecond(revCommit.getCommitTime()),
                        recordType, msg));
            }

            return history;
        } catch (GitAPIException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToAccessHistoryStorage(base, e);
        }
    }

    public void record() throws MetadataException {
        try {

            if (isRepositoryEmpty(git)) {
                final PersonIdent author = adjustCommitDateToCreationDate(getCommitter());
                final SavedState.Type commitType = SavedState.Type.INSTALL;
                final String msg = readCommitMessage();
                git.add().addFilepattern(ProsperoMetadataUtils.MANIFEST_FILE_NAME).call();
                git.add().addFilepattern(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME).call();
                git.add().addFilepattern(CURRENT_VERSION_FILE).call();
                git.add().addFilepattern(ProsperoMetadataUtils.PROVISIONING_RECORD_XML).call();
                // adjust the date so that when taking over a non-prosper installation date matches creation
                git.commit()
                        .setAuthor(author)
                        .setCommitter(author)
                        .setMessage(commitType.name() + (msg==null ? "" : " " + msg))
                        .call();
            } else {
                recordChange(SavedState.Type.UPDATE);
            }
        } catch (IOException | GitAPIException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToAccessHistoryStorage(base, e);
        }


    }

    private String readCommitMessage() throws MetadataException {
        final Path versionsFile = base.resolve(CURRENT_VERSION_FILE);
        try {
            return ManifestVersionRecord.read(versionsFile).map(ManifestVersionRecord::getSummary).orElse(null);
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToReadFile(versionsFile, e);
        }
    }

    public void recordChange(SavedState.Type operation) throws MetadataException {
        recordChange(operation, ProsperoMetadataUtils.MANIFEST_FILE_NAME, CURRENT_VERSION_FILE, ProsperoMetadataUtils.PROVISIONING_RECORD_XML);
    }

    public void recordChange(SavedState.Type operation, String... files) throws MetadataException {
        try {
            if (isRepositoryEmpty(git)) {
                throw new IllegalStateException("This operation cannot be performed on empty repository");
            }

            for (String file : files) {
                git.add().addFilepattern(file).call();
            }

            final PersonIdent author = getCommitter();
            final SavedState.Type commitType = operation;

            String msg = readCommitMessage();

            git.commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setMessage(commitType.name()+ (msg==null? "" : " " + msg ))
                    .call();

        } catch (IOException | GitAPIException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToAccessHistoryStorage(base, e);
        }
    }

    /*
     * The PersonIdent needs to be created on commit to capture current time
     */
    private PersonIdent getCommitter() {
        return new PersonIdent(GIT_HISTORY_USER, "");
    }

    private PersonIdent adjustCommitDateToCreationDate(PersonIdent committer) throws IOException {
        final FileTime fileTime = Files.readAttributes(base, BasicFileAttributes.class).creationTime();
        return new PersonIdent(committer, fileTime.toMillis(), committer.getTimeZone().getRawOffset());
    }

    public void recordConfigChange() throws MetadataException {
        try {
            git.add().addFilepattern(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME).call();
            final PersonIdent author = getCommitter();
            git.commit()
                    .setAuthor(author)
                    .setCommitter(author)
                    .setMessage(SavedState.Type.CONFIG_CHANGE.name())
                    .call();
        } catch (GitAPIException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToAccessHistoryStorage(base, e);
        }
    }

    public Path revert(SavedState savedState) throws MetadataException {
        try {
            Path hist = Files.createTempDirectory("hist").resolve(ProsperoMetadataUtils.METADATA_DIR);
            try (Git temp =  Git.cloneRepository()
                    .setDirectory(hist.toFile())
                    .setRemote("origin")
                    .setURI(base.toUri().toString())
                    .call()) {
                temp.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef(savedState.getName())
                        .call();

                if (!Files.exists(hist.resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML))) {
                    // find the latest persisted version of provisioning.xml
                    final Iterable<RevCommit> provRecordHistory = this.git.log()
                            .addPath(ProsperoMetadataUtils.PROVISIONING_RECORD_XML)
                            .call();

                    final Iterator<RevCommit> iterator = provRecordHistory.iterator();
                    RevCommit revCommit = null;
                    while (iterator.hasNext()) {
                        revCommit = iterator.next();
                    }

                    temp.checkout()
                            .addPath(ProsperoMetadataUtils.PROVISIONING_RECORD_XML)
                            .setAllPaths(false)
                            .setStartPoint(revCommit)
                            .call();
                }

                return hist.getParent();
            }
        } catch (GitAPIException | IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToAccessHistoryStorage(base, e);
        }
    }

    public void reset() throws MetadataException {
        try {
            git.reset()
                    .setRef("HEAD")
                    .setMode(ResetCommand.ResetType.HARD)
                    .call();
        } catch (GitAPIException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToAccessHistoryStorage(base, e);
        }
    }

    public List<ArtifactChange> getArtifactChanges(SavedState savedState) throws MetadataException {
        final Parser<ArtifactChange> parser = new ArtifactChangeParser();

        final SavedState other = getStateFromName(savedState.getName() + "^");
        return getChanges(savedState, other, ProsperoMetadataUtils.MANIFEST_FILE_NAME, parser);
    }

    public List<ArtifactChange> getArtifactChangesSince(SavedState savedState) throws MetadataException {
        final Parser<ArtifactChange> parser = new ArtifactChangeParser();

        final SavedState other = getStateFromName("HEAD");
        return getChanges(other, savedState, ProsperoMetadataUtils.MANIFEST_FILE_NAME, parser);
    }

    public List<ChannelChange> getChannelChanges(SavedState savedState) throws MetadataException {
        Parser<ChannelChange> parser = new ChannelChangeParser();

        final SavedState other = getStateFromName(savedState.getName() + "^");
        return getChanges(savedState, other, ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME, parser);
    }

    public List<ChannelChange> getChannelChangesSince(SavedState savedState) throws MetadataException {
        final Parser<ChannelChange> parser = new ChannelChangeParser();

        final SavedState other = getStateFromName("HEAD");
        return getChanges(other, savedState, ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME, parser);
    }

    public List<FeatureChange> getFeatureChanges(SavedState latestState) throws MetadataException {
        final SavedState other = getStateFromName(latestState.getName() + "^");
        return getChanges(latestState, other, ProsperoMetadataUtils.PROVISIONING_RECORD_XML, new FeatureChangeParser());
    }

    public List<FeatureChange> getFeatureChangesSince(SavedState latestState) throws MetadataException {
        final SavedState other = getStateFromName("HEAD");
        return getChanges(other, latestState, ProsperoMetadataUtils.PROVISIONING_RECORD_XML, new FeatureChangeParser());
    }

    private <T> List<T> getChanges(SavedState savedState, SavedState other, String manifestFileName, Parser<T> parser) throws MetadataException {
        try {
            final String change = checkoutPastState(savedState, manifestFileName);
            final String base;
            if (other != null) {
                base = checkoutPastState(other, manifestFileName);
            } else {
                base = null;
            }

            return parser.parse(change, base);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToParseConfiguration(this.base.resolve(manifestFileName), e);
        }
    }

    private SavedState getStateFromName(String savedState) throws MetadataException {
        try {
            final ObjectId parentRef = git.getRepository().resolve(savedState);
            return parentRef == null ? null : new SavedState(parentRef.getName());
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToAccessHistoryStorage(this.base, e);
        }
    }

    private Map<String, Artifact> toMap(Collection<Stream> artifacts) {
        final HashMap<String, Artifact> map = new HashMap<>();
        for (Stream stream : artifacts) {
            DefaultArtifact artifact = new DefaultArtifact(stream.getGroupId(), stream.getArtifactId(), "jar", stream.getVersion());
            map.put(stream.getGroupId() + ":" + stream.getArtifactId(), artifact);
        }
        return map;
    }

    private boolean isRepositoryEmpty(Git git) throws IOException {
        return git.getRepository().resolve(Constants.HEAD) == null;
    }

    private Git initGit() throws GitAPIException, IOException {
        Git git;
        if (!base.resolve(".git").toFile().exists()) {
            git = Git.init().setDirectory(base.toFile()).call();
            final StoredConfig config = git.getRepository().getConfig();
            config.setBoolean("commit", null, "gpgsign", false);
            config.setString("user", null, "name", GIT_HISTORY_USER);
            config.setString("user", null, "email", "");
            config.save();
        } else {
            git = Git.open(base.toFile());
        }
        return git;
    }

    @Override
    public void close() {
        if (git != null) {
            git.close();
        }
    }

    public boolean isStarted() throws IOException {
        return !isRepositoryEmpty(git);
    }

    private String checkoutPastState(SavedState savedState, String fileName) throws GitAPIException, IOException {
        // we need to get historical data from git, but we don't really want to change currently checkout state
        // so we're gonna find the commit in history, then load the file for it
        final RevWalk revWalk = new RevWalk(git.getRepository());
        try {
            final RevCommit commit = revWalk.parseCommit(git.getRepository().resolve(savedState.getName()));
            // the commit has an associated file tree. we need to find the fileName in it
            final RevTree tree = commit.getTree();

            try (TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(fileName));
                if (!treeWalk.next()) {
                    // there is no such file - we return null and let the caller handle it
                    return null;
                }

                // otherwise, let's read the file content
                final ObjectId objectId = treeWalk.getObjectId(0);
                final ObjectLoader loader = git.getRepository().open(objectId);

                try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                    loader.copyTo(os);
                    os.flush();
                    return os.toString(StandardCharsets.UTF_8);
                }
            }
        } finally {
            revWalk.dispose();
            revWalk.close();
        }
    }

    interface Parser<T> {
        List<T> parse(String changedPath, String basePath) throws IOException, MetadataException;
    }

    private static class ChannelChangeParser implements Parser<ChannelChange> {
        @Override
        public List<ChannelChange> parse(String changed, String base) throws IOException, MetadataException {
            final List<Channel> oldChannels = base == null ? Collections.emptyList() : ChannelMapper.fromString(base);
            final List<Channel> currentChannels = ChannelMapper.fromString(changed);

            final ArrayList<ChannelChange> channelChanges = new ArrayList<>();

            for (Channel current : currentChannels) {
                final Optional<Channel> oldChannel = oldChannels.stream()
                        .filter(old -> current.getName().equals(old.getName()))
                        .findFirst();
                if (oldChannel.isEmpty()) {
                    channelChanges.add(ChannelChange.added(current));
                } else {
                    final ChannelChange change = ChannelChange.modified(oldChannel.get(), current);
                    if (!change.getChildren().isEmpty()) {
                        channelChanges.add(change);
                    }
                }
            }

            for (Channel old : oldChannels) {
                final Optional<Channel> currentChannel = currentChannels.stream()
                        .filter(current -> current.getName().equals(old.getName()))
                        .findFirst();
                if (currentChannel.isEmpty()) {
                    channelChanges.add(ChannelChange.removed(old));
                }
            }

            return channelChanges;
        }
    }

    private class ArtifactChangeParser implements Parser<ArtifactChange> {
        @Override
        public List<ArtifactChange> parse(String changed, String base) throws IOException, MetadataException {
            final Map<String, Artifact> oldArtifacts;
            if (base != null) {
                final ChannelManifest parseOld = ChannelManifestMapper.fromString(base);
                oldArtifacts = GitStorage.this.toMap(parseOld.getStreams());
            } else {
                oldArtifacts = Collections.emptyMap();
            }

            final ChannelManifest parseCurrent = ChannelManifestMapper.fromString(changed);
            final Map<String, Artifact> currentArtifacts = GitStorage.this.toMap(parseCurrent.getStreams());

            final ArrayList<ArtifactChange> artifactChanges = new ArrayList<>();
            for (String ga : currentArtifacts.keySet()) {
                if (!oldArtifacts.containsKey(ga)) {
                    artifactChanges.add(ArtifactChange.added(currentArtifacts.get(ga)));
                } else if (!currentArtifacts.get(ga).getVersion().equals(oldArtifacts.get(ga).getVersion())) {
                    artifactChanges.add(ArtifactChange.updated(oldArtifacts.get(ga), currentArtifacts.get(ga)));
                }
            }
            for (String ga : oldArtifacts.keySet()) {
                if (!currentArtifacts.containsKey(ga)) {
                    artifactChanges.add(ArtifactChange.removed(oldArtifacts.get(ga)));
                }
            }

            return artifactChanges;
        }
    }
}
