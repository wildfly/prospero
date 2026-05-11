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

import org.junit.Before;
import org.junit.Test;
import org.wildfly.prospero.api.Console;
import org.wildfly.prospero.api.ProvisioningProgressEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ListPrinterTest {

    private final List<String> output = new ArrayList<>();
    private Console console;

    @Before
    public void setUp() {
        console = new Console() {
            @Override
            public void progressUpdate(ProvisioningProgressEvent update) {
            }

            @Override
            public void println(String text) {
                output.add(text);
            }

        };
    }

    @Test
    public void orderedPrintsSingleItemWithIndex() {
        ListPrinter.ordered(console).printItem("first item");

        assertThat(output).containsExactly("   1. first item");
    }

    @Test
    public void orderedPrintsMultipleItemsWithIncrementingIndex() {
        ListPrinter.ordered(console)
                .printItem("alpha")
                .printItem("beta")
                .printItem("gamma");

        assertThat(output).containsExactly(
                "   1. alpha",
                "   2. beta",
                "   3. gamma");
    }

    @Test
    public void unorderedPrintsSingleItemWithDash() {
        ListPrinter.unordered(console).printItem("first item");

        assertThat(output).containsExactly("  - first item");
    }

    @Test
    public void unorderedPrintsMultipleItemsWithDash() {
        ListPrinter.unordered(console)
                .printItem("alpha")
                .printItem("beta");

        assertThat(output).containsExactly(
                "  - alpha",
                "  - beta");
    }

    @Test
    public void orderedSubListHasIncreasedIndent() {
        ListPrinter.ordered(console)
                .printItem("parent")
                .orderedSubList()
                .printItem("child-a")
                .printItem("child-b");

        assertThat(output).containsExactly(
                "   1. parent",
                "     1. child-a",
                "     2. child-b");
    }

    @Test
    public void unorderedSubListHasIncreasedIndent() {
        ListPrinter.unordered(console)
                .printItem("parent")
                .unorderedSubList()
                .printItem("child-a")
                .printItem("child-b");

        assertThat(output).containsExactly(
                "  - parent",
                "    - child-a",
                "    - child-b");
    }

    @Test
    public void orderedParentWithUnorderedSubList() {
        ListPrinter printer = ListPrinter.ordered(console);
        printer.printItem("first")
                .unorderedSubList()
                .printItem("detail-a")
                .printItem("detail-b");
        printer.printItem("second");

        assertThat(output).containsExactly(
                "   1. first",
                "    - detail-a",
                "    - detail-b",
                "   2. second");
    }

    @Test
    public void unorderedParentWithOrderedSubList() {
        ListPrinter.unordered(console)
                .printItem("group")
                .orderedSubList()
                .printItem("step-1")
                .printItem("step-2");

        assertThat(output).containsExactly(
                "  - group",
                "     1. step-1",
                "     2. step-2");
    }

    @Test
    public void subListIndexResetsForEachNewSubList() {
        ListPrinter printer = ListPrinter.ordered(console);
        printer.printItem("first")
                .orderedSubList()
                .printItem("a")
                .printItem("b");
        printer.printItem("second")
                .orderedSubList()
                .printItem("x");

        assertThat(output).containsExactly(
                "   1. first",
                "     1. a",
                "     2. b",
                "   2. second",
                "     1. x");
    }

    @Test
    public void printItemsWithStringIterable() {
        ListPrinter.unordered(console)
                .printItems(Arrays.asList("alpha", "beta", "gamma"));

        assertThat(output).containsExactly(
                "  - alpha",
                "  - beta",
                "  - gamma");
    }

    @Test
    public void printItemsWithMapper() {
        ListPrinter.ordered(console)
                .printItems(Arrays.asList(10, 20, 30), i -> "item-" + i);

        assertThat(output).containsExactly(
                "   1. item-10",
                "   2. item-20",
                "   3. item-30");
    }

    @Test
    public void printItemsReturnsSelfForChaining() {
        ListPrinter.ordered(console)
                .printItems(Arrays.asList("a", "b"))
                .printItem("c");

        assertThat(output).containsExactly(
                "   1. a",
                "   2. b",
                "   3. c");
    }

    @Test
    public void printTextUsesIndentWithoutMarker() {
        ListPrinter.unordered(console)
                .printText("plain line");

        assertThat(output).containsExactly("  plain line");
    }

    @Test
    public void printTextInSubListUsesSubListIndent() {
        ListPrinter.unordered(console)
                .printItem("parent")
                .unorderedSubList()
                .printText("detail line");

        assertThat(output).containsExactly(
                "  - parent",
                "    detail line");
    }

    @Test
    public void printTextDoesNotAffectIndex() {
        ListPrinter printer = ListPrinter.ordered(console);
        printer.printItem("first");
        printer.printText("note");
        printer.printItem("second");

        assertThat(output).containsExactly(
                "   1. first",
                "  note",
                "   2. second");
    }

    @Test
    public void printTextReturnsSelfForChaining() {
        ListPrinter.unordered(console)
                .printItem("item")
                .unorderedSubList()
                .printText("detail-a")
                .printText("detail-b")
                .unorderedSubList()
                .printItem("nested");

        assertThat(output).containsExactly(
                "  - item",
                "    detail-a",
                "    detail-b",
                "      - nested");
    }
}
