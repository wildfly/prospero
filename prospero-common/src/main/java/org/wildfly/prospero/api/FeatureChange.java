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

package org.wildfly.prospero.api;

import java.util.Locale;

/**
 * Represents changes to feature-packs used to provison the server.
 */
public class FeatureChange extends Diff {

    private final Type type;

    /**
     * Type of feature change record
     */
    public enum Type { FEATURE, LAYERS, CONFIG }

    public FeatureChange(Type type, String oldValue, String newValue) {
        super(type.name().toLowerCase(Locale.ROOT), oldValue, newValue);
        this.type = type;
    }

    public FeatureChange(Type type, String name, Status status, Diff... nested) {
        super(name, status, nested);
        this.type = type;
    }

    public Type getType() {
        return type;
    }
}
