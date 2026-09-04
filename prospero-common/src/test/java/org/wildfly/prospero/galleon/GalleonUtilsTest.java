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

package org.wildfly.prospero.galleon;

import org.jboss.galleon.ProvisioningException;
import org.junit.Test;
import org.wildfly.channel.ArtifactTransferException;
import org.wildfly.channel.UnresolvedMavenArtifactException;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.wildfly.prospero.galleon.GalleonUtils.JBOSS_MODULES_SETTINGS_XML_URL;

public class GalleonUtilsTest {

    @Test
    public void clearAndRestoreJbossModuleProperty() throws Exception {
        System.setProperty(GalleonUtils.MODULE_PATH_PROPERTY, "foo");
        try {
            GalleonUtils.executeGalleon((options) -> assertNull(System.getProperty(GalleonUtils.MODULE_PATH_PROPERTY)), Paths.get("test"));
            assertEquals(System.getProperty(GalleonUtils.MODULE_PATH_PROPERTY), "foo");
        } finally {
            System.clearProperty(GalleonUtils.MODULE_PATH_PROPERTY);
        }
    }

    @Test
    public void setForkModeOptionBeforeExecution() throws Exception {
        GalleonUtils.executeGalleon((options) -> assertEquals(options.get(GalleonUtils.JBOSS_FORK_EMBEDDED_PROPERTY), GalleonUtils.JBOSS_FORK_EMBEDDED_VALUE),
                Paths.get("test"));
    }

    @Test
    public void setAndClearMavenRepoPropertyAroundExecution() throws Exception {
        GalleonUtils.executeGalleon((options) -> assertEquals(System.getProperty(GalleonUtils.MAVEN_REPO_LOCAL), "test"),
                Paths.get("test"));
    }

    @Test
    public void extractFailedArtifactResolutionOnCopy() throws Exception {
        final ProvisioningException test = new ProvisioningException(
                new ArtifactTransferException("test", Collections.emptySet(), Collections.emptySet()));
        try {
            GalleonUtils.executeGalleon(o -> {
                throw test;
            }, Paths.get("test"));
            fail("Should throw an exception");
        } catch (UnresolvedMavenArtifactException e) {
            assertEquals("test", e.getMessage());
        }
    }

    @Test
    public void deleteTemporarySettingsXmlAfterExecution() throws Exception {
        AtomicReference<String> settingsUrl = new AtomicReference<>();
        GalleonUtils.executeGalleon((options) -> {
            settingsUrl.set(System.getProperty(JBOSS_MODULES_SETTINGS_XML_URL));
                    assertThat(urlToPath(System.getProperty(JBOSS_MODULES_SETTINGS_XML_URL)))
                            .exists()
                            .hasContent("<settings/>");
                },
                Paths.get("test"));

        assertThat(urlToPath(settingsUrl.get()))
                .doesNotExist();
        assertThat(System.getProperties())
                .doesNotContainKey(JBOSS_MODULES_SETTINGS_XML_URL);
    }

    @Test
    public void testCycloneDxProperties() throws Exception {
        String cycloneDx = System.getProperty(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY);
        String cycloneDxFailOnError = System.getProperty(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY);
        try {
            System.setProperty(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY, "true");
            System.setProperty(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY, "true");
            GalleonUtils.executeGalleon((options) -> {
                assertEquals("true", options.get(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY));
                assertEquals("true", options.get(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY));
            }, Paths.get("test"));

            System.setProperty(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY, "false");
            System.setProperty(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY, "false");
            GalleonUtils.executeGalleon((options) -> {
                assertEquals("false", options.get(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY));
                assertEquals("false", options.get(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY));
            }, Paths.get("test"));

            System.clearProperty(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY);
            System.clearProperty(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY);
            GalleonUtils.executeGalleon((options) -> {
                assertFalse(options.containsKey(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY));
                assertFalse(options.containsKey(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY));
            }, Paths.get("test"));

        } finally {
            // Reset original values
            if (cycloneDx == null) {
                System.clearProperty(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY);
            } else {
                System.setProperty(GalleonUtils.JBOSS_CYCLONEDX_PROPERTY, cycloneDx);
            }
            if (cycloneDxFailOnError == null) {
                System.clearProperty(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY);
            } else {
                System.setProperty(GalleonUtils.JBOSS_CYCLONEDX_FAIL_ON_ERROR_PROPERTY, cycloneDxFailOnError);
            }
        }
    }

    private static Path urlToPath(String url) {
        try {
            return Path.of(new URL(url).toURI());
        } catch (URISyntaxException | MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}