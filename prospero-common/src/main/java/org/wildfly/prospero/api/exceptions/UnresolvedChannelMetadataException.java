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

package org.wildfly.prospero.api.exceptions;

import org.wildfly.channel.ChannelMetadataCoordinate;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.api.ArtifactUtils;

import java.util.Set;

public class UnresolvedChannelMetadataException extends OperationException {

    private final Set<ChannelMetadataCoordinate> missingArtifacts;
    private final Set<Repository> repositories;
    private final boolean offline;

    public UnresolvedChannelMetadataException(String msg, Throwable cause, Set<ChannelMetadataCoordinate> missingArtifacts,
                                              Set<Repository> repositories, boolean offline) {
        super(ArtifactResolutionException.withDetails(msg, missingArtifacts, ArtifactUtils::printCoordinate, repositories), cause);
        this.missingArtifacts = missingArtifacts;
        this.repositories = repositories;
        this.offline = offline;

    }

    public Set<ChannelMetadataCoordinate> getMissingArtifacts() {
        return missingArtifacts;
    }

    public Set<Repository> getRepositories() {
        return repositories;
    }

    public boolean isOffline() {
        return offline;
    }
}
