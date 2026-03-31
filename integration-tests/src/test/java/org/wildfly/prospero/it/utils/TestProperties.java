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

package org.wildfly.prospero.it.utils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Gives access to build properties defined in pom
 */
public class TestProperties {
    static {
        try {
            final Properties properties = new Properties();
            properties.load(TestProperties.class.getClassLoader().getResourceAsStream("properties-from-pom.properties"));
            WF_CHANNEL_GROUP_ID = properties.getProperty("prospero.test.base.channel.groupId");
            WF_CHANNEL_ARTIFACT_ID = properties.getProperty("prospero.test.base.channel.artifactId");
            WF_CHANNEL_VERSION = properties.getProperty("prospero.test.base.channel.version");
            final String testRepoUrls = properties.getProperty("prospero.test.base.repositories");
            TEST_REPO_URLS = Arrays.asList(testRepoUrls.split(","));
            DATASOURCE_FP_GROUPID = properties.getProperty("prospero.test.datasources-feature-pack.groupId");
            DATASOURCE_FP_ARTIFACTID = properties.getProperty("prospero.test.datasources-feature-pack.artifactId");
            DATASOURCE_FP_VERSION = properties.getProperty("prospero.test.datasources-feature-pack.version");
            SERVER_PROFILE = properties.getProperty("prospero.test.server.profile");
        } catch (IOException e) {
            throw new RuntimeException("Unable to read properties file", e);
        }
    }

    public static final String WF_CHANNEL_GROUP_ID;
    public static final String WF_CHANNEL_ARTIFACT_ID;
    public static final String WF_CHANNEL_VERSION;
    public static final List<String> TEST_REPO_URLS;
    public static final String DATASOURCE_FP_GROUPID;
    public static final String DATASOURCE_FP_ARTIFACTID;
    public static final String DATASOURCE_FP_VERSION;
    public static final String SERVER_PROFILE;

    public static List<URL> testReposToUrls() {
        return TEST_REPO_URLS.stream().map(url -> {
            try {
                return new URL(url);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }).toList();
    }

}
