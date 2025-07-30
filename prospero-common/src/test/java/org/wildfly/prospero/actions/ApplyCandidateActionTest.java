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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.galleon.Constants;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.ProvisioningManager;
import org.jboss.galleon.creator.FeaturePackCreator;
import org.jboss.galleon.repo.RepositoryArtifactResolver;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.repo.SimplisticMavenRepoManager;
import org.junit.Assert;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.Repository;
import org.wildfly.channel.Stream;
import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.FileConflict;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.exceptions.InvalidUpdateCandidateException;
import org.wildfly.prospero.galleon.ArtifactCache;
import org.wildfly.prospero.installation.git.GitStorage;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.CURRENT_VERSION_FILE;
import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.METADATA_DIR;

import org.wildfly.prospero.updates.CandidateProperties;
import org.wildfly.prospero.updates.CandidatePropertiesParser;
import org.wildfly.prospero.updates.MarkerFile;
import org.wildfly.prospero.utils.filestate.DirState;

@SuppressWarnings({"OptionalGetWithoutIsPresent", "ResultOfMethodCallIgnored"})
public class ApplyCandidateActionTest {

    private static final String FPL_100 = "org.test:pack-one:1.0.0:zip";
    private static final String FPL_101 = "org.test:pack-one:1.0.1:zip";
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    private Path repoHome;
    private RepositoryArtifactResolver repo;
    private Path installationPath;
    private Path updatePath;
    private DirState.DirBuilder dirBuilder;
    private FeaturePackCreator creator;

    @Before
    public void setUp() throws Exception {
        repoHome = temp.newFolder().toPath();
        installationPath = temp.newFolder().toPath();
        updatePath = temp.newFolder().toPath();
        repo = initRepoManager(repoHome);
        creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);
        dirBuilder = DirState.rootBuilder()
                .skip(Constants.PROVISIONED_STATE_DIR)
                .skip(METADATA_DIR);
    }

    @Test
    public void testUpdateNoUserChanges() throws Exception {
        final DirState expectedState = dirBuilder
                .addFile("prod1/p1.txt", "p1 1.0.1")
                .build();

        // build test packages
        createSimpleFeaturePacks();

        // install base and update. perform apply-update
        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);
        final List<FileConflict> conflicts = new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE);

        // verify
        expectedState.assertState(installationPath);
        assertThat(conflicts).isEmpty();
    }

    public void testUpdateWithSymlink() throws Exception {
        final DirState expectedState = dirBuilder
                .addFile("prod1/p1.txt", "p1 1.0.1")
                .addDir("log")
                .addDir("testlog")
                .build();

        // build test packages
        createSimpleFeaturePacks();

        // install base and update. perform apply-update
        install(installationPath, FPL_100);

        //creating SymbolicLink
        Path symlinkPath = Files.createDirectory(installationPath.resolve("testlog"));
        Path logPath = installationPath.resolve("log");
        Files.createSymbolicLink(logPath, symlinkPath);

        prepareUpdate(updatePath, installationPath, FPL_101);
        final List<FileConflict> conflicts = new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE);

        // verify
        expectedState.assertState(installationPath);
        assertThat(conflicts).isEmpty();
        assertTrue(Files.exists(logPath));
        assertTrue(Files.isSymbolicLink(logPath));
    }

    @Test
    public void testUpdateWithUserChanges() throws Exception {
        final DirState expectedState = dirBuilder
                .addFile("prod1/p1.txt", "user prod1/p1")
                .addFile("prod1/p1.txt.glnew", "prod1/p1 1.0.1")
                .addFile("prod1/p2.txt", "prod1/p2 1.0.1")
                .addFile("prod1/p4.txt", "user prod1/p4")
                .addFile("prod1/p4.txt.glnew", "prod1/p4 1.0.1")
                .addFile("prod2/p2.txt", "user prod2/p2")
                .addFile("prod3/p1.txt", "prod3/p1 1.0.1")
                .addFile("prod4/p1.txt", "user prod4/p1")
                .addFile("prod4/p1.txt.glnew", "prod4/p1 1.0.1")
                .addFile("new_dir/users.txt", "user new_dir/users.txt")
                .addFile("new.file", "user new file")
                .build();

        // build test packages
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_100).getFPID())
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "prod1/p1 1.0.0")
                .writeContent("prod1/p2.txt", "prod1/p2 1.0.0") // removed by user, restored in update
                .writeContent("prod1/p3.txt", "prod1/p3 1.0.0") // removed by user, not restored in update
                .writeContent("prod2/p1.txt", "prod2/p1 1.0.0") // removed by update
                .writeContent("prod2/p2.txt", "prod2/p2 1.0.0") // removed by update, updated by user
                .getFeaturePack();
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_101).getFPID())
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "prod1/p1 1.0.1")
                .writeContent("prod1/p2.txt", "prod1/p2 1.0.1")
                .writeContent("prod1/p4.txt", "prod1/p4 1.0.1") // not present in base, added by user and overwritten
                .writeContent("prod3/p1.txt", "prod3/p1 1.0.1") // not present in base, not added by user
                .writeContent("prod4/p1.txt", "prod4/p1 1.0.1") // not present in base, added by user in new directory
                .getFeaturePack();
        creator.install();

        // install base
        install(installationPath, FPL_100);
        // perform user changes
        writeContent("prod1/p1.txt", "user prod1/p1");
        writeContent("new.file", "user new file");
        Files.delete(installationPath.resolve("prod1/p2.txt"));
        writeContent("prod1/p4.txt", "user prod1/p4");
        Files.createDirectory(installationPath.resolve("new_dir"));
        Files.createDirectory(installationPath.resolve("prod4"));
        writeContent("prod4/p1.txt", "user prod4/p1");
        writeContent("prod2/p2.txt", "user prod2/p2");
        writeContent("new_dir/users.txt", "user new_dir/users.txt");
        // update
        prepareUpdate(updatePath, installationPath, FPL_101);
        final List<FileConflict> conflicts = new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE);

        // verify
        expectedState.assertState(installationPath);
        assertThat(conflicts).containsExactlyInAnyOrder(
                FileConflict.userModified("prod1/p1.txt").updateModified().userPreserved(),
                FileConflict.userAdded("prod1/p4.txt").updateAdded().userPreserved(),
                FileConflict.userAdded("prod4/p1.txt").updateAdded().userPreserved(),
                FileConflict.userModified("prod2/p2.txt").updateRemoved().userPreserved()
        );
    }


    @Test
    public void testUserChangesInSystemPaths() throws Exception {
        final DirState expectedState = dirBuilder
                .addFile("prod1/p1.txt", "p1 1.0.1")
                .addFile("prod1/p1.txt.glold", "user prod1/p1")
                .addFile("prod1/p2.txt", "p2 1.0.1")
                .addFile("prod1/p3.txt", "p3 1.0.1")
                .addFile("prod1/p3.txt.glold", "user prod1/p3")
                .build();

        // build test packages
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_100).getFPID())
                .addSystemPaths("prod1")
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.0") // modified by user and updated in update
                .writeContent("prod1/p2.txt", "p2 1.0.0") // removed by user and restored in update
                .getFeaturePack();
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_101).getFPID())
                .addSystemPaths("prod1")
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.1")
                .writeContent("prod1/p2.txt", "p2 1.0.1")
                .writeContent("prod1/p3.txt", "p3 1.0.1") // created by user, added in update
                .getFeaturePack();
        creator.install();

        // install base and update. perform apply-update
        install(installationPath, FPL_100);
        writeContent("prod1/p1.txt", "user prod1/p1");
        Files.delete(installationPath.resolve("prod1/p2.txt"));
        writeContent("prod1/p3.txt", "user prod1/p3");
        prepareUpdate(updatePath, installationPath, FPL_101);
        final List<FileConflict> conflicts = new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE);

        // verify
        expectedState.assertState(installationPath);
        FileConflict.userAdded("prod1/p3.txt").updateAdded().userPreserved();
        assertThat(conflicts).containsExactlyInAnyOrder(
                FileConflict.userModified("prod1/p1.txt").updateModified().overwritten(),
                FileConflict.userRemoved("prod1/p2.txt").updateModified().overwritten(),
                FileConflict.userAdded("prod1/p3.txt").updateAdded().overwritten()
        );
    }

    @Test
    public void testMetadataUpdated() throws Exception {
        // 1. manifest.yaml is updated
        // 2. installation-channels.yaml are not modified
        // 3. .galleon is updated with new values

        final DirState expectedState = DirState.rootBuilder()
                .skip("prod1")
                .skip(METADATA_DIR + "/" + ".git")
                .skip(Constants.PROVISIONED_STATE_DIR)
                .addFile(METADATA_DIR + "/" + ProsperoMetadataUtils.MANIFEST_FILE_NAME,
                        manifest("manifest " + FPL_101).trim())
                .addFile(METADATA_DIR + "/" + ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME,
                        channel("channels " + FPL_100).trim())
                .addFile(ArtifactCache.CACHE_FOLDER.toString().replace(File.separatorChar, '/') + "/" + "artifacts.txt" , FPL_101+"::abcd::foo/bar")
                .skip(METADATA_DIR + "/" + ProsperoMetadataUtils.PROVISIONING_RECORD_XML)
                .build();

        // build test packages
        createSimpleFeaturePacks();

        // install base and update. perform apply-update
        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);
        new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE);

        // verify
        expectedState.assertState(installationPath);
        assertTrue(Files.readString(installationPath.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONING_XML))
                .contains(FPL_101));
        assertTrue(Files.readString(installationPath.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONED_STATE_XML))
                .contains(FPL_101));
        // verify update was recorded
        try(GitStorage gitStorage = new GitStorage(installationPath)) {
            assertEquals(2, gitStorage.getRevisions().size());
        }
    }

    @Test
    public void updateWithoutMarkerFileIsRejected() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        Files.deleteIfExists(updatePath.resolve(MarkerFile.UPDATE_MARKER_FILE));

        assertThrows(InvalidUpdateCandidateException.class, ()->new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE));
    }

    @Test
    public void updateWithMarkerNotMatchingCurrentRevisionFileIsRejected() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        new MarkerFile("abcd1234", ApplyCandidateAction.Type.UPDATE).write(updatePath);

        assertThrows(InvalidUpdateCandidateException.class, ()->new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE));
    }

    @Test
    public void markerFileIsNotPresentInUpdateFolder() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE);

        assertFalse(Files.exists(installationPath.resolve(MarkerFile.UPDATE_MARKER_FILE)));
    }

    @Test
    public void targetStandaloneServerHasToBeStopped() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        Files.createDirectories(installationPath.resolve(ApplyCandidateAction.STANDALONE_STARTUP_MARKER.getParent()));
        Files.writeString(installationPath.resolve(ApplyCandidateAction.STANDALONE_STARTUP_MARKER), "test");

        assertThrows(ProvisioningException.class, () -> new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE));
    }

    @Test
    public void targetDomainServerHasToBeStopped() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        Files.createDirectories(installationPath.resolve(ApplyCandidateAction.DOMAIN_STARTUP_MARKER.getParent()));
        Files.writeString(installationPath.resolve(ApplyCandidateAction.DOMAIN_STARTUP_MARKER), "test");

        assertThrows(ProvisioningException.class, () -> new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE));
    }

    @Test
    public void operationInMarkerFileHasToMatch() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        new MarkerFile("abcd1234", ApplyCandidateAction.Type.UPDATE).write(updatePath);

        assertThrows(InvalidUpdateCandidateException.class, ()->new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.REVERT));
    }

    @Test
    public void manifestVersionIsUpdated() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        final ManifestVersionRecord manifestVersionRecord = new ManifestVersionRecord();
        manifestVersionRecord.addManifest(new ManifestVersionRecord.MavenManifest("org.foo", "bar", "1.0.0"));
        ProsperoMetadataUtils.writeVersionRecord(installationPath.resolve(METADATA_DIR).resolve(CURRENT_VERSION_FILE), manifestVersionRecord);

        prepareUpdate(updatePath, installationPath, FPL_101);
        final ManifestVersionRecord manifestVersionRecord2 = new ManifestVersionRecord();
        manifestVersionRecord2.addManifest(new ManifestVersionRecord.MavenManifest("org.foo", "bar", "1.0.1"));
        ProsperoMetadataUtils.writeVersionRecord(updatePath.resolve(METADATA_DIR).resolve(CURRENT_VERSION_FILE), manifestVersionRecord2);

        new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.UPDATE);

        assertEquals(manifestVersionRecord2.getSummary(), ManifestVersionRecord.read(installationPath.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(CURRENT_VERSION_FILE)).get().getSummary());
    }

    @Test
    public void verifyCandidateNoMarkerFile() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        Files.delete(updatePath.resolve(MarkerFile.UPDATE_MARKER_FILE));

        final ApplyCandidateAction.ValidationResult validationResult = new ApplyCandidateAction(installationPath, updatePath)
                .verifyCandidate(ApplyCandidateAction.Type.UPDATE);

        assertEquals(ApplyCandidateAction.ValidationResult.NOT_CANDIDATE, validationResult);
    }

    @Test
    public void verifyCandidateWrongOperation() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        final ApplyCandidateAction.ValidationResult validationResult = new ApplyCandidateAction(installationPath, updatePath)
                .verifyCandidate(ApplyCandidateAction.Type.REVERT);

        assertEquals(ApplyCandidateAction.ValidationResult.WRONG_TYPE, validationResult);
    }

    @Test
    public void verifyCandidateOldHash() throws Exception {
        createSimpleFeaturePacks();
        final ApplyCandidateAction applyCandidateAction = new ApplyCandidateAction(installationPath, updatePath);

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);
        // apply the update to move base to a new hash
        applyCandidateAction.applyUpdate(ApplyCandidateAction.Type.UPDATE);

        final ApplyCandidateAction.ValidationResult validationResult = applyCandidateAction
                .verifyCandidate(ApplyCandidateAction.Type.UPDATE);

        assertEquals(ApplyCandidateAction.ValidationResult.STALE, validationResult);
    }

    @Test
    public void verifyCandidateNoChanges() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareRevert(updatePath, installationPath, FPL_100);

        final ApplyCandidateAction.ValidationResult validationResult = new ApplyCandidateAction(installationPath, updatePath)
                .verifyCandidate(ApplyCandidateAction.Type.REVERT);

        assertEquals(ApplyCandidateAction.ValidationResult.NO_CHANGES, validationResult);
    }

    @Test
    public void verifyCandidateValidMarker() throws Exception {
        createSimpleFeaturePacks();
        final ApplyCandidateAction applyCandidateAction = new ApplyCandidateAction(installationPath, updatePath);

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        final ApplyCandidateAction.ValidationResult validationResult = applyCandidateAction
                .verifyCandidate(ApplyCandidateAction.Type.UPDATE);

        assertEquals(ApplyCandidateAction.ValidationResult.OK, validationResult);
    }

    @Test
    public void findUpdatesModified() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        Files.writeString(installationPath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.0"))));
        prepareUpdate(updatePath, installationPath, FPL_101);
        Files.writeString(updatePath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.1"))));

        assertThat(new ApplyCandidateAction(installationPath, updatePath).findUpdates().getArtifactUpdates())
                .containsOnly(ArtifactChange.updated(new DefaultArtifact("org.test", "foo", null, "1.0.0"),
                        new DefaultArtifact("org.test", "foo", null, "1.0.1")));
    }

    @Test
    public void findUpdatesAdded() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);
        Files.writeString(updatePath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.1"))));

        assertThat(new ApplyCandidateAction(installationPath, updatePath).findUpdates().getArtifactUpdates())
                .containsOnly(ArtifactChange.added(new DefaultArtifact("org.test", "foo", null, "1.0.1")));
    }

    @Test
    public void findUpdatesRemoved() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        Files.writeString(installationPath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.0"))));
        prepareUpdate(updatePath, installationPath, FPL_101);

        assertThat(new ApplyCandidateAction(installationPath, updatePath).findUpdates().getArtifactUpdates())
                .containsOnly(ArtifactChange.removed(new DefaultArtifact("org.test", "foo", null, "1.0.0")));
    }

    @Test
    public void featureAddIsRecordedAsAddingFeature() throws Exception {
        createSimpleFeaturePacks();

        String newFpl = createNewFeaturePack();

        install(installationPath, FPL_100);

        prepareFeatureAddCandidate(updatePath, installationPath, newFpl);

        new ApplyCandidateAction(installationPath, updatePath).applyUpdate(ApplyCandidateAction.Type.FEATURE_ADD);

        assertEquals(SavedState.Type.FEATURE_PACK, new GitStorage(installationPath).getRevisions().get(0).getType());

        assertThat(Files.readString(installationPath.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONING_XML)))
                .contains(newFpl);
        assertThat(Files.readString(installationPath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML)))
                .contains(newFpl);
    }

    @Test
    public void verifyRemoveCandidate() throws Exception {
        createSimpleFeaturePacks();
        final ApplyCandidateAction applyCandidateAction = new ApplyCandidateAction(installationPath, updatePath);

        install(installationPath, FPL_100);
        prepareUpdate(updatePath, installationPath, FPL_101);

        applyCandidateAction.removeCandidate(updatePath.toFile());

        Assert.assertFalse(Files.exists(updatePath));
    }

    @Test
    public void findUpdatesContainsChannelNameIfProvided() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        Files.writeString(installationPath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.0"),
                        new Stream("org.test", "bar", "1.0.0"))));
        prepareUpdate(updatePath, installationPath, FPL_101);
        Files.writeString(updatePath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.1"),
                        new Stream("org.test", "bar", "1.0.0"))));
        CandidatePropertiesParser.write(
                new CandidateProperties(List.of(new CandidateProperties.ComponentUpdate("org.test", "foo", "test-channel"))),
                updatePath.resolve(METADATA_DIR).resolve(ApplyCandidateAction.CANDIDATE_CHANNEL_NAME_LIST));

        assertThat(new ApplyCandidateAction(installationPath, updatePath).findUpdates().getArtifactUpdates())
                .containsOnly(ArtifactChange.updated(new DefaultArtifact("org.test", "foo", null, "1.0.0"),
                        new DefaultArtifact("org.test", "foo", null, "1.0.1"),
                        "test-channel"),
                        ArtifactChange.updated(new DefaultArtifact("org.test", "foo", null, "1.0.0"),
                                new DefaultArtifact("org.test", "bar", null, "1.0.1")));
    }

    @Test
    public void findUpdatesIgnoresInvalidCandidatePropertiesFile() throws Exception {
        createSimpleFeaturePacks();

        install(installationPath, FPL_100);
        Files.writeString(installationPath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.0"))));
        prepareUpdate(updatePath, installationPath, FPL_101);
        Files.writeString(updatePath.resolve(METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest 01",
                List.of(new Stream("org.test", "foo", "1.0.1"))));
        Files.writeString(updatePath.resolve(METADATA_DIR).resolve(ApplyCandidateAction.CANDIDATE_CHANNEL_NAME_LIST), "I'm invalid");

        assertThat(new ApplyCandidateAction(installationPath, updatePath).findUpdates().getArtifactUpdates())
                .containsOnly(ArtifactChange.updated(new DefaultArtifact("org.test", "foo", null, "1.0.0"),
                                new DefaultArtifact("org.test", "foo", null, "1.0.1")));
    }

    @Test
    public void testFindConflictsInSystemPaths() throws Exception {
        final DirState expectedState = dirBuilder
                .addFile("prod1/p1.txt", "user prod1/p1")
                .addFile("prod1/p3.txt", "user prod1/p3")
                .build();

        // build test packages
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_100).getFPID())
                .addSystemPaths("prod1")
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.0") // modified by the user
                .writeContent("prod1/p2.txt", "p2 1.0.0") // removed by user and restored in update
                .getFeaturePack();
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_101).getFPID())
                .addSystemPaths("prod1")
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.1")
                .writeContent("prod1/p2.txt", "p2 1.0.1")
                .getFeaturePack();
        creator.install();

        // install base and update. perform apply-update
        install(installationPath, FPL_100);
        writeContent("prod1/p1.txt", "user prod1/p1");
        Files.delete(installationPath.resolve("prod1/p2.txt"));
        writeContent("prod1/p3.txt", "user prod1/p3");
        prepareUpdate(updatePath, installationPath, FPL_101);
        final List<FileConflict> conflicts = new ApplyCandidateAction(installationPath, updatePath).getConflicts();

        // verify
        expectedState.assertState(installationPath);
        FileConflict.userAdded("prod1/p3.txt").updateAdded().userPreserved();
        assertThat(conflicts).containsExactlyInAnyOrder(
                FileConflict.userModified("prod1/p1.txt").updateModified().overwritten(),
                FileConflict.userRemoved("prod1/p2.txt").updateModified().overwritten()
        );
    }

    @Test
    public void ignoreAddedNonReadableFolders() throws Exception {
        final DirState expectedState = dirBuilder
                .addFile("prod1/p1.txt", "p1 1.0.1")
                .addFile("prod1/p2.txt", "p2 1.0.1")
                .addFile("test/test.txt", "test")
                .build();

        // build test packages
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_100).getFPID())
                .addSystemPaths("prod1")
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.0") // modified by the user
                .writeContent("prod1/p2.txt", "p2 1.0.0") // removed by user and restored in update
                .getFeaturePack();
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_101).getFPID())
                .addSystemPaths("prod1")
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.1")
                .writeContent("prod1/p2.txt", "p2 1.0.1")
                .getFeaturePack();
        creator.install();

        // install base and update. perform apply-update
        install(installationPath, FPL_100);
        Files.createDirectories(installationPath.resolve("test"));
        Files.writeString(installationPath.resolve("test").resolve("test.txt"), "test");
        installationPath.resolve("test").toFile().setReadable(false);

        prepareUpdate(updatePath, installationPath, FPL_101);
        new ApplyCandidateAction(installationPath, updatePath)
                .applyUpdate(ApplyCandidateAction.Type.UPDATE);

        // verify
        installationPath.resolve("test").toFile().setReadable(true);
        expectedState.assertState(installationPath);
    }

    private void createSimpleFeaturePacks() throws ProvisioningException {
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_100).getFPID())
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.0")
                .getFeaturePack();
        creator.newFeaturePack(FeaturePackLocation.fromString(FPL_101).getFPID())
                .newPackage("p1", true)
                .writeContent("prod1/p1.txt", "p1 1.0.1")
                .getFeaturePack();
        creator.install();
    }

    private String createNewFeaturePack() throws ProvisioningException {
        final String fpl = "org.test:pack-two:1.0.0:zip";
        creator.newFeaturePack(FeaturePackLocation.fromString(fpl).getFPID())
                .newPackage("p2", true)
                .writeContent("prod2/p1.txt", "p2 1.0.0")
                .getFeaturePack();
        creator.install();
        return fpl;
    }

    private void writeContent(String path, String content) throws IOException {
        Files.writeString(installationPath.resolve(path.replace("/", File.separator)), content);
    }

    private void install(Path path, String fpl) throws Exception {
        HashMap<String, String> options = new HashMap<>();
        options.put(Constants.EXPORT_SYSTEM_PATHS, "true");
        getPm(path).install(FeaturePackLocation.fromString(fpl), options);
        // mock the installation metadata
        final Path metadataPath = path.resolve(METADATA_DIR);
        Files.createDirectory(metadataPath);
        Files.writeString(metadataPath.resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME), manifest("manifest " + fpl));
        Files.writeString(metadataPath.resolve(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME), channel("channels " + fpl));
        try (final GitStorage gitStorage = new GitStorage(path)) {
            gitStorage.record();
        }

        Files.createDirectory(path.resolve(ArtifactCache.CACHE_FOLDER));
        Files.writeString(path.resolve(ArtifactCache.CACHE_FOLDER).resolve("artifacts.txt"), fpl + "::abcd::foo/bar");
    }

    private String manifest(String name) throws IOException {
        return manifest(name, Collections.emptyList());
    }

    private String manifest(String name, List<Stream> streams) throws IOException {
        String txt = ChannelManifestMapper.toYaml(new ChannelManifest(name, null, null, streams));
        // workaround for Windows
        return txt.replace("\n", System.lineSeparator());
    }

    private String channel(String name) throws IOException {
        final String txt = ChannelMapper.toYaml(new Channel(name, null, null,
                List.of(new Repository("foo", "http://foo.bar")),
                new ChannelManifestCoordinate("foo", "bar"),
                null, null));
        // workaround for Windows
        return txt.replace("\n", System.lineSeparator());
    }

    private void prepareFeatureAddCandidate(Path updatePath, Path basePath, String fpl) throws Exception {
        install(updatePath, fpl);

        // create update marker file
        try (final GitStorage gitStorage = new GitStorage(basePath)) {
            final String revHash = gitStorage.getRevisions().get(0).getName();
            new MarkerFile(revHash, ApplyCandidateAction.Type.FEATURE_ADD).write(updatePath);
        }
    }

    private void prepareUpdate(Path updatePath, Path basePath, String fpl) throws Exception {
        install(updatePath, fpl);

        // create update marker file
        try (final GitStorage gitStorage = new GitStorage(basePath)) {
            final String revHash = gitStorage.getRevisions().get(0).getName();
            new MarkerFile(revHash, ApplyCandidateAction.Type.UPDATE).write(updatePath);
        }
    }

    private void prepareRevert(Path updatePath, Path basePath, String fpl) throws Exception {
        install(updatePath, fpl);

        // create update marker file
        try (final GitStorage gitStorage = new GitStorage(basePath)) {
            final String revHash = gitStorage.getRevisions().get(0).getName();
            new MarkerFile(revHash, ApplyCandidateAction.Type.REVERT).write(updatePath);
        }
    }

    protected RepositoryArtifactResolver initRepoManager(Path repoHome) {
        return SimplisticMavenRepoManager.getInstance(repoHome);
    }

    protected ProvisioningManager getPm(Path path) throws Exception {
        return ProvisioningManager.builder()
                .addArtifactResolver(repo)
                .setInstallationHome(path)
                .setRecordState(true)
                .build();
    }
}