/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.creator.FeaturePackBuilder;
import org.jboss.galleon.creator.FeaturePackCreator;
import org.jboss.galleon.creator.PackageBuilder;
import org.jboss.galleon.repo.RepositoryArtifactResolver;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.jboss.galleon.universe.maven.repo.SimplisticMavenRepoManager;
import org.wildfly.channel.Channel;
import org.wildfly.channel.Repository;
import org.wildfly.prospero.actions.InstallationHistoryAction;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.FileConflict;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.cli.commands.CliConstants;
import org.wildfly.prospero.it.AcceptingConsole;
import org.wildfly.prospero.it.ExecutionUtils;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;

/**
 * A simple Galleon feature pack creating one or more module(s) with artifact(s) provisioned by Prospero.
 */
public class TestInstallation {

    private final Path serverRoot;

    public TestInstallation(Path serverRoot) {
        this.serverRoot = serverRoot;
    }

    public static Builder fpBuilder(String name) {
        return new Builder(name);
    }

    public void verifyModuleJar(String groupId, String artifactId, String version) {
        final Path moduleRoot = serverRoot.resolve("modules").resolve(groupId.replace('.', '/') + "/" + artifactId + "/main");

        assertThat(moduleRoot.resolve("module.xml"))
                .exists()
                .content().contains(artifactId + "-" + version + ".jar");
        assertThat(moduleRoot.resolve(artifactId + "-" + version + ".jar"))
                .exists();
    }

    /**
     * verifies that required files in .installation folder exist
     */
    public void verifyInstallationMetadataPresent() {
        final Path metadataRoot = serverRoot.resolve(ProsperoMetadataUtils.METADATA_DIR);
        assertThat(metadataRoot).exists();
        assertThat(metadataRoot.resolve(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME)).exists();
        assertThat(metadataRoot.resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME)).exists();
        assertThat(metadataRoot.resolve(ProsperoMetadataUtils.CURRENT_VERSION_FILE)).exists();
    }

    public InstallationMetadata readInstallationMetadata() throws MetadataException {
        return InstallationMetadata.loadInstallation(serverRoot);
    }

    /**
     * see {@link #install(String, List, Console, List)}
     *
     * @param fplName
     * @param channels
     * @param args
     * @throws ProvisioningException
     * @throws MalformedURLException
     * @throws OperationException
     */
    public void install(String fplName, List<Channel> channels, String... args) throws Exception {
        install(fplName, channels, new AcceptingConsole(), args);
    }

    /**
     * see {@link #install(String, List, Console, List)}
     *
     * @param fplName
     * @param channels
     * @param console
     * @throws ProvisioningException
     * @throws MalformedURLException
     * @throws OperationException
     */
    public void install(String fplName, List<Channel> channels, Console console, String... args)
            throws Exception {

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("provisioning", "xml");

            ProsperoMetadataUtils.writeChannelsConfiguration(tempFile, channels);
            final ArrayList<String> argList = new ArrayList<>();
            argList.add(CliConstants.Commands.INSTALL);
            argList.add(CliConstants.CHANNELS);
            argList.add(tempFile.toAbsolutePath().toString());
            argList.add(CliConstants.FPL);
            argList.add(fplName);
            argList.add(CliConstants.DIR);
            argList.add(serverRoot.toAbsolutePath().toString());

            Collections.addAll(argList, args);

            ExecutionUtils.prosperoExecution(argList.toArray(new String[]{}))
                    .withTimeLimit(10, TimeUnit.MINUTES)
                    .execute()
                    .assertReturnCode(ReturnCodes.SUCCESS);

        } finally {
            if (tempFile != null) {
                FileUtils.deleteQuietly(tempFile.toFile());
            }
        }
    }

    /**
     * provision a feature pack with name {@name fplName}.
     *
     * @param fplName
     * @param channels
     * @param console
     * @param overrideRepositoryUrls
     * @throws ProvisioningException
     * @throws MalformedURLException
     * @throws OperationException
     */
    public void install(String fplName, List<Channel> channels, Console console, List<URL> overrideRepositoryUrls) throws Exception {
        final StringBuilder repoUrls = new StringBuilder();
        IntStream.range(0, overrideRepositoryUrls.size())
                .forEach(i->repoUrls.append("test-").append(i).append("::").append(overrideRepositoryUrls.get(i).toExternalForm()));

        install(fplName, channels, console, CliConstants.REPOSITORIES, repoUrls.toString());
    }

    /**
     * see {@link #update(Console)}
     * @return
     * @throws ProvisioningException
     * @throws OperationException
     */
    public List<FileConflict> update(String... args) throws Exception {
        return update(List.of(Pair.of(CliMessages.MESSAGES.continueWithUpdate(), "y")), args);
    }

    /**
     * Performs update on the test server
     *
     * @param console
     * @return
     * @throws ProvisioningException
     * @throws OperationException
     */
    public List<FileConflict> update(Collection<Pair<String, String>> prompts, String... args) throws Exception {
        final ArrayList<String> argList = new ArrayList<>();
        Collections.addAll(argList,
                CliConstants.Commands.UPDATE, CliConstants.Commands.PERFORM,
                CliConstants.DIR, serverRoot.toAbsolutePath().toString());

        Collections.addAll(argList, args);

        final ExecutionUtils.Execution execution = ExecutionUtils.prosperoExecution(argList.toArray(new String[]{}))
                .withTimeLimit(10, TimeUnit.MINUTES);
        prompts.forEach(p->execution.withPrompt(p.getKey(), p.getValue()));
        execution
                .execute()
                .assertReturnCode(ReturnCodes.SUCCESS);

        return Collections.emptyList();
    }

    public List<FileConflict> updateWithCheck(Collection<Pair<String, String>> prompts, Consumer<ExecutionUtils.ExecutionResult> verifier , String... args) throws Exception {
        final ArrayList<String> argList = new ArrayList<>();
        Collections.addAll(argList,
                CliConstants.Commands.UPDATE, CliConstants.Commands.PERFORM,
                CliConstants.DIR, serverRoot.toAbsolutePath().toString());

        Collections.addAll(argList, args);

        final ExecutionUtils.Execution execution = ExecutionUtils.prosperoExecution(argList.toArray(new String[]{}))
                .withTimeLimit(10, TimeUnit.MINUTES);
        prompts.forEach(p->execution.withPrompt(p.getKey(), p.getValue()));
        final ExecutionUtils.ExecutionResult result = execution
                .execute();

        verifier.accept(result);

        return Collections.emptyList();
    }

    /**
     * see {@link #revertToOriginalState(Console, List)} (Console)}
     * @return
     * @throws ProvisioningException
     * @throws OperationException
     */
    public void revertToOriginalState() throws OperationException, ProvisioningException {
        revertToOriginalState(new AcceptingConsole());
    }

    /**
     * see {@link #revertToOriginalState(Console, List)} (Console)}
     * @return
     * @throws ProvisioningException
     * @throws OperationException
     */
    public void revertToOriginalState(Console console) throws OperationException, ProvisioningException {
        revertToOriginalState(console, Collections.emptyList());
    }

    /**
     * Reverts installation to the first recorded state
     *
     * @param console
     * @param repositories
     * @throws OperationException
     * @throws ProvisioningException
     */
    public void revertToOriginalState(Console console, List<URL> repositories) throws OperationException, ProvisioningException {
        InstallationHistoryAction installationHistoryAction = new InstallationHistoryAction(serverRoot, console);
        final List<SavedState> revisions = installationHistoryAction.getRevisions();
        final SavedState originalState = revisions.get(revisions.size() - 1);
        final ArrayList<Repository> list = new ArrayList<>();
        for (int i = 0; i < repositories.size(); i++) {
            list.add(new Repository("test-" + i, repositories.get(i).toExternalForm()));
        }
        installationHistoryAction.rollback(originalState, MavenOptions.OFFLINE_NO_CACHE, list);
    }

    public void prepareUpdate(Path candidate, String... args) throws Exception {
        final ArrayList<String> argList = new ArrayList<>();
        Collections.addAll(argList,
                CliConstants.Commands.UPDATE, CliConstants.Commands.PREPARE,
                CliConstants.YES,
                CliConstants.DIR, serverRoot.toAbsolutePath().toString(),
                CliConstants.CANDIDATE_DIR, candidate.toAbsolutePath().toString());

        Collections.addAll(argList, args);

        ExecutionUtils.prosperoExecution(argList.toArray(new String[]{}))
                .withTimeLimit(10, TimeUnit.MINUTES)
                .execute()
                .assertReturnCode(ReturnCodes.SUCCESS);
    }

    public void apply(Path candidatePath) throws Exception {
        final ArrayList<String> argList = new ArrayList<>();
        Collections.addAll(argList,
                CliConstants.Commands.UPDATE, CliConstants.Commands.APPLY,
                CliConstants.YES,
                CliConstants.DIR, serverRoot.toAbsolutePath().toString(),
                CliConstants.CANDIDATE_DIR, candidatePath.toAbsolutePath().toString());

        ExecutionUtils.prosperoExecution(argList.toArray(new String[]{}))
                .withTimeLimit(10, TimeUnit.MINUTES)
                .execute()
                .assertReturnCode(ReturnCodes.SUCCESS);
    }

    public String listUpdates(String... args) throws Exception {
        final ArrayList<String> argList = new ArrayList<>();
        Collections.addAll(argList,
                CliConstants.Commands.UPDATE, CliConstants.Commands.LIST,
                CliConstants.DIR, serverRoot.toAbsolutePath().toString());

        Collections.addAll(argList, args);

        final ExecutionUtils.ExecutionResult result = ExecutionUtils.prosperoExecution(argList.toArray(new String[]{}))
                .withTimeLimit(10, TimeUnit.MINUTES)
                .execute();

        result.assertReturnCode(ReturnCodes.SUCCESS);
        return result.getCommandOutput();
    }


    public String listChannelManifestUpdates(boolean allVersions) throws Exception {
        final ArrayList<String> argList = new ArrayList<>();
        Collections.addAll(argList,
                CliConstants.Commands.UPDATE, CliConstants.Commands.LIST_CHANNELS,
                CliConstants.DIR, serverRoot.toAbsolutePath().toString());

        if (allVersions) {
            argList.add(CliConstants.ALL);
        }

        final ExecutionUtils.ExecutionResult result = ExecutionUtils.prosperoExecution(argList.toArray(new String[]{}))
                .withTimeLimit(10, TimeUnit.MINUTES)
                .execute();
        result.assertReturnCode(ReturnCodes.SUCCESS);

        return result.getCommandOutput();
    }

    /**
     * Simplified builder for a feature pack. Generates a zip file with the feature pack
     */
    public static class Builder {
        private List<Artifact> modules = new ArrayList<>();
        private final String name;

        private Builder(String name) {
            this.name = name;
        }

        private static RepositoryArtifactResolver initRepoManager(Path repoHome) {
            return SimplisticMavenRepoManager.getInstance(repoHome);
        }

        public TestInstallation.Builder addModule(String groupId, String artifactId, String version) {
            modules.add(new DefaultArtifact(groupId, artifactId, "jar", version));
            return this;
        }

        public Artifact build() throws IOException, ProvisioningException {
            final Path tempRoot = Files.createTempDirectory("fp-builder");
            tempRoot.toFile().deleteOnExit();
            final RepositoryArtifactResolver repo = initRepoManager(tempRoot);
            FeaturePackCreator creator = FeaturePackCreator.getInstance().addArtifactResolver(repo);

            final FeaturePackBuilder featurePackBuilder = creator.newFeaturePack(FeaturePackLocation.fromString(name).getFPID())
                    .addPlugin("wildfly-galleon-plugins", "org.wildfly.galleon-plugins:wildfly-galleon-plugins:jar:7.1.2.Final");

            final PackageBuilder packageBuilder = featurePackBuilder
                    .newPackage("p1", true);

            final StringBuilder versions = new StringBuilder();
            for (Artifact module : modules) {
                final String moduleName = module.getGroupId() + "." + module.getArtifactId();
                final String path = moduleName.replace('.', '/');
                packageBuilder
                        .writeContent("pm/wildfly/module/modules/" + path + "/main/module.xml",
                                "<module name=\"" + moduleName + "\" xmlns=\"urn:jboss:module:1.9\">\n" +
                                "    <resources>\n" +
                                "        <artifact name=\"${" + module.getGroupId() + ":" + module.getArtifactId() + "}\"/>\n" +
                                "    </resources>\n" +
                                "\n" +
                                "</module>", false);

                versions.append(String.format("%s:%s=%s:%s:%s::jar%n", module.getGroupId(), module.getArtifactId(),
                        module.getGroupId(), module.getArtifactId(), module.getVersion()));
            }



            featurePackBuilder
                    .writeResources("wildfly/artifact-versions.properties",
                            versions.toString())
                    .writeResources("wildfly/wildfly-channel.properties", "resolution=REQUIRED");
            creator.install();

            final String[] parts = name.split(":");
            final String fpPath = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2];
            return new DefaultArtifact(parts[0], parts[1], null, "zip", parts[2],
                    null, tempRoot.resolve(Path.of(fpPath, parts[1] + "-" + parts[2] + ".zip")).toFile());
        }
    }
}
