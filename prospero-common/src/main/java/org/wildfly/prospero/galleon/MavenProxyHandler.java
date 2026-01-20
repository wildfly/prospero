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
package org.wildfly.prospero.galleon;

import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.jboss.logging.Logger;
import org.wildfly.channel.Repository;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Configures repository proxy settings based on java proxy system properties like:
 * <code>
 *     https.proxyHost=http://proxyhost
 *     https.proxyPort=8080
 *     https.proxyUser=username
 *     https.proxyPassword=password
 * </code>
 */
class MavenProxyHandler implements RepositoryConversionHandler {
    private static final Logger LOG = Logger.getLogger(GalleonEnvironment.class.getName());

    @Override
    public void accept(Repository repository, RemoteRepository.Builder builder) {
        addProxySettings(repository, builder);
    }

    static void addProxySettings(Repository repository, RemoteRepository.Builder remoteRepositoryBuilder) {
        getDefinedProxy(repository).ifPresent(remoteRepositoryBuilder::setProxy);
    }

    private static Optional<Proxy> getDefinedProxy(Repository r) {
        final URI repositoryUri;
        try {
            repositoryUri = new URI(r.getUrl());
            if (repositoryUri.getScheme() == null || !repositoryUri.getScheme().equals("http") && !repositoryUri.getScheme().equals("https")) {
                LOG.debugf("Skipping proxy configuration for %s - scheme not supported", r.getUrl());
                return Optional.empty();
            }
            if (repositoryUri.getHost() == null) {
                LOG.infof("Skipping proxy configuration for %s - unable to find host", r.getUrl());
                return Optional.empty();
            }
        } catch (URISyntaxException e) {
            LOG.infof("Skipping proxy configuration for %s - unable to parse address", r.getUrl());
            return Optional.empty();
        }


        return ProxySelector.getDefault()
                .select(repositoryUri)
                .stream().filter(p -> p.type() == java.net.Proxy.Type.HTTP)
                .map(java.net.Proxy::address)
                .map(InetSocketAddress.class::cast)
                .map(a -> {
                    final String username = getProperty(repositoryUri.getScheme(), "proxyUser");
                    final String password = getProperty(repositoryUri.getScheme(), "proxyPassword");
                    return createProxySettings(a, username, password);
                })
                .findFirst();
    }

    private static String getProperty(String scheme, String suffix) {
        if (scheme.equals("https")) {
            return System.getProperty("https." + suffix);
        } else if (scheme.equals("http")) {
            return System.getProperty("http." + suffix);
        } else {
            return null;
        }
    }

    private static Proxy createProxySettings(InetSocketAddress proxyAddress, String username, String password) {
        if (username != null && password != null) {
            if (LOG.isTraceEnabled()) {
                LOG.tracef("Creating authenticator for the proxy");
            }
            final Authentication authentication = new AuthenticationBuilder()
                    .addUsername(username)
                    .addPassword(password)
                    .build();
            return new Proxy(null, proxyAddress.getHostName(), proxyAddress.getPort(), authentication);
        } else {
            return new Proxy(null, proxyAddress.getHostName(), proxyAddress.getPort());
        }
    }
}
