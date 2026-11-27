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

package org.wildfly.prospero.wfchannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.io.FileUtils;
import org.eclipse.aether.repository.LocalRepository;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.jboss.galleon.ProvisioningException;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.MavenOptions;

public class MavenSessionManager {

    public static final Path LOCAL_MAVEN_REPO = Paths.get(System.getProperty("user.home"), ".m2", "repository");
    private static final String AETHER_OFFLINE_PROTOCOLS_PROPERTY = "aether.offline.protocols";
    private static final String AETHER_USE_SYSTEM_PROPERTIES = "aether.connector.http.useSystemProperties";
    public static final String AETHER_OFFLINE_PROTOCOLS_VALUE = "file";
    private final Path provisioningRepo;
    private boolean offline;

    public MavenSessionManager(MavenOptions mavenOptions) throws ProvisioningException {
        Objects.requireNonNull(mavenOptions);

        this.offline = mavenOptions.isOffline();

        if (mavenOptions.isNoLocalCache()) {
            // generate temp folder
            try {
                this.provisioningRepo = Files.createTempDirectory("provisioning-repo");
                Runtime.getRuntime().addShutdownHook(new Thread(() -> FileUtils.deleteQuietly(this.provisioningRepo.toFile())));
            } catch (IOException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToCreateCache(e);
            }
        } else if (!mavenOptions.overridesLocalCache()) {
            this.provisioningRepo = LOCAL_MAVEN_REPO;
        } else {
            this.provisioningRepo = mavenOptions.getLocalCache().toAbsolutePath();
        }
    }

    public MavenSessionManager(MavenSessionManager base) {
        this.offline = base.isOffline();
        this.provisioningRepo = base.provisioningRepo;
    }

    public MavenSessionManager() throws ProvisioningException {
        this(MavenOptions.DEFAULT_OPTIONS);
    }

    public RepositorySystem newRepositorySystem() {
        final DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                throw ProsperoLogger.ROOT_LOGGER.failedToInitMaven(exception);
            }
        });
        return locator.getService(RepositorySystem.class);
    }

    public DefaultRepositorySystemSession newRepositorySystemSession(RepositorySystem system) {
        final DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

        final LocalRepository localRepo = new LocalRepository(provisioningRepo.toAbsolutePath().toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        session.setConfigProperty(AETHER_OFFLINE_PROTOCOLS_PROPERTY, AETHER_OFFLINE_PROTOCOLS_VALUE);
        session.setOffline(offline);

        // Allow configuring aether and potentially other Maven config properties from the Java system properties.
        // This would be done in Maven. This was added to be able to configure retry handler and various connection
        // timeouts in cases when infrastructure is not behaving very reliably.
        Map<String, Object> configProps = new HashMap<>(session.getConfigProperties());
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("aether.") || name.startsWith("maven.") || name.startsWith("http.")) {
                configProps.put(name, System.getProperty(name));
            }
        }
        session.setConfigProperties(configProps);

        return session;
    }

    public Path getProvisioningRepo() {
        return provisioningRepo;
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
    }

    public boolean isOffline() {
        return offline;
    }
}
