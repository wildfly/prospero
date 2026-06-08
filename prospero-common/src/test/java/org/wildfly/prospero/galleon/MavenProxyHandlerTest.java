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

import org.eclipse.aether.repository.RemoteRepository;
import org.junit.After;
import org.junit.Test;
import org.wildfly.channel.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNull;

public class MavenProxyHandlerTest {

    private RemoteRepository.Builder builder(Repository repository) {
        return new RemoteRepository.Builder(repository.getId(), "default", repository.getUrl());
    }

    @After
    public void clearProperties() {
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("http.proxyUser");
        System.clearProperty("http.proxyPassword");

        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("https.proxyUser");
        System.clearProperty("https.proxyPassword");

        System.clearProperty("http.nonProxyHosts");
    }

    @Test
    public void noProxiesAddedIfSettingsAreNotPresent() throws Exception {
        Repository repo = new Repository("test", "http://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        assertNull(builder.build().getProxy());
    }

    @Test
    public void addUnauthenticatedHttpProxy() throws Exception {
        System.setProperty("http.proxyHost", "http://proxy");
        System.setProperty("http.proxyPort", "8888");

        Repository repo = new Repository("test", "http://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        assertThat(builder.build().getProxy())
                .hasFieldOrPropertyWithValue("host", "http://proxy")
                .hasFieldOrPropertyWithValue("port", 8888);

        repo = new Repository("test", "https://foo.bar");
        builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);
        assertNull(builder.build().getProxy());
    }

    @Test
    public void addUnauthenticatedHttpsProxy() throws Exception {
        System.setProperty("https.proxyHost", "http://proxy");
        System.setProperty("https.proxyPort", "8888");

        Repository repo = new Repository("test", "https://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        assertThat(builder.build().getProxy())
                .hasFieldOrPropertyWithValue("host", "http://proxy")
                .hasFieldOrPropertyWithValue("port", 8888);

        repo = new Repository("test", "http://foo.bar");
        builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        assertNull(builder.build().getProxy());
    }

    @Test
    public void addAuthenticatedHttpsProxy() throws Exception {
        System.setProperty("https.proxyHost", "http://proxy");
        System.setProperty("https.proxyPort", "8888");
        System.setProperty("https.proxyUser", "test");
        System.setProperty("https.proxyPassword", "pwd");

        Repository repo = new Repository("test", "https://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        assertThat(builder.build().getProxy()).satisfies(proxy -> {
            assertThat(proxy.getHost()).isEqualTo("http://proxy");
            assertThat(proxy.getPort()).isEqualTo(8888);
            assertThat(proxy.getAuthentication()).isNotNull();
        });
    }

    @Test
    public void addAuthenticatedHttpProxy() throws Exception {
        System.setProperty("http.proxyHost", "http://proxy");
        System.setProperty("http.proxyPort", "8888");
        System.setProperty("http.proxyUser", "test");
        System.setProperty("http.proxyPassword", "pwd");

        Repository repo = new Repository("test", "http://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        MavenProxyHandler.addProxySettings(repo, builder);

        assertThat(builder.build().getProxy()).satisfies(proxy -> {
            assertThat(proxy.getHost()).isEqualTo("http://proxy");
            assertThat(proxy.getPort()).isEqualTo(8888);
            assertThat(proxy.getAuthentication()).isNotNull();
        });
    }

    @Test
    public void dontAddAuthenticationIfPartiallyConfigured() throws Exception {
        System.setProperty("http.proxyHost", "http://proxy");
        System.setProperty("http.proxyPort", "8888");
        System.setProperty("http.proxyPassword", "pwd");

        Repository repo = new Repository("test", "http://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        MavenProxyHandler.addProxySettings(repo, builder);

        assertThat(builder.build().getProxy()).satisfies(proxy -> {
            assertThat(proxy.getHost()).isEqualTo("http://proxy");
            assertThat(proxy.getPort()).isEqualTo(8888);
            assertThat(proxy.getAuthentication()).isNull();
        });
    }

    @Test
    public void defaultPortTo80IfNotPresent() throws Exception {
        System.setProperty("http.proxyHost", "http://proxy");

        Repository repo = new Repository("test", "http://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        assertThat(builder.build().getProxy())
                .hasFieldOrPropertyWithValue("host", "http://proxy")
                .hasFieldOrPropertyWithValue("port", 80);
    }

    @Test
    public void skipProxyIfHostExcluded() throws Exception {
        System.setProperty("http.proxyHost", "http://proxy");
        System.setProperty("http.nonProxyHosts", "foo.bar");

        Repository repo = new Repository("test", "http://foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);

        assertNull(builder.build().getProxy());
    }

    @Test
    public void skipProxyIfRepositoryUrlInvalid() throws Exception {
        System.setProperty("http.proxyHost", "http://proxy");

        Repository repo = new Repository("test", "https:foo.bar");
        RemoteRepository.Builder builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);
        assertNull(builder.build().getProxy());

        repo = new Repository("test", "https:foo.bar");
        builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);
        assertNull(builder.build().getProxy());

        repo = new Repository("test", "");
        builder = builder(repo);
        MavenProxyHandler.addProxySettings(repo, builder);
        assertNull(builder.build().getProxy());
    }

}