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

package org.wildfly.prospero.cli;

import java.nio.file.Path;
import java.util.List;

import org.jboss.galleon.ProvisioningException;
import org.wildfly.channel.Channel;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.actions.FeaturesAddAction;
import org.wildfly.prospero.actions.SubscribeNewServerAction;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.actions.InstallationExportAction;
import org.wildfly.prospero.actions.InstallationHistoryAction;
import org.wildfly.prospero.actions.InstallationRestoreAction;
import org.wildfly.prospero.actions.MetadataAction;
import org.wildfly.prospero.actions.PromoteArtifactBundleAction;
import org.wildfly.prospero.actions.ProvisioningAction;
import org.wildfly.prospero.actions.UpdateAction;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.api.exceptions.MetadataException;

public class ActionFactory {

    public ProvisioningAction install(Path targetPath, MavenOptions mavenOptions, Console console) throws ProvisioningException {
        return new ProvisioningAction(targetPath, mavenOptions, console);
    }

    @Deprecated(forRemoval = true)
    public UpdateAction update(Path targetPath, MavenOptions mavenOptions, Console console, List<Repository> additionalRepositories)
            throws OperationException,
            ProvisioningException {
        return new UpdateAction(targetPath, mavenOptions, console, additionalRepositories);
    }

    public UpdateAction update(Path targetPath, List<Channel> overrideChannels, MavenOptions mavenOptions, Console console)
            throws OperationException, ProvisioningException {
        return new UpdateAction(targetPath, overrideChannels, mavenOptions, console);
    }

    public ApplyCandidateAction applyUpdate(Path installationPath, Path updatePath)
            throws OperationException,
            ProvisioningException {
        return new ApplyCandidateAction(installationPath, updatePath);
    }

    public InstallationHistoryAction history(Path targetPath, Console console) {
        return new InstallationHistoryAction(targetPath, console);
    }

    public MetadataAction metadataActions(Path targetPath) throws MetadataException {
        return new MetadataAction(targetPath);
    }

    public PromoteArtifactBundleAction promoter(Console console) {
        return new PromoteArtifactBundleAction(console);
    }

    public InstallationExportAction exportAction(Path targetPath) {
        return new InstallationExportAction(targetPath);
    }

    public InstallationRestoreAction restoreAction(Path targetPath, MavenOptions mavenOptions, Console console) throws ProvisioningException {
        return new InstallationRestoreAction(targetPath, mavenOptions, console);
    }

    public FeaturesAddAction featuresAddAction(Path installationDir, MavenOptions mavenOptions, List<Repository> repositories, Console console)
            throws ProvisioningException, MetadataException {
        return new FeaturesAddAction(mavenOptions, installationDir, repositories, console);
    }

    public SubscribeNewServerAction subscribeNewServerAction(MavenOptions mvnOptions, Console console) throws ProvisioningException {
        return new SubscribeNewServerAction(mvnOptions, console);
    }
}
