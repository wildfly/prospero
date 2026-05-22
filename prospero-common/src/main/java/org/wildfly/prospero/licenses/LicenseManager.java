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

package org.wildfly.prospero.licenses;

import org.apache.commons.io.FileUtils;
import org.jboss.logging.Logger;
import org.wildfly.prospero.metadata.ProsperoMetadataUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages stored licenses and agreements. Licenses are stored in classpath in {@code DEFAULT_LICENSE_DEFINITION} and
 * are keyed by the Feature-Pack Maven coordinated (groupId:artifactId).
 */
public class LicenseManager {

    private static final Logger logger = Logger.getLogger(LicenseManager.class);
    public static final String LICENSES_FOLDER = "licenses";
    protected static final String LICENSE_DEFINITION_NAME = "licenses";
    private static final String LICENSE_DEFINITION_EXTENSION = ".yaml";
    private static final String DEFAULT_LICENSE_DEFINITION = LICENSE_DEFINITION_NAME + LICENSE_DEFINITION_EXTENSION;
    protected static final String LICENSE_AGREEMENT_FILENAME= "license_accepted.properties";
    private final HashMap<String, List<License>> nameMap = new HashMap<>();

    public LicenseManager() {
        this(getLicensesFile());
    }

    // package-access for tests
    LicenseManager(URL licensesUrl) {
        if (licensesUrl == null) {
            logger.debug("No known repositories found");
        } else {
            logger.debug("Loading known provisioning configurations from: " + licensesUrl);
            final List<License> knownFeaturePacks;
            try {
                knownFeaturePacks = License.readLicenses(licensesUrl);
                for (License fp : knownFeaturePacks) {
                    if (!nameMap.containsKey(fp.getFpGav())) {
                        nameMap.put(fp.getFpGav(), new ArrayList<>());
                    }
                    nameMap.get(fp.getFpGav()).add(fp);
                }
            } catch (IOException e) {
                logger.warn("Failed to load licenses configurations from: " + licensesUrl);
                logger.debug("Error parsing provisioning configurations:", e);
            }
        }
    }

    /**
     * retrieve {@code License}s applicable to the list of FeaturePacks coordinates.
     *
     * @param fpls - list of Maven coordinates (groupId:artifactId) of installed Feature-Packs
     * @return list of required {@License}s. Empty list if no licenses are required.
     */
    public List<License> getLicenses(Set<String> fpls) {
        Objects.requireNonNull(fpls);

        return fpls.stream()
                .filter(nameMap::containsKey)
                .map(nameMap::get)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * create a record of approved licenses within the installation.
     *
     * The license texts together with username used to approve it and the timestamp is stored in
     * {@link ProsperoMetadataUtils#METADATA_DIR}/{@clink LICENSES_FOLDER}.
     *
     * @param licenses - accepted {@code License}s
     * @param targetServer - {@code Path} to the installed server
     * @throws IOException - if unable to record the license agreement
     */
    public void recordAgreements(List<License> licenses, Path targetServer) throws IOException {
        Objects.requireNonNull(licenses);
        Objects.requireNonNull(targetServer);
        if (licenses.isEmpty()) {
            if (logger.isDebugEnabled()) {
                logger.debug("No licenses to save, skipping.");
            }
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debugf("Saving %d license agreements.", licenses.size());
        }
        final LocalDateTime timestamp = LocalDateTime.now();

        final Path licenseFolder = targetServer.resolve(ProsperoMetadataUtils.METADATA_DIR).resolve(LICENSES_FOLDER);
        if (!Files.exists(licenseFolder)) {
            if (logger.isTraceEnabled()) {
                logger.trace("Creating license Folder " + licenseFolder);
            }
            Files.createDirectory(licenseFolder);
        }
        final Path licenseAcceptFile = licenseFolder.resolve(LICENSE_AGREEMENT_FILENAME);
        final SortedProperties licenseApproveProperties = new SortedProperties();
        if (Files.exists(licenseAcceptFile)) {
            try (FileInputStream inStream = new FileInputStream(licenseAcceptFile.toFile())) {
                licenseApproveProperties.load(inStream);
            }
        }
        final Optional<Integer> first = licenseApproveProperties.keySet().stream()
                .map(Object::toString)
                .filter(s -> s.startsWith("license.") && s.endsWith(".name"))
                .map(s -> s.replace("license.", "").replace(".name", ""))
                .map(Integer::parseInt)
                .max(Integer::compareTo)
                .map(i->i+1); // add one to get the next starting index

        try (FileOutputStream fos = new FileOutputStream(licenseAcceptFile.toFile())) {

            int nextLicenseIndex = first.orElse(0);
            for (License license : licenses) {
                saveLicenseText(license, licenseFolder);

                licenseApproveProperties.setProperty("license." + nextLicenseIndex + ".name", license.getName());
                licenseApproveProperties.setProperty("license." + nextLicenseIndex + ".file", toFileName(license));
                licenseApproveProperties.setProperty("license." + nextLicenseIndex + ".timestamp", timestamp.toString());

                nextLicenseIndex++;
            }

            if (logger.isTraceEnabled()) {
                logger.trace("Storing license agreement " + licenseApproveProperties);
            }
            licenseApproveProperties.store(fos, "Agreements accepted during installation");
        }
    }

    public void copyIfExists(Path sourceServer, Path targetServer) throws IOException {

        final Path licencesDir = Path.of(ProsperoMetadataUtils.METADATA_DIR, LICENSES_FOLDER);
        if (!Files.exists(sourceServer.resolve(licencesDir))) {
            return;
        }

        // copy all licenses files
        FileUtils.copyDirectory(sourceServer.resolve(licencesDir).toFile(), targetServer.resolve(licencesDir).toFile());
    }

    private static URL getLicensesFile() {
        final Locale locale = Locale.getDefault();
        final URL licensesUrl = LicenseManager.class.getClassLoader().getResource(
                LICENSE_DEFINITION_NAME + "_" + locale.getLanguage() + LICENSE_DEFINITION_EXTENSION);
        if (licensesUrl == null) {
            return LicenseManager.class.getClassLoader().getResource(DEFAULT_LICENSE_DEFINITION);
        } else {
            return licensesUrl;
        }
    }

    private static void saveLicenseText(License license, Path licensesFolder) throws IOException {
        final Path licenseFilePath = licensesFolder.resolve(toFileName(license));
        if (logger.isTraceEnabled()) {
            logger.trace("Storing license text to: " + licenseFilePath);
        }

        Files.writeString(licenseFilePath, license.getText(), StandardOpenOption.CREATE);
    }

    private static String toFileName(License license) {
        return license.getName().toLowerCase(Locale.getDefault()).replace(' ', '-') + ".txt";
    }
}
