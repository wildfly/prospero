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

package org.wildfly.prospero.it;

import java.util.List;

import org.wildfly.prospero.api.ArtifactChange;
import org.wildfly.prospero.api.ChannelVersionChange;
import org.wildfly.prospero.cli.CliConsole;

public class AcceptingConsole extends CliConsole {

    @Override
    public void updatesFound(List<ArtifactChange> artifactChanges, List<ChannelVersionChange> manifestChanges) {
        // no op
    }

    @Override
    public boolean confirmUpdates() {
        return true;
    }

    @Override
    public boolean confirm(String prompt, String accepted, String cancelled) {
        return true;
    }

    @Override
    public void updatesComplete() {
        // no op
    }

    @Override
    public void buildUpdatesComplete() {
    }

    @Override
    public boolean confirmBuildUpdates() {
        return true;
    }
}
