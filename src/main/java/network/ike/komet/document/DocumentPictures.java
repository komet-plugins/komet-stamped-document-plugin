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
package network.ike.komet.document;

import network.ike.komet.document.print.DocumentModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The picture files a document's image blocks name (IKE-Network/ike-issues#1351). The store
 * holds a document's lines, not its pictures, so a picture is read from the working tree the
 * document was materialized from: {@code image::{topics}/images/rda/tornado-scan.svg[]} names a
 * file beside the topics, in git with them.
 *
 * <p>A target is looked for as AsciiDoc looks, from the directory of the document the person
 * opened; then, since a topic is written to be included from more than one book, from the
 * directory of the file the image line is in and from each directory above it up to the tree's
 * root. The first that holds the file has it. A target that is an address, or that leaves the
 * tree, names no file here.
 *
 * <p>A picture is set at its own size, a pixel three quarters of a point as a browser and
 * Asciidoctor have it, and the page scales it down when it is wider than the measure.
 */
public final class DocumentPictures {

    private static final Pattern SVG_TAG = Pattern.compile("<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WIDTH = Pattern.compile("\\bwidth\\s*=\\s*[\"']\\s*([0-9.]+)\\s*(px|pt|mm|cm|in)?\\s*[\"']");
    private static final Pattern HEIGHT = Pattern.compile("\\bheight\\s*=\\s*[\"']\\s*([0-9.]+)\\s*(px|pt|mm|cm|in)?\\s*[\"']");
    private static final Pattern VIEW_BOX = Pattern.compile("\\bviewBox\\s*=\\s*[\"']\\s*[-0-9.eE]+[\\s,]+[-0-9.eE]+[\\s,]+([0-9.eE]+)[\\s,]+([0-9.eE]+)\\s*[\"']");

    /** A pixel in points: 96 to the inch against 72. */
    static final double PIXEL = 0.75;

    private DocumentPictures() { }

    /**
     * The file an image's target names.
     *
     * @param roots  the working trees to look in, the document's own first
     * @param host   the repository-relative path of the document the person opened
     * @param member the repository-relative path of the file the image line is in
     * @param target the target as it reads, its attribute references given their values
     * @return the file, if a tree holds it
     */
    public static Optional<Path> file(List<Path> roots, String host, String member, String target) {
        String named = target == null ? "" : target.strip();
        if (named.isEmpty() || named.contains("://") || named.startsWith("data:")) {
            return Optional.empty();
        }
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path tree = root.toAbsolutePath().normalize();
            Optional<Path> found = under(tree, parent(tree, host), named, false);
            if (found.isEmpty()) {
                found = under(tree, parent(tree, member), named, true);
            }
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Path parent(Path tree, String path) {
        if (path == null || path.isEmpty()) {
            return tree;
        }
        Path parent = tree.resolve(path).normalize().getParent();
        return parent == null || !parent.startsWith(tree) ? tree : parent;
    }

    /** The file as named from a directory, and when {@code above} from each directory over it up to the tree's root. */
    private static Optional<Path> under(Path tree, Path from, String named, boolean above) {
        for (Path directory = from; directory != null && directory.startsWith(tree); directory = directory.getParent()) {
            Path file = directory.resolve(named).normalize();
            if (file.startsWith(tree) && Files.isRegularFile(file)) {
                return Optional.of(file);
            }
            if (!above) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * A picture file with its own size.
     *
     * @param file the file
     * @return the picture, or empty when the file is not a picture whose size can be read
     */
    public static Optional<DocumentModel.Picture> picture(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".svg")) {
                return svgSize(Files.readString(file, StandardCharsets.UTF_8)).map(size -> new DocumentModel.Picture(file, size[0], size[1]));
            }
            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif")) {
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
                return image == null ? Optional.empty() : Optional.of(new DocumentModel.Picture(file, image.getWidth() * PIXEL, image.getHeight() * PIXEL));
            }
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * The size an SVG gives itself, in points: its {@code width} and {@code height}, and where
     * it gives none, or gives them as a share, the size of its {@code viewBox}.
     *
     * @param svg the file's text
     * @return {@code {width, height}}, or empty when the root element says neither
     */
    static Optional<double[]> svgSize(String svg) {
        Matcher tag = SVG_TAG.matcher(svg);
        if (!tag.find()) {
            return Optional.empty();
        }
        String root = tag.group();
        Matcher width = WIDTH.matcher(root);
        Matcher height = HEIGHT.matcher(root);
        Matcher box = VIEW_BOX.matcher(root);
        if (width.find() && height.find()) {
            return Optional.of(new double[] {points(width.group(1), width.group(2)), points(height.group(1), height.group(2))});
        }
        if (box.find()) {
            return Optional.of(new double[] {Double.parseDouble(box.group(1)) * PIXEL, Double.parseDouble(box.group(2)) * PIXEL});
        }
        return Optional.empty();
    }

    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern NAMED_WIDTH = Pattern.compile("(?:^|,)\\s*(?:pdf|scaled)?width\\s*=\\s*([0-9.]+%?)");

    /**
     * A picture at the width its image line asks for: the second place of the line's
     * attributes, or {@code width=}, in pixels, or as a share of the page's measure. Its height
     * follows, so it keeps its shape. A line that asks for none leaves the picture its own size.
     *
     * @param picture    the picture at its own size
     * @param attributes what stands in the image line's brackets
     * @param measure    the page's measure in points, for a width given as a share; 0 when it is not known
     * @return the picture at the width asked for
     */
    public static DocumentModel.Picture sized(DocumentModel.Picture picture, String attributes, double measure) {
        String bare = QUOTED.matcher(attributes == null ? "" : attributes).replaceAll("x");   // an alternative text may hold commas
        String asked = null;
        Matcher named = NAMED_WIDTH.matcher(bare);
        if (named.find()) {
            asked = named.group(1);
        } else {
            String[] places = bare.split(",");
            if (places.length > 1 && places[1].strip().matches("[0-9.]+%?")) {
                asked = places[1].strip();
            }
        }
        if (asked == null || picture.width() <= 0) {
            return picture;
        }
        double width;
        try {
            width = asked.endsWith("%") ? measure * Double.parseDouble(asked.substring(0, asked.length() - 1)) / 100 : Double.parseDouble(asked) * PIXEL;
        } catch (NumberFormatException notANumber) {
            return picture;
        }
        return width <= 0 ? picture : new DocumentModel.Picture(picture.file(), width, picture.height() * width / picture.width());
    }

    private static double points(String number, String unit) {
        double value = Double.parseDouble(number);
        return switch (unit == null ? "px" : unit) {
            case "pt" -> value;
            case "mm" -> value * 72 / 25.4;
            case "cm" -> value * 72 / 2.54;
            case "in" -> value * 72;
            default -> value * PIXEL;
        };
    }
}
