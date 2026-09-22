/*
 * Copyright 2023 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.api;

import org.assertj.core.api.Assumptions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MavenOptionsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void mergeOfflineOptionsBothPresent() throws Exception {
        MavenOptions base = MavenOptions.builder()
                .setOffline(true)
                .build();

        MavenOptions override = MavenOptions.builder()
                .setOffline(false)
                .build();

        assertFalse(base.merge(override).isOffline());
    }

    @Test
    public void mergeOfflineOptionsBasePresent() throws Exception {
        MavenOptions base = MavenOptions.builder()
                .setOffline(true)
                .build();

        MavenOptions override = MavenOptions.DEFAULT_OPTIONS;

        assertTrue(base.merge(override).isOffline());
    }

    @Test
    public void mergeOfflineOptionsOverridePresent() throws Exception {
        MavenOptions base = MavenOptions.DEFAULT_OPTIONS;

        MavenOptions override = MavenOptions.builder()
                .setOffline(true)
                .build();

        assertTrue(base.merge(override).isOffline());
    }

    @Test
    public void mergeNoCacheOptionsBothPresent() throws Exception {
        MavenOptions base = MavenOptions.builder()
                .setNoLocalCache(true)
                .build();

        MavenOptions override = MavenOptions.builder()
                .setNoLocalCache(false)
                .build();

        assertFalse(base.merge(override).isNoLocalCache());
    }

    @Test
    public void mergeNoCacheOptionsBasePresent() throws Exception {
        MavenOptions base = MavenOptions.builder()
                .setNoLocalCache(true)
                .build();

        MavenOptions override = MavenOptions.DEFAULT_OPTIONS;

        assertTrue(base.merge(override).isNoLocalCache());
    }

    @Test
    public void mergeNoCacheOptionsOverridePresent() throws Exception {
        MavenOptions base = MavenOptions.DEFAULT_OPTIONS;

        MavenOptions override = MavenOptions.builder()
                .setNoLocalCache(true)
                .build();

        assertTrue(base.merge(override).isNoLocalCache());
    }

    @Test
    public void mergeLocalCacheOptionsBothPresent() throws Exception {
        MavenOptions base = MavenOptions.builder()
                .setLocalCachePath(Path.of("foo"))
                .build();

        MavenOptions override = MavenOptions.builder()
                .setLocalCachePath(Path.of("bar"))
                .build();

        assertEquals(Path.of("bar"), base.merge(override).getLocalCache());
    }

    @Test
    public void mergeLocalCacheOptionsBasePresent() throws Exception {
        MavenOptions base = MavenOptions.builder()
                .setLocalCachePath(Path.of("foo"))
                .build();

        MavenOptions override = MavenOptions.DEFAULT_OPTIONS;

        assertEquals(Path.of("foo"), base.merge(override).getLocalCache());
    }

    @Test
    public void mergeLocalCacheOptionsOverridePresent() throws Exception {
        MavenOptions base = MavenOptions.DEFAULT_OPTIONS;

        MavenOptions override = MavenOptions.builder()
                .setLocalCachePath(Path.of("bar"))
                .build();

        assertEquals(Path.of("bar"), base.merge(override).getLocalCache());
    }

    @Test
    public void readWriteMavenOptionsContent() throws Exception {
        MavenOptions base = MavenOptions.DEFAULT_OPTIONS;
        Path target = temp.newFile().toPath();
        base.write(target);
        assertEquals(MavenOptions.builder()
                .setOffline(false)
                .setNoLocalCache(true)
                .build(), MavenOptions.read(target));

        base = MavenOptions.builder()
                .setLocalCachePath(Path.of("foo").toAbsolutePath())
                .setNoLocalCache(false)
                .setOffline(true)
                .build();
        base.write(target);
        assertEquals(base, MavenOptions.read(target));
    }

    @Test
    public void defaultMavenSettings() {
        MavenOptions options = MavenOptions.builder().build();
        assertThat(options.getMavenSettingsPath()).isEqualTo(Path.of(System.getProperty("user.home"), ".m2/settings.xml"));
    }

    @Test
    public void notExistingMavenSettings() {
        Path notExistingPath = Path.of(UUID.randomUUID().toString());
        Assumptions.assumeThat(notExistingPath).doesNotExist();

        MavenOptions options = MavenOptions.builder().setMavenSettingsPath(notExistingPath).build();
        assertThat(options.getMavenSettings()).isNull();
    }

}