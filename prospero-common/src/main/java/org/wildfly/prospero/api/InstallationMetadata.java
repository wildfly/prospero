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

package org.wildfly.prospero.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import org.apache.commons.lang3.StringUtils;
import org.jboss.galleon.Constants;
import org.jboss.galleon.util.PathsUtils;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.apache.commons.io.FileUtils;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.metadata.ManifestVersionRecord;
import org.wildfly.prospero.api.exceptions.MetadataException;
import org.wildfly.prospero.installation.git.GitStorage;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;
import org.wildfly.prospero.model.ManifestYamlSupport;
import org.wildfly.prospero.model.ProsperoConfig;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.galleon.ProvisioningException;
import org.wildfly.channel.Stream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jboss.galleon.api.GalleonBuilder;
import org.jboss.galleon.api.Provisioning;
import org.jboss.galleon.api.config.GalleonProvisioningConfig;

import static org.wildfly.prospero.metadata.ProsperoMetadataUtils.CURRENT_VERSION_FILE;

public class InstallationMetadata implements AutoCloseable {

    public static final String PROVISIONING_FILE_NAME = "provisioning.xml";
    public static final String GALLEON_INSTALLATION_DIR = ".galleon";
    private final Path manifestFile;
    private final Path channelsFile;
    private final Path readmeFile;
    private final Path provisioningFile;
    private final GalleonProvisioningConfig galleonProvisioningConfig;
    private final GitStorage gitStorage;
    private final Path base;
    private final Optional<ManifestVersionRecord> manifestVersion;
    private final GalleonProvisioningConfig provisioningConfig;
    private ProsperoConfig prosperoConfig;
    private ChannelManifest manifest;

    /**
     * load the metadata of an existing installation. If the history is not available, it will be started.
     *
     * @param base
     * @return
     * @throws MetadataException
     */
    public static InstallationMetadata loadInstallation(Path base) throws MetadataException {
        final Path manifestFile = base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME);

        ChannelManifest manifest;
        ProsperoConfig prosperoConfig;
        Optional<ManifestVersionRecord> currentVersion;

        try {
            manifest = ManifestYamlSupport.parse(manifestFile.toFile());
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToParseConfiguration(manifestFile, e);
        }
        prosperoConfig = ProsperoConfig.readConfig(base.resolve(ProsperoMetadataUtils.METADATA_DIR));

        final Path versionsFile = base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(CURRENT_VERSION_FILE);
        try {
            currentVersion = ManifestVersionRecord.read(versionsFile);
        } catch (JsonMappingException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToParseConfiguration(versionsFile, e);
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToReadFile(versionsFile, e);
        }

        final Path provisioningRecordPath = base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML);
        GalleonProvisioningConfig provisioningConfig = null;
        if (Files.exists(provisioningRecordPath)) {
            try {
                // XXX We should be able to resolve the version from something.
                try(Provisioning p = new GalleonBuilder().newProvisioningBuilder().build()) {
                    provisioningConfig = p.loadProvisioningConfig(provisioningRecordPath);
                }
            } catch (ProvisioningException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToReadFile(provisioningRecordPath, e);
            }
        }

        try {
            final GitStorage gitStorage = new GitStorage(base);
            final InstallationMetadata metadata = new InstallationMetadata(base, manifest, prosperoConfig, gitStorage, currentVersion, provisioningConfig);
            if (!gitStorage.isStarted()) {
                ProsperoLogger.ROOT_LOGGER.debugf("Initializing history storage in %s", base);
                gitStorage.record();
            }
            return metadata;
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToCreateHistoryStorage(base.resolve(ProsperoMetadataUtils.METADATA_DIR), e);
        }
    }

    /**
     * create an in-memory installation metadata. No information is recorded until {@link InstallationMetadata#recordProvision(boolean)}
     * is called.
     *
     * @param base
     * @param manifest
     * @param prosperoConfig
     * @param currentVersions - manifest versions used to provision the installation
     * @return
     * @throws MetadataException
     */
    public static InstallationMetadata newInstallation(Path base, ChannelManifest manifest, ProsperoConfig prosperoConfig,
                                                       Optional<ManifestVersionRecord> currentVersions) throws MetadataException {
        final GalleonProvisioningConfig provisioningConfig;
        try {
            try (Provisioning p = new GalleonBuilder().newProvisioningBuilder().build()) {
                provisioningConfig = p.loadProvisioningConfig(PathsUtils.getProvisioningXml(base));
            }
        } catch (ProvisioningException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToReadFile(PathsUtils.getProvisioningXml(base), e);
        }
        return new InstallationMetadata(base, manifest, prosperoConfig, new GitStorage(base), currentVersions, provisioningConfig);
    }

    /**
     * read the metadata from an exported zip containing configuration files
     *
     * @param archiveLocation path to the exported zip
     * @return
     * @throws IOException
     * @throws MetadataException
     */
    public static InstallationMetadata fromMetadataBundle(Path archiveLocation) throws IOException, MetadataException {

        if (!Files.exists(archiveLocation) || !Files.isRegularFile(archiveLocation) || !Files.isReadable(archiveLocation)) {
            throw ProsperoLogger.ROOT_LOGGER.invalidMetadataBundle(archiveLocation);
        }

        final Path tempDirectory = Files.createTempDirectory("installer-import");
        tempDirectory.toFile().deleteOnExit();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(archiveLocation.toFile()))) {
            Path manifestFile = null;
            Path channelsFile = null;
            Path provisioningFile = null;
            ZipEntry entry;
            Files.createDirectory(tempDirectory.resolve(ProsperoMetadataUtils.METADATA_DIR));
            Files.createDirectory(tempDirectory.resolve(Constants.PROVISIONED_STATE_DIR));
            while ((entry = zis.getNextEntry()) != null) {

                if (entry.getName().equals(ProsperoMetadataUtils.MANIFEST_FILE_NAME)) {
                    manifestFile = tempDirectory.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.MANIFEST_FILE_NAME);
                    Files.copy(zis, manifestFile, StandardCopyOption.REPLACE_EXISTING);
                    manifestFile.toFile().deleteOnExit();
                }

                if (entry.getName().equals(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME)) {
                    channelsFile = tempDirectory.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME);
                    Files.copy(zis, channelsFile, StandardCopyOption.REPLACE_EXISTING);
                    channelsFile.toFile().deleteOnExit();
                }

                if (entry.getName().equals(PROVISIONING_FILE_NAME)) {
                    provisioningFile = tempDirectory.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONING_XML);
                    Files.copy(zis, provisioningFile, StandardCopyOption.REPLACE_EXISTING);
                    provisioningFile.toFile().deleteOnExit();
                }
            }

            if (manifestFile == null || channelsFile == null || provisioningFile == null) {
                throw ProsperoLogger.ROOT_LOGGER.incompleteMetadataBundle(archiveLocation);
            }
        }

        return loadInstallation(tempDirectory);
    }

    protected InstallationMetadata(Path base, ChannelManifest manifest, ProsperoConfig prosperoConfig,
                                   GitStorage gitStorage, Optional<ManifestVersionRecord> currentVersions,
                                   GalleonProvisioningConfig provisioningConfig) throws MetadataException {
        this.base = base;
        this.gitStorage = gitStorage;
        this.manifestFile = ProsperoMetadataUtils.manifestPath(base);
        this.channelsFile = ProsperoMetadataUtils.configurationPath(base);
        this.readmeFile = base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.README_FILE_NAME);
        this.provisioningFile = base.resolve(GALLEON_INSTALLATION_DIR).resolve(PROVISIONING_FILE_NAME);
        this.provisioningConfig = provisioningConfig;

        this.manifest = manifest;
        this.prosperoConfig = new ProsperoConfig(new ArrayList<>(prosperoConfig.getChannels()), prosperoConfig.getMavenOptions());

        final List<Channel> channels = prosperoConfig.getChannels();
        if (channels != null && channels.stream().anyMatch(c-> StringUtils.isEmpty(c.getName()))) {
            throw ProsperoLogger.ROOT_LOGGER.emptyChannelName();
        }

        try {
            if(Files.exists(provisioningFile)) {
                try (Provisioning p = new GalleonBuilder().newProvisioningBuilder().build()) {
                    this.galleonProvisioningConfig = p.loadProvisioningConfig(provisioningFile);
                }
            } else {
                this.galleonProvisioningConfig = null;
            }
        } catch (ProvisioningException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToParseConfiguration(provisioningFile, e);
        }

        this.manifestVersion = currentVersions;
    }

    public Path exportMetadataBundle(Path location) throws IOException {
        final File file = location.toFile();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            zos.putNextEntry(new ZipEntry(ProsperoMetadataUtils.MANIFEST_FILE_NAME));
            try(FileInputStream fis = new FileInputStream(manifestFile.toFile())) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
            }
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(ProsperoMetadataUtils.INSTALLER_CHANNELS_FILE_NAME));
            try(FileInputStream fis = new FileInputStream(channelsFile.toFile())) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
            }
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(PROVISIONING_FILE_NAME));
            try(FileInputStream fis = new FileInputStream(provisioningFile.toFile())) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
            }
            zos.closeEntry();
        }
        return file.toPath();
    }

    public ChannelManifest getManifest() {
        return manifest;
    }

    public GalleonProvisioningConfig getGalleonProvisioningConfig() {
        return galleonProvisioningConfig;
    }

    public void recordProvision(boolean overrideProsperoConfig) throws MetadataException {
        recordProvision(overrideProsperoConfig, true);
    }

    public void recordProvision(boolean overrideProsperoConfig, boolean gitRecord) throws MetadataException {
        try {
            ProsperoMetadataUtils.writeManifest(this.manifestFile, this.manifest);
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToSaveConfiguration(manifestFile, e);
        }
        // Add README.txt file to .installation directory to warn the files should not be edited.
        if (!Files.exists(readmeFile)) {
            try {
                ProsperoMetadataUtils.writeWarningReadme(readmeFile);
            } catch (IOException e) {
                throw new MetadataException("Unable to create README.txt in installation", e);
            }
        }

        if (overrideProsperoConfig || !Files.exists(this.channelsFile)) {
            writeProsperoConfig();
        }

        if (manifestVersion.isPresent()) {
            final Path versionFile = base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(CURRENT_VERSION_FILE);
            try {
                ProsperoMetadataUtils.writeVersionRecord(versionFile, manifestVersion.get());
            } catch (IOException e) {
                throw ProsperoLogger.ROOT_LOGGER.unableToWriteFile(versionFile, e);
            }
        }

        if (gitRecord) {
            gitStorage.record();
        }
    }

    /**
     * check if the provisioning definition is present. If not add it to the history
     */
    public void updateProvisioningConfiguration() throws MetadataException {
        try {
            if (!Files.exists(base.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(ProsperoMetadataUtils.PROVISIONING_RECORD_XML))) {
                ProsperoMetadataUtils.recordProvisioningDefinition(base);

                gitStorage.recordChange(SavedState.Type.INTERNAL_UPDATE, ProsperoMetadataUtils.PROVISIONING_RECORD_XML);
            }

            // persist in history
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToSaveConfiguration(channelsFile, e);
        }
    }

    private void writeProsperoConfig() throws MetadataException {
        try {
            ProsperoMetadataUtils.writeChannelsConfiguration(channelsFile, getProsperoConfig().getChannels());
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToSaveConfiguration(channelsFile, e);
        }
    }

    public List<SavedState> getRevisions() throws MetadataException {
        return gitStorage.getRevisions();
    }

    public InstallationMetadata getSavedState(SavedState savedState) throws MetadataException {
        // checkout previous version
        // record as rollback operation
        Path revert = null;
        try {
            revert = gitStorage.revert(savedState);

            // re-parse metadata
            return loadInstallation(revert);
        } finally {
            gitStorage.reset();
            if (revert != null && Files.exists(revert)) {
                FileUtils.deleteQuietly(revert.toFile());
            }
        }
    }

    public InstallationChanges getChangesIn(SavedState savedState) throws MetadataException {
        return new InstallationChanges(
                gitStorage.getArtifactChanges(savedState),
                gitStorage.getChannelChanges(savedState),
                gitStorage.getFeatureChanges(savedState));
    }

    public InstallationChanges getChangesToCurrent(SavedState savedState) throws MetadataException {
        return new InstallationChanges(
                gitStorage.getArtifactChangesSince(savedState),
                gitStorage.getChannelChangesSince(savedState),
                gitStorage.getFeatureChangesSince(savedState));
    }

    public void setManifest(ChannelManifest resolvedChannel) {
        manifest = resolvedChannel;
    }

    public List<Artifact> getArtifacts() {
        return manifest.getStreams().stream().map(s-> streamToArtifact(s)).collect(Collectors.toList());
    }

    private DefaultArtifact streamToArtifact(Stream s) {
        return new DefaultArtifact(s.getGroupId(), s.getArtifactId(), "jar", s.getVersion());
    }

    public Artifact find(Artifact gav) {
        for (Stream stream : manifest.getStreams()) {
            if (stream.getGroupId().equals(gav.getGroupId()) && stream.getArtifactId().equals(gav.getArtifactId())) {
                return streamToArtifact(stream);
            }
        }
        return null;
    }

    public ProsperoConfig getProsperoConfig() {
        return prosperoConfig;
    }

    public void updateProsperoConfig(ProsperoConfig config) throws MetadataException {
        this.prosperoConfig = config;

        writeProsperoConfig();

        gitStorage.recordConfigChange();
    }

    public Optional<ManifestVersionRecord> getManifestVersions() {
        return manifestVersion;
    }

    @Override
    public void close() {
        if (gitStorage != null) {
            try {
                gitStorage.close();
            } catch (Exception e) {
                // log and ignore
                ProsperoLogger.ROOT_LOGGER.unableToCloseStore(e);
            }
        }
    }

    /**
     * galleon configuration used to provision current state of the server.
     *
     * @return
     */
    public GalleonProvisioningConfig getRecordedProvisioningConfig() {
        return provisioningConfig;
    }

    public List<ChannelVersion> getChannelVersions() throws MetadataException {
        try {
            final ChannelVersionReader reader = new ChannelVersionReader(
                    ChannelMapper.fromString(Files.readString(channelsFile)),
                    manifestVersion.orElse(new ManifestVersionRecord())
            );
            return reader.getChannelVersions();
        } catch (IOException e) {
            throw ProsperoLogger.ROOT_LOGGER.unableToParseConfiguration(channelsFile, e);
        }
    }
}
