package org.wildfly.prospero.installation.git;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.version.VersionMatcher;
import org.wildfly.prospero.ProsperoLogger;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.metadata.ManifestVersionRecord;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * generates commit messages used in the git history storage
 */
class SavedStateParser {
    public static final String SCHEMA_VERSION_1_0_0 = "1.0.0";
    private static final String SCHEMA_1_0_0_FILE = "org/wildfly/prospero/savedstate/v1.0.0/schema.json";
    private static final Map<String, JsonSchema> SCHEMAS = new HashMap();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper(new JsonFactory());
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V201909)).objectMapper(JSON_MAPPER).build();

    static {
        SCHEMAS.put(SCHEMA_VERSION_1_0_0, SCHEMA_FACTORY.getSchema(ChannelMapper.class.getClassLoader().getResourceAsStream(SCHEMA_1_0_0_FILE)));
    }

    String write(SavedState.Type recordType, ManifestVersionRecord currentVersions) throws IOException {
        if (currentVersions == null) {
            return null;
        }

        final String header = currentVersions.getSummary();

        return recordType.name() + " " + header + "\n\n" + toJson(currentVersions);
    }

    SavedState read(String hash, Instant now, String text) throws IOException {
        final SavedState.Type type;
        final String originalText = text;
        final String msg;
        List<SavedState.Version> versions = Collections.emptyList();

        if (!text.contains(" ")) {
            // the message is only type
            type = SavedState.Type.fromText(text);
            text = "";
        } else {
            final int endOfType = text.indexOf(' ');
            type = SavedState.Type.fromText(text.substring(0, endOfType));
            text = text.substring(endOfType + 1);
        }

        if (type == SavedState.Type.UNKNOWN) {
            return new SavedState(hash, now, type, shortMessage(originalText), versions);
        }

        if (!text.contains("\n\n")) {
            // the message contains type and short description
            msg = text;
        } else {
            // the message contains type, short description and versions
            final int endOfShortDesc = text.indexOf("\n\n");
            msg = text.substring(0, endOfShortDesc).trim();

            text = text.substring(endOfShortDesc).trim();
            if (!text.isEmpty()) {
                try {
                    versions = readVersions(text);
                } catch (JsonParseException e) {
                    ProsperoLogger.ROOT_LOGGER.error("Unable to parse a history record [" + text + "]", e);
                }
            }
        }

        return new SavedState(hash, now, type, msg, versions);
    }

    private static String shortMessage(String originalText) {
        originalText = originalText.trim();

        if (originalText.isEmpty()) {
            return null;
        } else if (originalText.contains("\n")) {
            return originalText.split("\n") [0].trim();
        } else {
            return originalText;
        }
    }

    private static List<SavedState.Version> readVersions(String text) throws JsonProcessingException {
        JsonNode node = JSON_MAPPER.readTree(text);
        JsonSchema schema = getSchema(node);

        if (schema == null) {
            return Collections.emptyList();
        }

        Set<ValidationMessage> validationMessages = schema.validate(node);
        if (!validationMessages.isEmpty()) {
            for (ValidationMessage validationMessage : validationMessages) {
                ProsperoLogger.ROOT_LOGGER.error("Invalid Saved State in history " + validationMessage);
            }
            return Collections.emptyList();
        }

        final ManifestVersionRecord record = JSON_MAPPER.readValue(text, ManifestVersionRecord.class);
        final List<SavedState.Version> versions = new ArrayList<>();
        record.getMavenManifests().forEach(m -> versions.add(new SavedState.Version(
                m.getGroupId() + ":" + m.getArtifactId(),
                m.getVersion(), m.getDescription())));
        record.getUrlManifests().forEach(m -> versions.add(new SavedState.Version(
                m.getUrl(), m.getHash(), m.getDescription()
        )));
        record.getOpenManifests().forEach(m ->  versions.add(new SavedState.Version(
                "unknown", "unknown", m.getSummary()
        )));
        return versions;
    }

    private String toJson(ManifestVersionRecord currentVersions) throws IOException {
        return JSON_MAPPER.writeValueAsString(currentVersions);
    }

    private static JsonSchema getSchema(JsonNode node) {
        JsonNode schemaVersion = node.path("schemaVersion");
        String version = schemaVersion.asText();
        if (version == null || version.isEmpty()) {
            ProsperoLogger.ROOT_LOGGER.error("Invalid Saved State record in history - schema version is not specified");
            return null;
        }

        JsonSchema schema = SCHEMAS.get(version);
        if (schema != null) {
            return schema;
        } else {
            final String[] parts = version.split("\\.");
            StringBuilder versionPattern = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i == 0) {
                    versionPattern.append(parts[i]);
                } else if (i == parts.length -1 ) {
                    versionPattern.append(".*");
                } else {
                    versionPattern.append("\\.").append(parts[i]);
                }
            }

            final Optional<String> latestCompatibleSchemaVersion = SCHEMAS.keySet().stream()
                    .filter(v -> v.matches(versionPattern.toString()))
                    .max(VersionMatcher.COMPARATOR);

            return latestCompatibleSchemaVersion.map(SCHEMAS::get)
                    .orElseGet(()->{
                        ProsperoLogger.ROOT_LOGGER.error("Invalid Saved State record in history - unknown schema version " + schemaVersion);
                        return null;
                    });
        }
    }
}
