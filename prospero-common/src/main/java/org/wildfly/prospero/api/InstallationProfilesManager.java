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

package org.wildfly.prospero.api;

import org.jboss.logging.Logger;
import org.wildfly.prospero.model.InstallationProfile;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defines well known Galleon feature-packs.
 */
public abstract class InstallationProfilesManager {

    private static final Logger logger = Logger.getLogger(InstallationProfilesManager.class);

    private static final Map<String, InstallationProfile> nameMap = new HashMap<>();

    public static InstallationProfile getByName(String name) {
        return nameMap.get(name);
    }

    public static boolean isWellKnownName(String name) {
        return nameMap.containsKey(name);
    }

    public static Set<String> getNames() {
        return nameMap.keySet();
    }

    static {

        final URL knownRepoUrl = findProfileDefinitions();
        if (knownRepoUrl == null) {
            logger.debug("No known repositories found");
        } else {
            logger.debug("Loading known provisioning configurations from: " + knownRepoUrl);
            final List<InstallationProfile> installationProfiles;
            try {
                installationProfiles = InstallationProfile.readConfig(knownRepoUrl);
                for (InstallationProfile fp : installationProfiles) {
                    nameMap.put(fp.getName(), fp);
                }
            } catch (IOException e) {
                logger.warn("Failed to load provisioning configurations from: " + knownRepoUrl);
                logger.debug("Error parsing provisioning configurations:", e);
            }
        }
    }

    private static URL findProfileDefinitions() {
        final URL definition = InstallationProfilesManager.class.getClassLoader().getResource("prospero-installation-profiles.yaml");
        if (definition != null) {
            return definition;
        } else {
            return InstallationProfilesManager.class.getClassLoader().getResource("prospero-known-combinations.yaml");
        }
    }

}
