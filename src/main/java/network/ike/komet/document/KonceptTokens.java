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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import network.ike.komet.document.ConceptChipInlineDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serialization of id-bearing {@code k:} interchange tokens for the compose surface: the
 * edit-projection counterpart of {@link ConceptChipInlineDecorator}'s read projection — prose
 * <em>edits as {@code k:} tokens and reads as chips</em>. Emits exactly the tight inline grammar
 * the decorator parses, so a composed message re-chips verbatim in the echoed turn. This is the
 * send-extraction serializer for the live inline-chip input ({@code IKE-Network/ike-issues#789}):
 * walking the compose model's segments, each chip serializes through {@link #token}.
 *
 * <p>Pure string functions, deliberately: serialization is testable without a store or a toolkit.
 * The one exception is {@link Found#name()}, which asks the open store for the name of a token
 * written without a label, and finds none when no store is running.
 * Interim home — the canonical token (parse/serialize + adoc macro) is
 * {@code IKE-Network/ike-issues#735}'s remit in koncept-core; this class carries only what the
 * compose surface needs and can retire onto it.
 */
public final class KonceptTokens {

    private KonceptTokens() {
    }

    /**
     * The id spaces a token can be written in. Both are public: an identifier in either space
     * means the same component in every store. A nid is local to one store, so it is not a kind,
     * and no token can be written with one ({@code IKE-Network/ike-issues#1170}).
     */
    public enum Kind {
        /** A UUID of the component's public id. */
        UUID("uuid"),
        /** A SNOMED CT identifier. */
        SCTID("sctid");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        /**
         * The kind as it is written in a token, between {@code k:} and {@code =}.
         *
         * @return the token key, {@code uuid} or {@code sctid}
         */
        public String key() {
            return key;
        }
    }

    /**
     * One id-bearing token, e.g. {@code k:uuid=e07f…[Chronic disease]}. The label is display text
     * for the reader; identity travels in the id, and the rendered chip always shows the
     * store-resolved name regardless of the label.
     *
     * @param kind  the id space
     * @param id    the identifier in that space
     * @param label the display label; brackets are stripped (the grammar reserves them), and a
     *              blank label omits the bracket segment entirely
     * @return the serialized token
     */
    public static String token(Kind kind, String id, String label) {
        String clean = label == null ? "" : label.replace("[", "").replace("]", "").trim();
        return clean.isEmpty()
                ? "k:" + kind.key() + "=" + id
                : "k:" + kind.key() + "=" + id + "[" + clean + "]";
    }

    /**
     * The tight inline token grammar, for the display projection (kept in step with the decorator).
     * It still recognizes the {@code nid} kind that text stored before
     * {@code IKE-Network/ike-issues#1170} can hold, only so that such a token projects as its
     * label; a nid is never resolved.
     */
    private static final Pattern TOKEN =
            Pattern.compile("k:(?<kind>sctid|uuid|nid|id)=(?<id>[0-9a-fA-F-]+)(?:\\[(?<label>[^\\[\\]]*)])?");

    /**
     * A token as it stands in a text.
     *
     * @param start the token's first character
     * @param end   the character after its last, the label's closing bracket included
     * @param kind  the id space as written: {@code uuid}, {@code sctid}, {@code id}, or the
     *              {@code nid} of text stored before {@code IKE-Network/ike-issues#1170}
     * @param id    the identifier as written
     * @param label the words between the brackets, or {@code null} when the token has none
     */
    public record Found(int start, int end, String kind, String id, String label) {

        /** @return where the label's first character stands in the text, or {@code -1} when the token has none */
        public int labelStart() {
            return label == null ? -1 : end - 1 - label.length();
        }

        /**
         * The name a reader is shown for the token where it cannot be drawn as a chip, as on a
         * printed page: its label, which its author wrote for the reader; else the name the open
         * store gives the component it names. A nid is never resolved: it names a component only
         * in the store that assigned it.
         *
         * @return the name, or empty when the token has no label and names nothing the open store holds
         */
        public Optional<String> name() {
            if (label != null && !label.isBlank()) {
                return Optional.of(label.strip());
            }
            try {
                PublicId component = publicId(kind, id);
                if (component != null && PrimitiveData.running() && PrimitiveData.get().hasPublicId(component)) {
                    return PrimitiveData.textOptional(PrimitiveData.nid(component)).map(String::strip).filter(name -> !name.isEmpty());
                }
            } catch (RuntimeException unresolvable) {
                // an identifier that is not one, or a store that cannot say: nothing to name it by
            }
            return Optional.empty();
        }
    }

    /**
     * The tokens of a text, in order.
     *
     * @param text the text; may be null
     * @return the tokens, none overlapping
     */
    public static List<Found> find(String text) {
        if (text == null || text.indexOf("k:") < 0) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            found.add(new Found(m.start(), m.end(), m.group("kind"), m.group("id"), m.group("label")));
        }
        return found;
    }

    /**
     * The public id a token's identifier names. The generic {@code id} kind (mirroring the
     * koncept-tree line grammar) is read as a UUID first, then as an SCTID-shaped number. Nothing
     * here asks a store whether the component exists.
     *
     * @param kind the id space as written in the token
     * @param id   the identifier
     * @return the public id; {@code null} for a nid, which is not a public identifier
     * @throws IllegalArgumentException when the identifier is not one of its kind
     */
    public static PublicId publicId(String kind, String id) {
        return switch (kind) {
            case "uuid" -> PublicIds.of(UUID.fromString(id));
            case "sctid" -> PublicIds.of(UuidUtil.fromSNOMED(id));
            case "id" -> {
                try {
                    yield PublicIds.of(UUID.fromString(id));
                } catch (IllegalArgumentException notAUuid) {
                    yield PublicIds.of(UuidUtil.fromSNOMED(id));
                }
            }
            default -> null;
        };
    }

    /**
     * The human-display projection of token text: each {@code k:} token replaced by its label (or
     * elided when unlabelled) — for places that show the message as a name, like the conversation
     * rail, where a raw truncated token would read as noise.
     *
     * @param tokenText prose carrying {@code k:} tokens; may be null
     * @return the prose with tokens replaced by their labels, whitespace collapsed
     */
    public static String display(String tokenText) {
        if (tokenText == null || tokenText.isEmpty()) {
            return "";
        }
        Matcher m = TOKEN.matcher(tokenText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label = m.group("label");
            m.appendReplacement(sb, Matcher.quoteReplacement(label == null ? "" : label));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
