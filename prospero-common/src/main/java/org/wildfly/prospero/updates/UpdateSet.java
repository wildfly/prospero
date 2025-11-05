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

package org.wildfly.prospero.updates;

import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.ChannelVersionChange;

import java.util.Collections;
import java.util.List;

public class UpdateSet {

    public static final UpdateSet EMPTY = new UpdateSet(Collections.emptyList());
    private final List<ArtifactChange> artifactUpdates;
    private final List<ChannelVersionChange> channelChanges;

    public UpdateSet(List<ArtifactChange> updates) {
        this(updates, Collections.emptyList());
    }

    public UpdateSet(List<ArtifactChange> updates, List<ChannelVersionChange> channelChanges) {
        this.artifactUpdates = updates;
        this.channelChanges = channelChanges;
    }

    public List<ArtifactChange> getArtifactUpdates() {
        return artifactUpdates;
    }

    public boolean isEmpty() {
        return artifactUpdates.isEmpty();
    }

    public List<ChannelVersionChange> getChannelVersionChanges() {
        return channelChanges;
    }
}
