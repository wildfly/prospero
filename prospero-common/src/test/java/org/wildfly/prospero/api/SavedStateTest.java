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

package org.wildfly.prospero.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SavedStateTest {

    @Test
    public void versionDisplayDefaultsToLogicalVersion() {
        final SavedState.Version version = new SavedState.Version("abcd", "1.0.0", "Logical version");

        assertEquals("Logical version", version.getDisplayVersion());
    }

    @Test
    public void versionDisplayFallsBackToPhysicalVersionIfLogicalVersionDoesNotExist() {
        final SavedState.Version version = new SavedState.Version("abcd", "1.0.0", null);

        assertEquals("abcd:1.0.0", version.getDisplayVersion());
    }

    @Test
    public void testVersionOrdering() {
        SavedState.Version v1 = new SavedState.Version("aaa", "2", "1");
        SavedState.Version v2 = new SavedState.Version("bbb", "2", "1");
        assertThat(v1).isLessThan(v2);

        v2 = new SavedState.Version("aaa", "3", "1");
        assertThat(v1).isLessThan(v2);

        v2 = new SavedState.Version("aaa", "1", "1");
        assertThat(v1).isGreaterThan(v2);

        v2 = new SavedState.Version("aaa", "2", "2");
        assertThat(v1).isLessThan(v2);
    }
}