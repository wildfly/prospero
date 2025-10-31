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

package org.wildfly.prospero.installation.git;

import org.jboss.galleon.config.ConfigModel;
import org.jboss.galleon.config.ProvisioningConfig;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.xml.ProvisioningXmlWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.wildfly.prospero.api.Diff;
import org.wildfly.prospero.api.FeatureChange;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.wildfly.prospero.api.FeatureChange.Type.CONFIG;
import static org.wildfly.prospero.api.FeatureChange.Type.FEATURE;
import static org.wildfly.prospero.api.FeatureChange.Type.LAYERS;


// some edge cases, other tests are in GitStorageTest
public class FeatureChangeParserTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void marksEverythingAsRemovedIfNewConfigDoesntExist() throws Exception {
        final Path oldConfigDir = temp.newFolder("old").toPath();
        ProvisioningConfig config = ProvisioningConfig.builder()
                .addFeaturePackDep(FeaturePackLocation.fromString("org.test:feature-one:zip"))
                .addConfig(ConfigModel.builder("model-one", "name-one")
                        .includeLayer("layer-one")
                        .build())
                .build();
        final Path provisioningXml = oldConfigDir.resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML);
        ProvisioningXmlWriter.getInstance().write(config, provisioningXml);
        final List<FeatureChange> changes = new FeatureChangeParser().parse(null, Files.readString(provisioningXml));

        assertThat(changes)
                .containsOnly(
                        new FeatureChange(FEATURE, "org.test:feature-one:zip", Diff.Status.REMOVED),
                        new FeatureChange(CONFIG, "model-one:name-one", Diff.Status.REMOVED,
                                new FeatureChange(LAYERS, "layer-one", null))
                );
    }

    @Test
    public void marksEverythingAsAddedIfOldConfigDoesntExist() throws Exception {
        final Path newConfigDir = temp.newFolder("new").toPath();
        ProvisioningConfig config = ProvisioningConfig.builder()
                .addFeaturePackDep(FeaturePackLocation.fromString("org.test:feature-one:zip"))
                .addConfig(ConfigModel.builder("model-one", "name-one")
                        .includeLayer("layer-one")
                        .build())
                .build();
        final Path provisioningXml = newConfigDir.resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML);
        ProvisioningXmlWriter.getInstance().write(config, provisioningXml);
        final List<FeatureChange> changes = new FeatureChangeParser().parse(Files.readString(provisioningXml), null);

        assertThat(changes)
                .containsOnly(
                        new FeatureChange(FEATURE, "org.test:feature-one:zip", Diff.Status.ADDED),
                        new FeatureChange(CONFIG, "model-one:name-one", Diff.Status.ADDED,
                                new FeatureChange(LAYERS, null, "layer-one"))
                );
    }
}