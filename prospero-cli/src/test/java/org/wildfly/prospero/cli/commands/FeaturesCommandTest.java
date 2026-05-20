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

package org.wildfly.prospero.cli.commands;

import org.jboss.galleon.config.ConfigId;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.wildfly.channel.ArtifactCoordinate;
import org.wildfly.prospero.actions.ApplyCandidateAction;
import org.wildfly.prospero.actions.FeaturesAddAction;
import org.wildfly.prospero.api.FileConflict;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.cli.ActionFactory;
import org.wildfly.prospero.cli.CliMessages;
import org.wildfly.prospero.cli.ReturnCodes;
import org.wildfly.prospero.model.FeaturePackTemplate;
import org.wildfly.prospero.test.MetadataTestUtils;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.wildfly.common.Assert.assertTrue;

@RunWith(MockitoJUnitRunner.class)
public class FeaturesCommandTest extends AbstractMavenCommandTest {
    public static final String A_PROSPERO_FP = UpdateCommand.PROSPERO_FP_GA + ":1.0.0";
    @Mock
    private ActionFactory actionFactory;
    @Mock
    private FeaturesAddAction featuresAddAction;
    @Mock
    private ApplyCandidateAction applyUpdateAction;

    @Captor
    private ArgumentCaptor<MavenOptions> mavenOptions;

    @Captor
    private ArgumentCaptor<ConfigId> configNameCaptor;
    @Captor
    private ArgumentCaptor<Set<ConfigId>> defaultConfigNamesCaptor;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path installationDir;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        installationDir = tempFolder.newFolder().toPath();

        when(actionFactory.featuresAddAction(any(), any(), any(), any())).thenReturn(featuresAddAction);
        when(actionFactory.applyUpdate(any(), any())).thenReturn(applyUpdateAction);
        MetadataTestUtils.createInstallationMetadata(installationDir);
        MetadataTestUtils.createGalleonProvisionedState(installationDir, A_PROSPERO_FP);

        when(featuresAddAction.isFeaturePackAvailable(any()))
                .thenReturn(true);
    }

    @Test
    public void currentDirNotValidInstallation() {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD, CliConstants.FPL, "test:test", CliConstants.LAYERS, "layer1");

        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertTrue(getErrorOutput().contains(CliMessages.MESSAGES.invalidInstallationDir(UpdateCommand.currentDir().toAbsolutePath())
                .getMessage()));
    }

    @Test
    public void errorIfFplIsNotPresent() {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD, CliConstants.DIR, "test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(String.format("Missing required option: '%s=%s'", CliConstants.FPL, CliConstants.FEATURE_PACK_REFERENCE));
    }

    @Test
    public void passArgumentsToFeaturesAddAction_OnlyFpl() throws Exception {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "test:test",
                CliConstants.LAYERS, "layer1");
        assertEquals(ReturnCodes.SUCCESS, exitCode);

        final ArgumentCaptor<String> fplCaptor = ArgumentCaptor.forClass(String.class);
        verify(featuresAddAction).addFeaturePackWithLayers(fplCaptor.capture(), any(), configNameCaptor.capture(), any());

        assertEquals(fplCaptor.getValue(), "test:test");
        assertNull(configNameCaptor.getValue());
    }

    @Test
    public void passArgumentsToFeaturesAddAction_LayersAreSplit() throws Exception {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "test:test",
                CliConstants.LAYERS, "layer1,layer2");
        assertEquals(ReturnCodes.SUCCESS, exitCode);

        final ArgumentCaptor<Set<String>> layersCaptor = ArgumentCaptor.forClass(Set.class);
        verify(featuresAddAction).addFeaturePackWithLayers(any(), layersCaptor.capture(),
                any(), any());

        assertEquals(Set.of("layer1", "layer2"), layersCaptor.getValue());
    }

    @Test
    public void passArgumentsToFeaturesAddAction_ModuleOnlyConfig() throws Exception {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "test:test",
                CliConstants.LAYERS, "layer1",
                CliConstants.TARGET_CONFIG, "test/");
        assertEquals(ReturnCodes.SUCCESS, exitCode);

        verify(featuresAddAction).addFeaturePackWithLayers(any(), any(),
                configNameCaptor.capture(), any());

        assertThat(configNameCaptor.getValue())
                .isEqualTo(new ConfigId("test", null));
    }

    @Test
    public void passArgumentsToFeaturesAddAction_AllParameters() throws Exception {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "test:test",
                CliConstants.LAYERS, "layer1",
                CliConstants.TARGET_CONFIG, "model/config");
        assertEquals(ReturnCodes.SUCCESS, exitCode);

        final ArgumentCaptor<Set<String>> layersCaptor = ArgumentCaptor.forClass(Set.class);
        verify(featuresAddAction).addFeaturePackWithLayers(any(), layersCaptor.capture(),
                configNameCaptor.capture(), any());

        assertEquals(Set.of("layer1"), layersCaptor.getValue());
        assertEquals(new ConfigId("model", "config"), configNameCaptor.getValue());
    }

    @Test
    public void nonExistingFeaturePack_CausesError() throws Exception {
        when(featuresAddAction.isFeaturePackAvailable("test:idontexist"))
                .thenReturn(false);

        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "test:idontexist",
                CliConstants.LAYERS, "layer1",
                CliConstants.TARGET_CONFIG, "model/config");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(CliMessages.MESSAGES.featurePackNotFound("test:idontexist"));
    }

    @Test
    public void unableToResolveFeaturePack_CausesError() throws Exception {
        when(featuresAddAction.isFeaturePackAvailable("test:idontexist"))
                .thenThrow(new ArtifactResolutionException("Test error", null,
                        Set.of(new ArtifactCoordinate("test", "idontexist", "zip", null, "1.0.0")),
                        Set.of(), false));

        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "test:idontexist",
                CliConstants.LAYERS, "layer1",
                CliConstants.TARGET_CONFIG, "model/config");
        assertEquals(ReturnCodes.PROCESSING_ERROR, exitCode);
        assertThat(getErrorOutput())
                .contains("Unable to resolve artifacts:")
                .contains("test:idontexist:zip:1.0.0 [missing]");
    }

    @Test
    public void fplWithOnePartFails() throws Exception {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.LAYERS, "layer1",
                CliConstants.FPL, "test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(CliMessages.MESSAGES.featurePackNameNotMavenCoordinate().getMessage());
    }

    @Test
    public void fplWithTreePartsFails() throws Exception {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.LAYERS, "layer1",
                CliConstants.FPL, "org.test:test:1.2.3");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(CliMessages.MESSAGES.featurePackNameNotMavenCoordinate().getMessage());
    }

    @Test
    public void nonExistingLayersShowsError() throws Exception {
        doThrow(new FeaturesAddAction.LayerNotFoundException("foo", Set.of("idontexist"), Set.of("layer1", "layer2")))
                .when(featuresAddAction).addFeaturePackWithLayers(eq("org.test:test"), eq(Set.of("idontexist")),
                        eq(null), any());
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.LAYERS, "idontexist",
                CliConstants.FPL, "org.test:test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains("The feature-pack `org.test:test` does not provide requested layers [idontexist].")
                .contains("Supported layers are [layer1, layer2]");
    }

    @Test
    public void nonExistingLayersShowsErrorNoAvailableLayers() throws Exception {
        doThrow(new FeaturesAddAction.LayerNotFoundException("foo", Set.of("idontexist"), Collections.emptySet()))
                .when(featuresAddAction).addFeaturePackWithLayers(eq("org.test:test"), eq(Set.of("idontexist")),
                        eq(null), any());
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.LAYERS, "idontexist",
                CliConstants.FPL, "org.test:test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains("The feature-pack `org.test:test` does not provide any layers.")
                .contains("Try removing the --layers parameter");
    }


    @Test
    public void nonExistingModelShowsError() throws Exception {
        doThrow(new FeaturesAddAction.ModelNotDefinedException("test", "idontexist", Set.of("model1", "model2")))
                .when(featuresAddAction).addFeaturePackWithLayers(eq("org.test:test"),
                        eq(Set.of("layer1")), eq(new ConfigId("idontexist", "test")), any());
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.LAYERS, "layer1",
                CliConstants.TARGET_CONFIG, "idontexist/test",
                CliConstants.FPL, "org.test:test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains("The feature-pack `org.test:test` does not provide requested model `idontexist`.")
                .contains("Supported models are [model1, model2]");
    }

    @Test
    public void nonExistingConfigurationShowsError() throws Exception {
        doThrow(new FeaturesAddAction.ConfigurationNotFoundException("test", new ConfigId("test", "idontexist")))
                .when(featuresAddAction).addFeaturePackWithLayers(eq("org.test:test"),
                        eq(Set.of("layer1")), eq(new ConfigId("test", "idontexist")), any());
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.LAYERS, "layer1",
                CliConstants.TARGET_CONFIG, "test/idontexist",
                CliConstants.FPL, "org.test:test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains("The feature-pack `org.test:test` does not provide requested configuration `test/idontexist`.");
    }

    @Test
    public void noLayersTriggersNoLayersBuild() throws Exception {
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "test:test",
                CliConstants.TARGET_CONFIG, "model/config");
        assertEquals(ReturnCodes.SUCCESS, exitCode);

        verify(featuresAddAction).addFeaturePack(eq("test:test"), eq(Set.of(new ConfigId("model", "config"))), any());
    }

    @Test
    public void showErrorIfFeaturePackRequiresLayersAndNoneGiven() throws Exception {
        when(featuresAddAction.getFeaturePackRecipe("org.test:test"))
                .thenReturn(new FeaturePackTemplate.Builder("org.test", "test", "1.0.0")
                        .setRequiresLayers(true)
                        .build());
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.TARGET_CONFIG, "test/idontexist",
                CliConstants.FPL, "org.test:test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(CliMessages.MESSAGES.featurePackRequiresLayers("org.test:test"));
    }

    @Test
    public void showErrorIfFeaturePackDoesntSupportCustomizationButLayersGiven() throws Exception {
        when(featuresAddAction.getFeaturePackRecipe("org.test:test"))
                .thenReturn(new FeaturePackTemplate.Builder("org.test", "test", "1.0.0")
                        .setSupportsCustomization(false)
                        .build());
        int exitCode = commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.TARGET_CONFIG, "test/idontexist",
                CliConstants.FPL, "org.test:test");
        assertEquals(ReturnCodes.INVALID_ARGUMENTS, exitCode);
        assertThat(getErrorOutput())
                .contains(CliMessages.MESSAGES.featurePackDoesNotSupportCustomization("org.test:test"));
    }

    @Test
    public void promptUserIfConflictsDetected() throws Exception {
        when(applyUpdateAction.getConflicts())
                .thenReturn(List.of(
                        FileConflict.userModified("path/to/file").updateModified().userPreserved())
                );
        commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.TARGET_CONFIG, "test/idontexist",
                CliConstants.FPL, "org.test:test");

        assertEquals("The user should be prompted to continue twice.", 2, askedConfirmation);
        verify(applyUpdateAction).applyUpdate(ApplyCandidateAction.Type.FEATURE_ADD);
    }

    @Test
    public void rejectChangesIfConflictsAreNotAccepted() throws Exception {
        when(applyUpdateAction.getConflicts())
                .thenReturn(List.of(
                        FileConflict.userModified("path/to/file").updateModified().userPreserved())
                );
        // reject 2nd prompt which should show user the conflicts
        this.setDenyConfirm(true, 2);
        commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.TARGET_CONFIG, "test/idontexist",
                CliConstants.FPL, "org.test:test");

        verify(applyUpdateAction, never()).applyUpdate(ApplyCandidateAction.Type.FEATURE_ADD);
    }

    @Test
    public void applyChangesWithoutPromptWhenNoConflictsFound() throws Exception {
        commandLine.execute(CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.TARGET_CONFIG, "test/idontexist",
                CliConstants.FPL, "org.test:test");

        assertEquals("The user should be prompted to continue only once to confirm installation.", 1, askedConfirmation);
        verify(applyUpdateAction).applyUpdate(ApplyCandidateAction.Type.FEATURE_ADD);
    }

    @Override
    protected ActionFactory createActionFactory() {
        return actionFactory;
    }

    @Override
    protected MavenOptions getCapturedMavenOptions() throws Exception {
        Mockito.verify(actionFactory).featuresAddAction(any(), mavenOptions.capture(), any(), any());
        return mavenOptions.getValue();
    }

    @Override
    protected String[] getDefaultArguments() {
        return new String[] {CliConstants.Commands.FEATURE_PACKS, CliConstants.Commands.ADD,
                CliConstants.DIR, installationDir.toString(),
                CliConstants.FPL, "foo:bar",
                CliConstants.LAYERS, "layer1",};
    }
}