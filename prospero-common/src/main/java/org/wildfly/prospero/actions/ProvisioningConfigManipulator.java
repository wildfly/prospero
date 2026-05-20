/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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

import org.jboss.galleon.ProvisioningDescriptionException;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.config.GalleonConfigurationWithLayers;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.config.ConfigId;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.wildfly.prospero.galleon.FeaturePackLocationParser;

class ProvisioningConfigManipulator {

    private final GalleonProvisioningConfig.Builder configBuilder;

    public ProvisioningConfigManipulator(GalleonProvisioningConfig.Builder configBuilder) {
        this.configBuilder = configBuilder;
    }

    /**
     * removes a feature-pack definition with name {@code fpName}.
     *
     * @param fpName    - the identifier (groupId:artifactId) of the feature-pack to be replaced
     * @return      - the position in the provisioning configuration that the removed feature-pack was at
     * @throws ProvisioningException - when the feature-pack is not defined in the builder
     */
    int removeFeaturePackDefinition(String fpName) throws ProvisioningException {
        final FeaturePackLocation.ProducerSpec depFpl = FeaturePackLocationParser.resolveFpl(fpName).getProducer();
        final int fpIndex = configBuilder.getFeaturePackDepIndex(depFpl.getLocation());
        configBuilder.removeFeaturePackDep(depFpl.getLocation());
        return fpIndex;
    }

    /**
     * removes {@code fpName} from the {@code configBuilder} and replaces it with a transitive dependency preserving all configurations
     *
     * @param fpName            - the identifier (groupId:artifactId) of the feature-pack to be replaced
     * @param originalConfig    - the provisioning configuration used to create the builder
     * @return      - the position in the provisioning configuration that the removed feature-pack was at
     * @throws ProvisioningException - if the feature-pack is not defined in the builder or the transitive dependency cannot be added
     */
    int convertToTransitiveDep(String fpName, GalleonProvisioningConfig originalConfig) throws ProvisioningException {
        final FeaturePackLocation.ProducerSpec depFpl = FeaturePackLocationParser.resolveFpl(fpName).getProducer();
        final GalleonFeaturePackConfig oldConfig = originalConfig.getFeaturePackDep(depFpl);
        final int fpIndex = configBuilder.getFeaturePackDepIndex(depFpl.getLocation());
        configBuilder.removeFeaturePackDep(oldConfig.getLocation());

        // replace it with a transitive config
        final GalleonFeaturePackConfig.Builder newFpBuilder = GalleonFeaturePackConfig.transitiveBuilder(oldConfig.getLocation());
        copyFeaturePackConfig(oldConfig, newFpBuilder);
        configBuilder.addFeaturePackDep(newFpBuilder.build());

        return fpIndex;
    }

    /**
     * copies settings from {@code originalConfig} to {@code configBuilder}.
     *
     * @param originalConfig    -   the source feature-pack configuration that will be copied over
     * @param configBuilder     -   a builder that the {@code originalConfig} configuration will be copied over
     * @throws ProvisioningDescriptionException - if the builder configuration conflicts with the source configuration
     */
    static void copyFeaturePackConfig(GalleonFeaturePackConfig originalConfig, GalleonFeaturePackConfig.Builder configBuilder) throws ProvisioningDescriptionException {
        if (originalConfig.getInheritPackages() != null) {
            configBuilder.setInheritPackages(originalConfig.getInheritPackages());
        }
        for (String excludedPackage : originalConfig.getExcludedPackages()) {
            configBuilder.excludePackage(excludedPackage);
        }
        for (String includedPackage : originalConfig.getIncludedPackages()) {
            configBuilder.includePackage(includedPackage);
        }
        for (FeaturePackLocation.FPID patch : originalConfig.getPatches()) {
            configBuilder.addPatch(patch);
        }

        if (originalConfig.getInheritConfigs() != null) {
            configBuilder.setInheritConfigs(originalConfig.getInheritConfigs());
        }
        configBuilder.setInheritModelOnlyConfigs(originalConfig.isInheritModelOnlyConfigs());
        for (String model : originalConfig.getFullModelsIncluded()) {
            configBuilder.includeConfigModel(model);
        }
        for (String model : originalConfig.getFullModelsExcluded().keySet()) {
            configBuilder.excludeConfigModel(model);
        }
        for (ConfigId config : originalConfig.getIncludedConfigs()) {
            configBuilder.includeDefaultConfig(config);
        }
        for (ConfigId config : originalConfig.getExcludedConfigs()) {
            configBuilder.excludeDefaultConfig(config);
        }
        for (GalleonConfigurationWithLayers config : originalConfig.getDefinedConfigs()) {
            configBuilder.addConfig(config);
        }
    }
}
