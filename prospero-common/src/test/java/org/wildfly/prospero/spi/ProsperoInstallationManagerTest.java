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

package org.wildfly.prospero.spi;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.installationmanager.CandidateType;
import org.wildfly.installationmanager.FileConflict;
import org.wildfly.installationmanager.InstallationChanges;
import org.wildfly.installationmanager.MavenOptions;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.actions.InstallationHistoryAction;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.ChannelChange;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.updates.UpdateSet;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("OptionalGetWithoutIsPresent")
@RunWith(MockitoJUnitRunner.class)
public class ProsperoInstallationManagerTest {
    private static final Channel CHANNEL_1 = new Channel("channel-1", null, null,
            List.of(new Repository("repo1", "url1"), new Repository("repo2", "url2")),
            new ChannelManifestCoordinate("foo", "bar"), null, null);

    private static final Channel CHANNEL_2 = new Channel("channel-1", null, null,
            List.of(new Repository("repo1", "url1b"), new Repository("repo2", "url2")),
            new ChannelManifestCoordinate("foo", "bar2"), null, null);
    @Mock
    private ProsperoInstallationManager.ActionFactory actionFactory;

    @Mock
    private UpdateAction updateAction;

    @Mock
    private InstallationHistoryAction historyAction;

    @Mock
    private ApplyCandidateAction applyCandidateAction;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testChannelChanges() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getHistoryAction()).thenReturn(historyAction);
        when(historyAction.getRevisionChanges(any())).thenReturn(
                new org.wildfly.prospero.api.InstallationChanges(Collections.emptyList(),
                        List.of(ChannelChange.modified(CHANNEL_1, CHANNEL_2)), Collections.emptyList()));

        final InstallationChanges changes = mgr.revisionDetails("abcd");

        final org.wildfly.installationmanager.ChannelChange channelChange = changes.channelChanges().get(0);
        final org.wildfly.installationmanager.Channel oldChannel = channelChange.getOldChannel().get();
        final org.wildfly.installationmanager.Channel newChannel = channelChange.getNewChannel().get();
        assertEquals("channel-1", channelChange.getName());
        assertEquals("foo:bar", oldChannel.getManifestCoordinate().get());
        assertEquals("foo:bar2", newChannel.getManifestCoordinate().get());
        assertEquals("repo1", oldChannel.getRepositories().get(0).getId());
        assertEquals("repo1", newChannel.getRepositories().get(0).getId());
        assertEquals("url1", oldChannel.getRepositories().get(0).getUrl());
        assertEquals("url1b", newChannel.getRepositories().get(0).getUrl());
    }

    @Test
    public void testChannelChangesNewChannel() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getHistoryAction()).thenReturn(historyAction);
        when(historyAction.getRevisionChanges(any())).thenReturn(
                new org.wildfly.prospero.api.InstallationChanges(Collections.emptyList(),
                        List.of(ChannelChange.added(CHANNEL_1)), Collections.emptyList()));

        final InstallationChanges changes = mgr.revisionDetails("abcd");

        final org.wildfly.installationmanager.ChannelChange channelChange = changes.channelChanges().get(0);
        assertFalse(channelChange.getOldChannel().isPresent());
        assertTrue(channelChange.getNewChannel().isPresent());
        final org.wildfly.installationmanager.Channel newChannel = channelChange.getNewChannel().get();
        assertEquals("channel-1", channelChange.getName());
        assertEquals("foo:bar", newChannel.getManifestCoordinate().get());
        assertEquals(2, newChannel.getRepositories().size());
        assertEquals("repo1", newChannel.getRepositories().get(0).getId());
        assertEquals("url1", newChannel.getRepositories().get(0).getUrl());
    }

    @Test
    public void findUpdateWithNullRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getUpdateAction(Collections.emptyList(), null, true)).thenReturn(updateAction);
        when(updateAction.findUpdates()).thenReturn(new UpdateSet(Collections.emptyList()));

        mgr.findUpdates(null);
    }

    @Test
    public void findUpdateWithEmptyRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getUpdateAction(Collections.emptyList(), null, true)).thenReturn(updateAction);
        when(updateAction.findUpdates()).thenReturn(new UpdateSet(Collections.emptyList()));

        mgr.findUpdates(null);
    }

    @Test
    public void findUpdateWithRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getUpdateAction(List.of(new Repository("test", "http://test.te")), null, true)).thenReturn(updateAction);
        when(updateAction.findUpdates()).thenReturn(new UpdateSet(Collections.emptyList()));

        mgr.findUpdates(List.of(new org.wildfly.installationmanager.Repository("test", "http://test.te")));
    }

    @Test
    public void prepareUpdateWithNullRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getUpdateAction(Collections.emptyList(), false)).thenReturn(updateAction);
        when(updateAction.buildUpdate(any())).thenReturn(true);

        mgr.prepareUpdate(Path.of("test"), null, false);
    }

    @Test
    public void prepareUpdateReturnsStatusOfCandidate() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getUpdateAction(Collections.emptyList(), false)).thenReturn(updateAction);
        when(updateAction.buildUpdate(any())).thenReturn(false);

        assertFalse(mgr.prepareUpdate(Path.of("test"), null, false));
    }

    @Test
    public void prepareUpdateWithEmptyRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getUpdateAction(Collections.emptyList(), false)).thenReturn(updateAction);
        when(updateAction.buildUpdate(any())).thenReturn(true);

        mgr.prepareUpdate(Path.of("test"), Collections.emptyList(), false);
    }

    @Test
    public void prepareUpdateWithRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getUpdateAction(List.of(new Repository("test", "http://test.te")), false)).thenReturn(updateAction);
        when(updateAction.buildUpdate(any())).thenReturn(true);

        mgr.prepareUpdate(Path.of("test"), List.of(new org.wildfly.installationmanager.Repository("test", "http://test.te")), false);
    }

    @Test
    public void prepareRevertWithNullRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getHistoryAction()).thenReturn(historyAction);

        mgr.prepareRevert("abcd1234", Path.of("test"), null);
        verify(historyAction).prepareRevert(eq(new SavedState("abcd1234")), any(),
                eq(Collections.emptyList()), eq(Path.of("test")));
    }

    @Test
    public void prepareRevertWithEmptyRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getHistoryAction()).thenReturn(historyAction);

        mgr.prepareRevert("abcd1234", Path.of("test"), null);
        verify(historyAction).prepareRevert(eq(new SavedState("abcd1234")), any(),
                eq(Collections.emptyList()), eq(Path.of("test")));
    }

    @Test
    public void prepareRevertWithRepositoryListPassesEmptyList() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);
        when(actionFactory.getHistoryAction()).thenReturn(historyAction);

        mgr.prepareRevert("abcd1234", Path.of("test"), List.of(new org.wildfly.installationmanager.Repository("test", "http://test.te")));
        verify(historyAction).prepareRevert(eq(new SavedState("abcd1234")), any(),
                eq(List.of(new Repository("test", "http://test.te"))), eq(Path.of("test")));
    }

    @Test
    public void mapMavenOptions() throws Exception {
        // no local cache
        org.wildfly.prospero.api.MavenOptions mavenOptions = new ProsperoInstallationManager(temp.newFolder().toPath(),
                new MavenOptions(null, true, true))
                .getActionFactory().getMavenOptions();

        assertTrue(mavenOptions.isOffline());
        assertTrue(mavenOptions.isNoLocalCache());
        assertNull(mavenOptions.getLocalCache());

        // local cache path set
        final Path localRepository = temp.newFile().toPath();
        mavenOptions = new ProsperoInstallationManager(temp.newFolder().toPath(),
                new MavenOptions(localRepository, false, true))
                        .getActionFactory().getMavenOptions();

        assertTrue(mavenOptions.isOffline());
        assertFalse(mavenOptions.isNoLocalCache());
        assertEquals(localRepository, mavenOptions.getLocalCache());

        // default local cache
        mavenOptions = new ProsperoInstallationManager(temp.newFolder().toPath(),
                new MavenOptions(null, false, true))
                .getActionFactory().getMavenOptions();

        assertTrue(mavenOptions.isOffline());
        assertFalse(mavenOptions.isNoLocalCache());
        assertEquals(MavenOptions.LOCAL_MAVEN_REPO, mavenOptions.getLocalCache());

        // online
        mavenOptions = new ProsperoInstallationManager(temp.newFolder().toPath(),
                new MavenOptions(null, true, false))
                .getActionFactory().getMavenOptions();

        assertFalse(mavenOptions.isOffline());
        assertTrue(mavenOptions.isNoLocalCache());
        assertNull(mavenOptions.getLocalCache());
    }

    @Test
    public void testCheckUpdatesMapsConflicts() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);

        when(actionFactory.getApplyCandidateAction(any())).thenReturn(applyCandidateAction);
        when(applyCandidateAction.verifyCandidate(any())).thenReturn(ApplyCandidateAction.ValidationResult.OK);
        when(applyCandidateAction.getConflicts()).thenReturn(List.of(
                org.wildfly.prospero.api.FileConflict.userModified("foo/bar").updateModified().userPreserved(),
                org.wildfly.prospero.api.FileConflict.userModified("system/file_a").updateModified().overwritten(),
                org.wildfly.prospero.api.FileConflict.userAdded("system/file_b").updateAdded().overwritten()
        ));

        final Collection<FileConflict> conflicts = mgr.verifyCandidate(Path.of("candidate"), CandidateType.UPDATE);
        assertThat(conflicts)
                .contains(
                        new FileConflict(Path.of("foo/bar"), FileConflict.Status.MODIFIED, FileConflict.Status.MODIFIED, false),
                        new FileConflict(Path.of("system/file_a"), FileConflict.Status.MODIFIED, FileConflict.Status.MODIFIED, true),
                        new FileConflict(Path.of("system/file_b"), FileConflict.Status.ADDED, FileConflict.Status.ADDED, true)
                );
    }

    @Test
    public void testCheckUpdatesThrowsVerificationExceptions() throws Exception {
        final ProsperoInstallationManager mgr = new ProsperoInstallationManager(actionFactory);

        when(actionFactory.getApplyCandidateAction(any())).thenReturn(applyCandidateAction);
        when(applyCandidateAction.verifyCandidate(any())).thenReturn(ApplyCandidateAction.ValidationResult.STALE);

        assertThatThrownBy(() -> mgr.verifyCandidate(Path.of("candidate"), CandidateType.UPDATE))
                .hasMessageContaining("has been modified after the candidate has been created");

    }
}