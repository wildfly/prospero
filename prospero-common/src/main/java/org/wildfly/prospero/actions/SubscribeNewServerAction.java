/*
 *  Copyright (c) 2023 The original author or authors
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of Apache License v2.0 which
 *  accompanies this distribution.
 *
 *       The Apache License v2.0 is available at
 *       http://www.opensource.org/licenses/apache2.0.php
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package org.wildfly.prospero.actions;

import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.FeaturePackLocation;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.MavenOptions;
import org.wildfly.prospero.api.exceptions.ArtifactResolutionException;
import org.wildfly.prospero.api.exceptions.OperationException;
import org.wildfly.prospero.galleon.GalleonEnvironment;
import org.wildfly.prospero.galleon.GalleonUtils;
import org.wildfly.prospero.wfchannel.MavenSessionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jboss.galleon.api.config.GalleonFeaturePackConfig;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;

/**
 * The action used to generate a server based on the Channels and a FeaturePackLocation with specified version.
 * The generated server does not have Channel support, only Galleon metadata.
 *
 * @author <a href="mailto:aoingl@gmail.com">Lin Gao</a>
 */
public class SubscribeNewServerAction {
  private final MavenSessionManager mavenSessionManager;
  private final Console console;

  public SubscribeNewServerAction(MavenOptions mvnOptions, Console console) throws ProvisioningException {
    this.console = console;
    this.mavenSessionManager = new MavenSessionManager(mvnOptions);
  }

  public GenerateResult generateServerMetadata(List<Channel> channels, FeaturePackLocation loc) throws IOException, ProvisioningException, OperationException {
    Path tempDir = Files.createTempDirectory("tmp-prov-");

    tempDir.toFile().deleteOnExit();
    try (GalleonEnvironment galleonEnv = GalleonEnvironment
      .builder(tempDir, channels, mavenSessionManager, false)
      // forces the feature-pack artifact to be downloaded directly and not go through channel version resolution
      .setArtifactDirectResolve(true)
      .setConsole(console)
      .build()) {

      final GalleonFeaturePackConfig.Builder configBuilder = GalleonFeaturePackConfig.builder(loc)
              .includePackage("docs.examples.configs");
      final GalleonProvisioningConfig provisioningConfig = GalleonProvisioningConfig.builder().addFeaturePackDep(configBuilder.build()).build();
      try {
        GalleonUtils.executeGalleon(options -> galleonEnv.getProvisioning().provision(provisioningConfig, options),
                mavenSessionManager.getProvisioningRepo().toAbsolutePath());
      } catch (UnresolvedMavenArtifactException e) {
        throw new ArtifactResolutionException(ProsperoLogger.ROOT_LOGGER.unableToResolve(), e, e.getUnresolvedArtifacts(),
                e.getAttemptedRepositories(), mavenSessionManager.isOffline());
      }

      boolean manifestCoordDefined = channels.stream().anyMatch(c -> c.getManifestCoordinate() != null);
      return new GenerateResult(tempDir, channels, galleonEnv.getChannelSession().getRecordedChannel(), manifestCoordDefined);
    }
  }

  public static class GenerateResult {
    private final List<Channel> channels;
    private final Path provisionDir;
    private final ChannelManifest manifest;
    private final boolean manifestCoordDefined;
    GenerateResult(Path provisionDir, List<Channel> channels, ChannelManifest manifest, boolean manifestCoordDefined) {
      this.provisionDir = provisionDir;
      this.channels = channels;
      this.manifest = manifest;
      this.manifestCoordDefined = manifestCoordDefined;
    }

    public List<Channel> getChannels() {
      return channels;
    }

    public Path getProvisionDir() {
      return provisionDir;
    }

    public ChannelManifest getManifest() {
      return manifest;
    }

    public boolean isManifestCoordDefined() {
      return manifestCoordDefined;
    }
  }

}
