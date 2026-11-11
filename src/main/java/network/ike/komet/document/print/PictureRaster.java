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
package network.ike.komet.document.print;

import javafx.scene.image.Image;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * A picture file as an image JavaFX can draw (IKE-Network/ike-issues#1351). JavaFX reads PNG,
 * JPEG and GIF itself and has no reader for SVG, which is what a chart is saved as. Typst has
 * one, and it is Typst that sets the picture in the PDF: so an SVG is drawn once by Typst, on a
 * page of the picture's own size with nothing else on it, and the page is what the screen
 * shows. The page and the PDF then show the same drawing of the same file.
 */
public final class PictureRaster {

    /** How many times its size an SVG is drawn at, so it stays sharp on a dense display and scaled up a little. */
    private static final int SHARPNESS = 2;

    private PictureRaster() { }

    /**
     * The image of a picture file.
     *
     * @param picture the picture
     * @return the image, or empty when the file cannot be read, or is an SVG and typst is not installed
     */
    public static Optional<Image> image(DocumentModel.Picture picture) {
        String name = picture.file().getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            Path file = name.endsWith(".svg") ? drawn(picture) : picture.file();
            if (file == null) {
                return Optional.empty();
            }
            Image image = new Image(file.toUri().toString());
            return image.isError() ? Optional.empty() : Optional.of(image);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** An SVG drawn by Typst to a PNG beside a copy of it, in a folder of its own. */
    private static Path drawn(DocumentModel.Picture picture) throws IOException {
        if (TypstCompiler.locate().isEmpty()) {
            return null;
        }
        Path folder = Files.createTempDirectory("stamped-document-picture-");
        Path copy = folder.resolve("picture.svg");
        Files.copy(picture.file(), copy);
        Path source = folder.resolve("picture.typ");
        // the page is the picture, several times its size: the renderer's fixed resolution then gives that many pixels to the point
        Files.writeString(source, "#set page(width: " + picture.width() * SHARPNESS + "pt, height: " + picture.height() * SHARPNESS
                + "pt, margin: 0pt)\n#image(\"picture.svg\", width: 100%, height: 100%)\n", StandardCharsets.UTF_8);
        Path png = folder.resolve("picture.png");
        TypstCompiler.Result result = TypstCompiler.renderPage(folder, source, png, 1);
        return result.ok() && Files.isRegularFile(png) ? png : null;
    }
}
