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

import org.junit.Test;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifestCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.api.exceptions.MetadataException;

import java.net.MalformedURLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ChannelManifestSubstitutorTest {
    @Test
    public void testChannelManifestSubstituted() throws MalformedURLException, MetadataException {
        String url = "file:${propName}/examples/wildfly-27.0.0.Alpha2-manifest.yaml";
        final ChannelManifestSubstitutor substitutor = new ChannelManifestSubstitutor(Map.of("propName", "propValue"));
        String expected = "file:propValue/examples/wildfly-27.0.0.Alpha2-manifest.yaml";
        Channel channel = new Channel("channel1", "", null, List.of(new Repository("test", "http://test.org")),
                ChannelManifestCoordinate.create(url, null), null, null);
        Channel substitutedChannel = substitutor.substitute(channel);
        System.clearProperty("propName");
        assertEquals(expected, substitutedChannel.getManifestCoordinate().getUrl().toString());
    }

    @Test
    public void testChannelManifestNotSubstituted() throws MalformedURLException, MetadataException {
        String url = "file:/Users/examples/wildfly-27.0.0.Alpha2-manifest.yaml";
        final ChannelManifestSubstitutor substitutor = new ChannelManifestSubstitutor(Collections.emptyMap());
        Channel channel = new Channel("channel1", "", null, List.of(new Repository("test", "http://test.org")),
                ChannelManifestCoordinate.create(url, null), null, null);
        Channel substitutedChannel = substitutor.substitute(channel);
        assertEquals(url, substitutedChannel.getManifestCoordinate().getUrl().toString());
    }

    @Test
    public void testChannelWithoutManifestNotSubstituted() throws Exception {
        final ChannelManifestSubstitutor substitutor = new ChannelManifestSubstitutor(Collections.emptyMap());
        Channel channel = new Channel("channel1", "", null, List.of(new Repository("test", "http://test.org")),
                null, null, null);
        Channel substitutedChannel = substitutor.substitute(channel);
        assertEquals(channel, substitutedChannel);
    }
}
