package org.wildfly.prospero.installation.git;

import org.junit.Before;
import org.junit.Test;
import org.wildfly.prospero.api.SavedState;
import org.wildfly.prospero.metadata.ManifestVersionRecord;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SavedStateParserTest {

    protected static final Instant A_TIMESTAMP = Instant.now();
    protected static final String A_HASH = "abcd";
    private SavedStateParser savedStateParser;

    @Before
    public void setUp() throws Exception {
        this.savedStateParser = new SavedStateParser();
    }

    @Test
    public void readSavedStateOperationOnly() throws Exception {
        final SavedState state = savedStateParser.read(A_HASH, A_TIMESTAMP, SavedState.Type.INSTALL.toString());

        assertThat(state)
                .isEqualTo(new SavedState(A_HASH, A_TIMESTAMP, SavedState.Type.INSTALL, null, Collections.emptyList()));
    }

    @Test
    public void readSavedStateOperationAndShortDescription() throws Exception {
        final String msg = SavedState.Type.INSTALL + " [foo:bar]";

        final SavedState state = savedStateParser.read(A_HASH, A_TIMESTAMP, msg);

        assertThat(state)
                .isEqualTo(new SavedState(A_HASH, A_TIMESTAMP, SavedState.Type.INSTALL, "[foo:bar]", Collections.emptyList()));
    }

    @Test
    public void readSavedStateFullRecord() throws Exception {
        final ManifestVersionRecord record = new ManifestVersionRecord("1.0.0",
                List.of(new ManifestVersionRecord.MavenManifest("org.foo", "bar", "1.0.0", "Update 1")),
                Collections.emptyList(), Collections.emptyList());
        final String msg = savedStateParser.write(SavedState.Type.INSTALL, record);

        final SavedState state = savedStateParser.read(A_HASH, A_TIMESTAMP, msg);

        assertThat(state)
                .isEqualTo(new SavedState(A_HASH, A_TIMESTAMP, SavedState.Type.INSTALL, "[org.foo:bar::1.0.0]",
                        List.of(new SavedState.Version("org.foo:bar", "1.0.0", "Update 1"))));
    }

    @Test
    public void readSavedStateShortStatusWithTrailingNewLines() throws Exception {
        final String msg = SavedState.Type.INSTALL + " [foo:bar]\n\n";

        final SavedState state = savedStateParser.read(A_HASH, A_TIMESTAMP, msg);

        assertThat(state)
                .isEqualTo(new SavedState(A_HASH, A_TIMESTAMP, SavedState.Type.INSTALL, "[foo:bar]", Collections.emptyList()));
    }

}