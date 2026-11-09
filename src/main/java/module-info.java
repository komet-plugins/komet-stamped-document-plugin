/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * The stamped document as a Komet plugin (IKE-Network/ike-issues#1351): AsciiDoc documents kept
 * in git, read and edited line by line with each line's STAMP, on a block surface that pages and
 * prints. Komet stages it into its plugins/ layer with or without the assistant; the assistant and
 * the profile plugins provide {@link network.ike.komet.document.StructuredBlockKind}s that draw
 * their blocks on its surface.
 */
module komet.stamped.document {
    requires dev.ikm.komet.layout;
    requires dev.ikm.komet.framework;
    requires dev.ikm.komet.preferences;
    requires dev.ikm.komet.actions;
    requires dev.ikm.komet.markdown.richtext;
    requires dev.ikm.komet.terms;
    requires dev.ikm.tinkar.entity;
    requires dev.ikm.tinkar.common;
    requires dev.ikm.tinkar.provider.search;
    requires dev.ikm.tinkar.provider.grpc;
    requires dev.ikm.tinkar.events;
    requires org.slf4j;
    requires org.eclipse.collections.api;
    requires javafx.controls;
    requires org.kordamp.ikonli.javafx;
    requires jfx.incubator.richtext;
    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires stamp.adoc.core;
    requires network.ike.foundation.ike.bindings;
    requires com.sparrowwallet.toucan;
    requires java.desktop;

    exports network.ike.komet.document;
    exports network.ike.komet.document.print;
    exports network.ike.komet.document.surface;
    exports network.ike.komet.document.surface.print;
    exports network.ike.komet.document.surface.print.settings;
    exports network.ike.komet.document.help;

    // The structured block kinds a document draws, which other plugins provide, and the one a document draws itself.
    uses network.ike.komet.document.StructuredBlockKind;
    provides network.ike.komet.document.StructuredBlockKind
            with network.ike.komet.document.diagram.DiagramBlockKind;

    // Journal "+" menu entry.
    provides dev.ikm.komet.layout_engine.host.KlCardProvider
            with network.ike.komet.document.StampedDocumentCard.Factory;
    // The document's Komet actions, found through PluggableService like Komet's own. Class-path
    // tests find them through META-INF/services, which lists the same classes.
    provides dev.ikm.komet.actions.KometAction
            with network.ike.komet.document.EditDocumentBlock,
                 network.ike.komet.document.EditDocumentSentence,
                 network.ike.komet.document.AddDocumentBlock,
                 network.ike.komet.document.DeleteDocumentBlock;
    // The Documents tray of the sidebar.
    provides dev.ikm.komet.layout.tray.KlSidebarTrayProvider
            with network.ike.komet.document.DocumentNavigatorTrayProvider;
    // The plugin's help book (AsciiDoc under META-INF/komet-help) and the viewer that renders
    // AsciiDoc help, for this book and every other a plugin carries.
    provides dev.ikm.komet.layout.help.KlHelpProvider
            with network.ike.komet.document.help.DocumentHelp;
    provides dev.ikm.komet.layout.help.KlHelpViewer.Factory
            with network.ike.komet.document.help.AsciiDocHelpViewerFactory;
    // Saved layouts that name a class by its komet-claude-plugin package still restore.
    provides dev.ikm.tinkar.common.service.RenamedClasses
            with network.ike.komet.document.DocumentRenames;

    opens network.ike.komet.document.surface.print.settings;
}
