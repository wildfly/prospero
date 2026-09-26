/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
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

package org.wildfly.prospero.cli.printers;

import org.wildfly.prospero.api.Console;

import java.util.function.Function;

public class ListPrinter {

    private static final String BASE_INDENT = "  ";
    private static final String INDENT_STEP = "  ";
    private static final String ORDERED_FORMAT = "%2d. ";
    private static final String UNORDERED_MARKER = "- ";

    private final Console console;
    private final boolean ordered;
    private final String indent;
    private int index = 0;

    private ListPrinter(Console console, boolean ordered, String indent) {
        this.console = console;
        this.ordered = ordered;
        this.indent = indent;
    }

    public static ListPrinter ordered(Console console) {
        return new ListPrinter(console, true, BASE_INDENT);
    }

    public static ListPrinter unordered(Console console) {
        return new ListPrinter(console, false, BASE_INDENT);
    }

    /**
     * Prints a list item. Returns {@code this} for chaining.
     */
    public ListPrinter printItem(String text) {
        console.println(prefix() + text);
        return this;
    }

    /**
     * Prints text with indentation but without a list marker. Returns {@code this} for chaining.
     */
    public ListPrinter printText(String text) {
        console.println(indent + text);
        return this;
    }

    /**
     * Prints all items from the iterable. Returns {@code this} for chaining.
     */
    public ListPrinter printItems(Iterable<String> items) {
        return printItems(items, Function.identity());
    }

    /**
     * Prints all items from the iterable to standard output, applying the mapper. Returns {@code this} for chaining.
     */
    public <T> ListPrinter printItems(Iterable<T> items, Function<T, String> mapper) {
        for (T item : items) {
            printItem(mapper.apply(item));
        }
        return this;
    }

    public ListPrinter orderedSubList() {
        return new ListPrinter(console, true, indent + INDENT_STEP);
    }

    public ListPrinter unorderedSubList() {
        return new ListPrinter(console, false, indent + INDENT_STEP);
    }

    private String prefix() {
        if (ordered) {
            return indent + String.format(ORDERED_FORMAT, ++index);
        }
        return indent + UNORDERED_MARKER;
    }
}
