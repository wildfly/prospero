package org.wildfly.prospero.installation.git;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.metadata.ManifestVersionRecord;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * generates commit messages used in the git history storage
 */
class SavedStateParser {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper(new JsonFactory());

    String write(SavedState.Type recordType, ManifestVersionRecord currentVersions) throws IOException {
        if (currentVersions == null) {
            return null;
        }

        final String header = currentVersions.getSummary();

        return recordType.name() + " " + header + "\n\n" + toJson(currentVersions);
    }

    SavedState read(String hash, Instant now, String text) throws IOException {
        final SavedState.Type type;
        final String msg;
        final List<SavedState.Version> versions = new ArrayList<>();

        if (!text.contains(" ")) {
            // the message is only type
            type = SavedState.Type.fromText(text);
            text = "";
        } else {
            final int endOfType = text.indexOf(' ');
            type = SavedState.Type.fromText(text.substring(0, endOfType));
            text = text.substring(endOfType + 1);
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
                final ManifestVersionRecord record = JSON_MAPPER.readValue(text, ManifestVersionRecord.class);
                record.getMavenManifests().forEach(m -> versions.add(new SavedState.Version(
                        m.getGroupId() + ":" + m.getArtifactId(),
                        m.getVersion(), m.getDescription())));
                record.getUrlManifests().forEach(m -> versions.add(new SavedState.Version(
                        m.getUrl(), m.getHash(), m.getDescription()
                )));
                record.getOpenManifests().forEach(m ->  versions.add(new SavedState.Version(
                        "unknown", "unknown", m.getSummary()
                )));
            }
        }

        return new SavedState(hash, now, type, msg.isEmpty() ? null : msg, versions);
    }

    private String toJson(ManifestVersionRecord currentVersions) throws IOException {
        return JSON_MAPPER.writeValueAsString(currentVersions);
    }
}
