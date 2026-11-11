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
package network.ike.komet.document.surface;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Orientation;
import javafx.scene.control.ScrollBar;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.skin.RichTextAreaSkin;

import java.util.ArrayList;
import java.util.List;

/**
 * One horizontal scrollbar for every unwrapped text area a surface stacks.
 *
 * <p>An unwrapped {@link RichTextArea} scrolls sideways by itself, with a scrollbar of its own
 * at its foot. A stack of them, the Source view's ledgers, then has a bar in the middle of the
 * page for each area with a line wider than the window, each moving its own area alone. Here
 * each area keeps scrolling its own text, so its side decorator stays where it is, but the bar
 * it is given takes no room and draws nothing, and the one bar the reader sees sets them all to
 * the same distance.
 *
 * <p>How an area is moved: its skin offers its bar through
 * {@link RichTextAreaSkin#createHScrollBar()}, and reads that bar as running from 0 to 1 over the
 * distance its text can travel, the bar's visible amount being the viewport's width over the
 * text's. A text that is {@code 1 / visibleAmount} viewports wide and is to stand {@code p}
 * viewports along is therefore at {@code p / (1 / visibleAmount - 1)}; an area whose text fits
 * its viewport answers the same sum with a negative value and moves the same distance, so short
 * ledgers stay in step with long ones.
 */
public final class HorizontalScroll {

    /** How far apart, in pixels, an area's place and the wanted one may be before the area is set again. */
    private static final double TOLERANCE = 0.75;

    /** One area and the bar its skin was given. */
    private record Member(ScrollBar quiet, RichTextArea area) {

        /**
         * The width of the area's viewport: its bar is laid out across the whole area when its text
         * is too wide, and its side decorators, which the area widens to a round number, stand in that.
         */
        double viewport() {
            double width = quiet.getWidth();
            for (String side : List.of(".left-side", ".right-side")) {
                if (area.lookup(side) instanceof javafx.scene.layout.Region column && column.isVisible()) {
                    width -= column.getWidth();
                }
            }
            return width;
        }
    }

    private final ScrollBar bar = new ScrollBar();
    private final List<Member> members = new ArrayList<>();
    /** The width of an area's viewport, in pixels; 0 until an area with a line wider than it is laid out. */
    private double viewport;
    /** How far the text stands to the left, in pixels. */
    private double offset;
    private boolean settlePending;
    private boolean settling;

    /** Creates the scroll, its bar hidden until some area's text is wider than its viewport. */
    public HorizontalScroll() {
        bar.setOrientation(Orientation.HORIZONTAL);
        bar.setMin(0);
        bar.setMax(0);
        bar.setVisible(false);
        bar.setManaged(false);
        bar.getStyleClass().add("doc-horizontal-scroll");
        bar.valueProperty().addListener(obs -> {
            if (!settling) {
                offset = Math.round(bar.getValue() * Math.max(viewport, 1));
                pushAll();
            }
        });
    }

    /** @return the bar the reader moves, for the surface to stand under its body */
    public ScrollBar bar() {
        return bar;
    }

    /** @return whether some area's text is wider than its viewport, so the bar is shown */
    public boolean isShowing() {
        return bar.isManaged();
    }

    /** @return how far the text stands to the left, in pixels */
    public double offset() {
        return offset;
    }

    /**
     * Moves the text, as a trackpad's sideways gesture does.
     *
     * @param pixels how far to move it left; negative for right
     */
    public void scrollBy(double pixels) {
        if (isShowing() && viewport > 0) {
            bar.setValue(Math.clamp(bar.getValue() + pixels / viewport, 0, bar.getMax()));
        }
    }

    /**
     * An unwrapped area whose text this scroll moves, with no scrollbar of its own to be seen.
     * Its side decorators stand still beside the text.
     *
     * @return the area
     */
    public RichTextArea area() {
        HorizontalScroll scroll = this;
        RichTextArea area = new RichTextArea() {
            @Override
            protected RichTextAreaSkin createDefaultSkin() {
                return new Skin(this, scroll);
            }
        };
        area.setWrapText(false);
        return area;
    }

    /** The area's skin, with a bar of no height in place of the one that would stand at the area's foot. */
    private static final class Skin extends RichTextAreaSkin {
        /** Set while the super constructor runs, which asks for the bar: no initializer, or it would be cleared after. */
        private ScrollBar quiet;

        Skin(RichTextArea area, HorizontalScroll scroll) {
            super(area);
            scroll.join(quiet, area);
        }

        @Override
        protected ScrollBar createHScrollBar() {
            quiet = new ScrollBar();
            quiet.setPrefHeight(0);
            quiet.setMinHeight(0);
            quiet.setMaxHeight(0);
            quiet.setOpacity(0);
            quiet.setMouseTransparent(true);
            quiet.setFocusTraversable(false);
            return quiet;
        }
    }

    /** Takes an area's bar while the area is in a scene: an area of a document shown before leaves by leaving the scene. */
    private void join(ScrollBar quiet, RichTextArea area) {
        Member member = new Member(quiet, area);
        InvalidationListener changed = obs -> settleSoon();
        quiet.visibleAmountProperty().addListener(changed);
        quiet.valueProperty().addListener(changed);
        quiet.sceneProperty().addListener((obs, was, is) -> {
            if (is == null) {
                members.remove(member);
            } else if (!members.contains(member)) {
                members.add(member);
            }
            settleSoon();
        });
        if (quiet.getScene() != null) {
            members.add(member);
            settleSoon();
        }
    }

    /**
     * Settles once the current layout is over: an area reports its width, and takes its place
     * back to the start, while it is being laid out, when it does not hear a new place.
     */
    private void settleSoon() {
        if (settlePending || settling) {
            return;
        }
        settlePending = true;
        Platform.runLater(() -> {
            settlePending = false;
            settle();
        });
    }

    /** Sizes the bar to the widest text among the areas, and sets every area that has strayed back to the one place. */
    private void settle() {
        settling = true;
        try {
            double widest = 1;
            for (Member member : members) {
                double visible = member.quiet().getVisibleAmount();
                if (visible > 0 && visible < 1) {
                    widest = Math.max(widest, 1 / visible);
                    double width = member.viewport();
                    if (width > 0) {
                        viewport = width;
                    }
                }
            }
            double range = widest - 1;
            boolean show = viewport > 0 && range * viewport >= 1;
            if (!show) {
                offset = 0;
            } else {
                offset = Math.min(offset, Math.round(range * viewport));
                bar.setMax(range);
                bar.setVisibleAmount(range / widest);
                bar.setUnitIncrement(40 / viewport);
                bar.setBlockIncrement(0.9);
            }
            bar.setValue(show ? offset / viewport : 0);
            bar.setVisible(show);
            bar.setManaged(show);
            pushAll();
        } finally {
            settling = false;
        }
    }

    private void pushAll() {
        boolean was = settling;
        settling = true;
        try {
            for (Member member : members) {
                push(member.quiet());
            }
        } finally {
            settling = was;
        }
    }

    /** Sets one area to the offset, unless it is within a pixel of it: the area rounds its place to whole pixels and says so back. */
    private void push(ScrollBar quiet) {
        double visible = quiet.getVisibleAmount();
        if (!(visible > 0) || viewport <= 0 || Math.abs(1 - visible) < 1e-9) {
            return;
        }
        double travel = 1 / visible - 1;                             // how far the text can go, in viewports; negative when it fits
        double want = offset / viewport / travel;
        if (Math.abs(quiet.getValue() - want) * Math.abs(travel) * viewport > TOLERANCE) {
            quiet.setValue(want);
        }
    }
}
