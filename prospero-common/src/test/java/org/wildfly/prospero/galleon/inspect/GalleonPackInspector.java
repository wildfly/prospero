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

package org.wildfly.prospero.galleon.inspect;

import org.jboss.logging.Logger;
import org.wildfly.prospero.api.InstallationMetadata;
import org.wildfly.prospero.api.exceptions.ProvisioningRuntimeException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.jboss.galleon.ProvisioningException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

class GalleonPackInspector extends XmlSupport {

    private static final Logger logger = Logger.getLogger(GalleonPackInspector.class);

    private final InstallationMetadata installationMetadata;
    private final Path installedModules;

    public GalleonPackInspector(InstallationMetadata installationMetadata, Path modulesDir) {
        this.installationMetadata = installationMetadata;
        this.installedModules = modulesDir;
    }

    public List<Artifact> getAllInstalledArtifacts(List<Path> resolvedFeaturePacks) throws ProvisioningException {
        List<Artifact> res = new ArrayList<>();
        for (Path fpPath : resolvedFeaturePacks) {
            final ArrayList<Artifact> artifactsFromFeaturePack = getArtifactsFromFeaturePack(fpPath);

            res.addAll(artifactsFromFeaturePack);
        }
        return res;
    }

    private ArrayList<Artifact> getArtifactsFromFeaturePack(Path featurePack) throws ProvisioningException {
        ArrayList<Artifact> res = new ArrayList<>();
        try (FileSystem fileSystem = FileSystems.newFileSystem(featurePack, this.getClass().getClassLoader())) {

            List<Path> moduleFiles = findModuleTemplates(fileSystem);

            final Map<String, String> versionProperties = readProperties(fileSystem.getPath("resources/wildfly/artifact-versions.properties"));

            final Path tempModulesDirectory = Files.createTempDirectory("temp-modules");
            tempModulesDirectory.toFile().deleteOnExit();

            for (Path moduleFile : moduleFiles) {
                // unzip the template to be able to read it
                final Path copy = Files.copy(moduleFile, tempModulesDirectory, REPLACE_EXISTING);
                parseTemplateArtifacts(res, versionProperties, moduleFile, copy.toFile());
            }
        } catch (IOException e) {
            throw new ProvisioningException("Unable to parse feature-pack " + featurePack, e);
        }
        return res;

    }

    private void parseTemplateArtifacts(ArrayList<Artifact> res, Map<String, String> versionProperties, Path moduleFile, File template) throws ProvisioningException {
        final NodeList nodes;
        try {
            final Document document = readDocument(template);
            nodes = nodesFromXPath(document, "//resources/artifact");
        } catch (XmlException e) {
            throw new ProvisioningException("Unable to parse modules template " + moduleFile, e);
        }

        final Path modulesRoot = findModulesRoot(moduleFile);
        final Path relativeModulePath = modulesRoot.relativize(moduleFile.getParent());

        for (int i = 0; i < nodes.getLength(); i++) {
            final String name = getArtifactPropertyName(nodes, i);

            if (!versionProperties.containsKey(name)) {
                // ignore, try next one
                logger.debug("Artifact not found: " + name);
                continue;
            }
            final String[] gav = versionProperties.get(name).split(":");

            Artifact artifact = new DefaultArtifact(gav[0], gav[1], gav[3], gav[4], gav[2], null);
            // feature-pack version might have been changed by channel resolution
            artifact = getInstalledVersion(artifact);

            if (artifact != null) {
                String fileName = toFileName(artifact);

                findInstalledArtifacts(res, relativeModulePath, artifact, fileName);
            }
        }
    }

    private Path findModulesRoot(Path moduleFile) {
        Path tmp = moduleFile;
        while (tmp != null) {
            tmp = tmp.getParent();
            if (tmp.getFileName().toString().equals("modules")) {
                break;
            }
        }
        if (tmp == null) {
            throw new ProvisioningRuntimeException("Unable to find root of the module " + moduleFile);
        }
        return tmp;
    }

    private String getArtifactPropertyName(NodeList nodes, int i) {
        Element e = (Element) nodes.item(i);
        String name = e.getAttribute("name");
        name = name.replace("${", "");
        name = name.replace("}", "");
        return name;
    }

    private Artifact getInstalledVersion(Artifact artifact) {
        final Artifact installedVersion = installationMetadata.find(artifact);
        if (installedVersion != null && !artifact.getVersion().equals(installedVersion.getVersion())) {
            artifact = artifact.setVersion(installedVersion.getVersion());
        }
        return artifact;
    }

    private String toFileName(Artifact artifact) {
        String fileName;
        if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
            fileName = artifact.getArtifactId() + "-" + artifact.getVersion() + "-" + artifact.getClassifier() + "." + artifact.getExtension();
        } else {
            fileName = artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getExtension();
        }
        return fileName;
    }

    private void findInstalledArtifacts(ArrayList<Artifact> res, Path relativeModulePath, Artifact artifact, String fileName) {
        final Path artifactFile = installedModules.resolve(relativeModulePath.toString()).resolve(fileName);
        if (artifactFile.toFile().exists()) {
            res.add(artifact.setFile(artifactFile.toFile()));
        } else {
            logger.debug("Artifact not found in modules: " + artifactFile);
        }
    }

    private List<Path> findModuleTemplates(FileSystem fileSystem) throws IOException {
        List<Path> moduleFiles = new ArrayList<>();
        Files.walkFileTree(fileSystem.getPath("/"), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("module.xml")) {
                    moduleFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return moduleFiles;
    }

    private Map<String, String> readProperties(Path propsFile) throws ProvisioningException {
        Map<String, String> propsMap = new HashMap<>();
        try(BufferedReader reader = Files.newBufferedReader(propsFile)) {
            String line = reader.readLine();
            while(line != null) {
                line = line.trim();
                if(!line.isEmpty() && line.charAt(0) != '#') {
                    final int i = line.indexOf('=');
                    if(i < 0) {
                        throw new ProvisioningException("Failed to parse property " + line + " from " + propsFile);
                    }
                    propsMap.put(line.substring(0, i), line.substring(i + 1));
                }
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new ProvisioningException("Failed to read version property file " + propsFile, e);
        }
        return propsMap;
    }
}
