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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.io.FileUtils;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.ProvisioningBuilder;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;
import org.jboss.galleon.diff.FsDiff;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.UniverseResolver;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.jboss.logging.Logger;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

public class GalleonUtils {
    public static final String MAVEN_REPO_LOCAL = "maven.repo.local";
    protected static final String JBOSS_MODULES_SETTINGS_XML_URL = "jboss.modules.settings.xml.url";
    public static final String JBOSS_FORK_EMBEDDED_PROPERTY = "jboss-fork-embedded";
    public static final String JBOSS_FORK_EMBEDDED_VALUE = "true";
    public static final String JBOSS_BULK_RESOLVE_PROPERTY = "jboss-bulk-resolve-artifacts";
    public static final String JBOSS_BULK_RESOLVE_VALUE = "true";
    public static final String MODULE_PATH_PROPERTY = "module.path";
    public static final String PRINT_ONLY_CONFLICTS_PROPERTY = "print-only-conflicts";
    public static final String PRINT_ONLY_CONFLICTS_VALUE = "true";
    public static final String STORE_INPUT_PROVISIONING_CONFIG_PROPERTY = "store-input-provisioning-config";
    public static final String STORE_INPUT_PROVISIONING_CONFIG_VALUE = "true";
    public static final String STORE_PROVISIONED_ARTIFACTS = "jboss-resolved-artifacts-cache";
    public static final String STORE_PROVISIONED_ARTIFACTS_VALUE = ArtifactCache.CACHE_FOLDER.toString().replace(File.separatorChar, '/');

    private static final String CLASSPATH_SCHEME = "classpath";
    private static final String FILE_SCHEME = "file";
    private static final String OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES = "jboss-reset-embedded-system-properties";
    private static final boolean IS_MODULAR;

    private static final Logger logger = Logger.getLogger(GalleonUtils.class.getName());

    static {
        boolean modular;
        try {
            Class.forName("org.jboss.modules.ModuleClassLoader", false, ClassLoader.getSystemClassLoader());
            modular = true;
        } catch (ClassNotFoundException ex) {
            modular = false;
        }
        IS_MODULAR = modular;
    }

    public static void executeGalleon(GalleonExecution execution, Path localRepository) throws ProvisioningException, UnresolvedMavenArtifactException {
        final Map<String, String> substitutedProperties = new HashMap<>();
        try {
            substitutedProperties.putAll(substituteProvisioningProperties(localRepository));
            final Map<String, String> options = new HashMap<>();

            // we need to set FORK EMBEDDED to false when the logger is in DEBUG
            // https://github.com/wildfly/prospero/issues/1011
            if (logger.isDebugEnabled() && !IS_MODULAR) {
                logger.debug("Debug mode detected; disabling forked process to allow debugger attachment when start as a non JBoss Module way.");
                options.put(JBOSS_FORK_EMBEDDED_PROPERTY, "false");
            } else {
                //When prospero is started as a JBoss Module, we MUST fork the embedded server, we don't have other alternative.
                options.put(JBOSS_FORK_EMBEDDED_PROPERTY, JBOSS_FORK_EMBEDDED_VALUE);
            }

            options.put(JBOSS_BULK_RESOLVE_PROPERTY, JBOSS_BULK_RESOLVE_VALUE);
            options.put(PRINT_ONLY_CONFLICTS_PROPERTY, PRINT_ONLY_CONFLICTS_VALUE);
            options.put(STORE_INPUT_PROVISIONING_CONFIG_PROPERTY, STORE_INPUT_PROVISIONING_CONFIG_VALUE);
            options.put(STORE_PROVISIONED_ARTIFACTS, STORE_PROVISIONED_ARTIFACTS_VALUE);
            String resetSysProp = System.getProperty(OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES, "");
            if (!resetSysProp.equals("-")) {
                options.put(OPTION_RESET_EMBEDDED_SYSTEM_PROPERTIES, resetSysProp);
            }
            if (logger.isTraceEnabled()) {
                logger.trace("Executing galleon");
                logger.trace("System properties:");
                for (Object key : System.getProperties().keySet()) {
                    logger.trace("  " + key + ": " + System.getProperties().get(key));
                }
                logger.trace("System envs:");
                for (String key : System.getenv().keySet()) {
                    logger.trace("  " + key + ": " + System.getenv().get(key));
                }
                logger.trace("Galleon options:");
                for (String key : options.keySet()) {
                    logger.trace("  " + key + ": " + options.get(key));
                }
            }
            execution.execute(options);
        } catch (ProvisioningException e) {
            throw extractMavenException(e).orElseThrow(()->e);
        } finally {
            try {
                final Path tempSettingsXml = Path.of(new URL(System.getProperty(JBOSS_MODULES_SETTINGS_XML_URL)).toURI());
                FileUtils.deleteQuietly(tempSettingsXml.toFile());
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException("Unable to delete a temporary settings.xml file " + System.getProperty(JBOSS_MODULES_SETTINGS_XML_URL), e);
            }

            for (Map.Entry<String, String> property : substitutedProperties.entrySet()) {
                if (property.getValue() == null) {
                    System.clearProperty(property.getKey());
                } else {
                    System.setProperty(property.getKey(), property.getValue());
                }
            }
        }
    }

    private static Map<String, String> substituteProvisioningProperties(Path localRepository) throws ProvisioningException {
        Map<String, String> substitutedProperties = new HashMap<>();
        substitutedProperties.put(MAVEN_REPO_LOCAL, System.getProperty(MAVEN_REPO_LOCAL));
        System.setProperty(MAVEN_REPO_LOCAL, localRepository.toString());

        if (System.getProperty(MODULE_PATH_PROPERTY) != null) {
            substitutedProperties.put(MODULE_PATH_PROPERTY, System.getProperty(MODULE_PATH_PROPERTY));
            System.clearProperty(MODULE_PATH_PROPERTY);
        }
        if (System.getProperty("logging.configuration") != null) {
            substitutedProperties.put("logging.configuration", System.getProperty("logging.configuration"));
            System.clearProperty("logging.configuration");
        }
        // Set up empty settings.xml for jboss-modules to use. Avoids errors when the default settins.xml is corrupted.
        // We don't need the settings.xml when starting embedded server, as we set the local repository manually and
        // we download all dependencies before starting server.
        try {
            substitutedProperties.put(JBOSS_MODULES_SETTINGS_XML_URL, System.getProperty(JBOSS_MODULES_SETTINGS_XML_URL));
            final Path tempSettingsXml = Files.createTempFile("prospero-maven-settings", "xml");
            Files.writeString(tempSettingsXml, "<settings/>");
            System.setProperty(JBOSS_MODULES_SETTINGS_XML_URL, tempSettingsXml.toUri().toURL().toExternalForm());
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToCreateTemporaryDirectory(e);
        }

        return substitutedProperties;
    }

    private static Optional<UnresolvedMavenArtifactException> extractMavenException(Throwable e) {
        if (e instanceof UnresolvedMavenArtifactException) {
            return Optional.of((UnresolvedMavenArtifactException) e);
        } else if (e.getCause() != null) {
            return extractMavenException(e.getCause());
        }
        return Optional.empty();
    }

    public interface GalleonExecution {
        void execute(Map<String, String> options) throws ProvisioningException;
    }

    public interface ProvisioningManagerExecution {
        void execute(Provisioning provMgr, Map<String, String> options) throws ProvisioningException;
    }

    public static GalleonBuilder newGalleonBuilder(MavenRepoManager maven, java.util.function.Consumer<String> resolvedFps) throws ProvisioningException {
        GalleonBuilder provider = new GalleonBuilder();
        if (resolvedFps != null) {
            final UniverseResolver.Builder builder = UniverseResolver.builder()
                    .addArtifactResolver(maven);
            UniverseResolver universeResolver = new UniverseResolver(builder) {
                @Override
                public Path resolve(FeaturePackLocation fpl) throws ProvisioningException {
                    if (fpl.isMavenCoordinates()) {
                        final String[] split = fpl.getFPID().getProducer().getName().split(":");
                        resolvedFps.accept(split[0] + ":" + split[1]);
                    }
                    return super.resolve(fpl);
                }
            };
            provider.setUniverseResolver(universeResolver);
        } else {
            provider.addArtifactResolver(maven);
        }
        return provider;
    }

    public static Provisioning newProvisioning(GalleonBuilder provider, Path installDir, GalleonProvisioningConfig config, Path provisioningFile, boolean useDefaultCore) throws ProvisioningException {
        ProvisioningBuilder pBuilder;
        if (useDefaultCore) {
           pBuilder = provider.newProvisioningBuilder();
        } else {
            if (config != null) {
                pBuilder = provider.newProvisioningBuilder(config);
            } else {
                if(Files.exists(provisioningFile)) {
                    pBuilder = provider.newProvisioningBuilder(provisioningFile);
                } else {
                    pBuilder = provider.newProvisioningBuilder();
                }
            }
        }
        return pBuilder
                .setMessageWriter(new LoggingMessageWriter(logger))
                .setInstallationHome(installDir).build();
    }

    public static List<String> getInstalledPacks(Path dir) throws ProvisioningException {
        try (Provisioning provisioning = new GalleonBuilder().newProvisioningBuilder().build()) {
            return provisioning.getInstalledPacks(dir);
        }
    }

    public static GalleonProvisioningConfig readProvisioningConfig(URI uri) throws MetadataException {
        try {
            if (CLASSPATH_SCHEME.equals(uri.getScheme())) {
                InputStream is = GalleonUtils.class.getClassLoader().getResourceAsStream(uri.getSchemeSpecificPart());
                return loadProvisioningConfig(is);
            } else if (FILE_SCHEME.equals(uri.getScheme())) {
                try (Provisioning p = new GalleonBuilder().newProvisioningBuilder().build()) {
                    return p.loadProvisioningConfig(Path.of(uri));
                }
            } else {
                throw new IllegalArgumentException(String.format("Can't use scheme '%s' for Galleon provisioning.xml URI.",
                        uri.getScheme()));
            }
        } catch (ProvisioningException e) {
            // check if ProvisioningException wraps (somewhere in stack) XmlStreamException
            // if it does Galleon doesn't like the provisioning definition
            final Optional<XMLStreamException> xmlEx = getXmlException(e);
            if (xmlEx.isPresent()) {
                throw ProsperoLogger.ROOT_LOGGER.unableToParseConfigurationUri(uri, e);
            } else {
                throw new MetadataException(e.getLocalizedMessage(), e);
            }
        } catch (XMLStreamException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToParseConfigurationUri(uri, e);
        }
    }

    @Deprecated (forRemoval = true)
    public static GalleonProvisioningConfig loadProvisioningConfig(InputStream is) throws ProvisioningException, XMLStreamException {
        try(Provisioning p = new GalleonBuilder().newProvisioningBuilder().build()) {
            return p.loadProvisioningConfig(is);
        }
    }

    @Deprecated (forRemoval = true)
    public static GalleonProvisioningConfig loadProvisioningConfig(URI uri) throws ProvisioningException, XMLStreamException {
        if (CLASSPATH_SCHEME.equals(uri.getScheme())) {
            InputStream is = GalleonUtils.class.getClassLoader().getResourceAsStream(uri.getSchemeSpecificPart());
            return loadProvisioningConfig(is);
        } else if (FILE_SCHEME.equals(uri.getScheme())) {
            try (Provisioning p = new GalleonBuilder().newProvisioningBuilder().build()) {
                return p.loadProvisioningConfig(Path.of(uri));
            } catch (ProvisioningException e) {
                // check if ProvisioningException wraps (somewhere in stack) XmlStreamException
                // if it does Galleon doesn't like the provisioning definition
                // TODO: when possible (Prospero 2.0??) change this method to NOT throw XMLStreamException and handle it properly
                final Optional<XMLStreamException> xmlEx = getXmlException(e);
                if (xmlEx.isPresent()) {
                    throw xmlEx.get();
                } else {
                    throw e;
                }
            }
        } else {
            throw new IllegalArgumentException(String.format("Can't use scheme '%s' for Galleon provisioning.xml URI.",
                    uri.getScheme()));
        }
    }

    private static Optional<XMLStreamException> getXmlException(ProvisioningException rootException) {
        Throwable e = rootException;
        while (e.getCause() != null) {
            if (e.getCause() instanceof XMLStreamException) {
                return Optional.of((XMLStreamException) e.getCause());
            } else {
                e = e.getCause();
            }
        }

        return Optional.empty();
    }

    /**
     * Returns list of files in the installation folder with their status (changed/added/removed)
     *
     * @param root
     * @return
     * @throws ProvisioningException
     * @throws OperationException
     */
    public static FsDiff findChanges(Path root) throws ProvisioningException, OperationException {
        // offline is enough - we just need to read the configuration
        final MavenOptions mavenOptions = MavenOptions.builder()
                .setOffline(true)
                .setNoLocalCache(true)
                .build();
        try (GalleonEnvironment galleonEnv = GalleonEnvironment.builder(root, Collections.emptyList(),
                        new MavenSessionManager(mavenOptions), true)
                .build()) {
            return galleonEnv.getProvisioning().getFsDiff();
        }
    }

}
