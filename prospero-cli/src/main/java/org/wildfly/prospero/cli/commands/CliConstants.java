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

package org.wildfly.prospero.cli.commands;

/**
 * CLI string constants
 */
public final class CliConstants {
    // keep the constants sorted

    private CliConstants() {
    }

    /**
     * Command names
     */
    public static final class Commands {

        private Commands() {
        }

        public static final String ADD = "add";
        public static final String APPLY = "apply";
        public static final String CHANNEL = "channel";
        public static final String CLONE = "clone";
        public static final String CUSTOMIZATION_INIT_CHANNEL = "init";
        public static final String CUSTOMIZATION_INITIALIZE_CHANNEL = "initialize";
        public static final String CUSTOMIZATION_PROMOTE = "promote";
        public static final String EXPORT = "export";
        public static final String FEATURE_PACKS = "feature-pack";
        public static final String FEATURE_PACKS_ALIAS = "fp";
        public static final String HISTORY = "history";
        public static final String INSTALL = "install";
        public static final String LIST = "list";
        public static final String LIST_CHANNELS = "list-channels";
        public static final String PERFORM = "perform";
        public static final String PREPARE = "prepare";
        public static final String PRINT_LICENSES = "print-licenses";
        public static final String RECREATE = "recreate";
        public static final String REMOVE = "remove";
        public static final String REVERT = "revert";
        public static final String SUBSCRIBE = "subscribe";
        public static final String UPDATE = "update";
        protected static final String VERSIONS = "versions";
    }

    public static final String ACCEPT_AGREEMENTS = "--accept-license-agreements";
    public static final String ALL = "--all";
    public static final String ARG_PATH = "--path";
    public static final String CANDIDATE_DIR = "--candidate-dir";
    public static final String CHANNEL = "--channel";
    public static final String CHANNEL_NAME = "--channel-name";
    public static final String CHANNELS = "--channels";
    public static final String CHANNEL_MANIFEST = "--manifest";
    public static final String CHANNEL_MANIFEST_REFERENCE = "<manifest-reference>";
    public static final String CHANNEL_REFERENCE = "<channel-reference>";
    public static final String CONFIG_STABILITY_LEVEL = "--config-stability-level";
    public static final String CUSTOMIZATION_ARCHIVE = "--archive";
    public static final String CUSTOMIZATION_REPOSITORY_URL = "--repository-url";
    public static final String DEBUG = "--debug";
    public static final String DEFINITION = "--definition";
    public static final String DIR = "--dir";
    public static final String FEATURE_PACK_REFERENCE = "<feature-pack-reference>";
    public static final String FPL = "--fpl";
    public static final String FULL = "--full";
    public static final String H = "-h";
    public static final String HELP = "--help";
    public static final String LAYERS = "--layers";
    public static final String LIST_PROFILES = "--list-profiles";
    public static final String LOCAL_CACHE = "--local-cache";
    public static final String OFFLINE = "--offline";
    public static final String PACKAGE_STABILITY_LEVEL = "--package-stability-level";
    public static final String PATH = "<path>";
    public static final String PRODUCT = "--product";
    public static final String PROFILE = "--profile";
    public static final String PROFILE_REFERENCE = "<installation-profile>";
    public static final String REMOVE = "--rm";
    public static final String REPO_URL = "<repo-url>";
    public static final String REPOSITORIES = "--repositories";
    public static final String REVISION = "--revision";
    public static final String SELF = "--self";
    public static final String SHADE_REPOSITORIES = "--shade-repositories";
    public static final String STABILITY_LEVEL = "--stability-level";
    public static final String USE_LOCAL_MAVEN_CACHE = "--use-default-local-cache";
    public static final String TARGET_CONFIG = "--target-config";
    public static final String V = "-v";
    public static final String VERBOSE = "--verbose";
    public static final String VERSION = "--version";
    public static final String MANIFEST_VERSIONS = "--manifest-versions";
    public static final String VV = "-vv";
    public static final String Y = "-y";
    public static final String YES = "--yes";
    public static final String NO_CONFLICTS_ONLY = "--no-conflicts-only";
    public static final String DRY_RUN = "--dry-run";

}
