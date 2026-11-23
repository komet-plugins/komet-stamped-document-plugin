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

import dev.ikm.tinkar.common.util.time.DateTimeUtil;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * When a turn of a conversation was said, as its heading says it: {@code Nov 23rd, 11:42 am}, in the
 * time zone of the computer. The year is said when it is not the year now, and always on a printed
 * page, which is read in later years.
 */
final class TurnTime {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private TurnTime() {
    }

    /**
     * The time as a heading on screen says it.
     *
     * @param at when the turn was said
     * @return the date and time, with the year only when it is another year
     */
    static String onScreen(Instant at) {
        ZoneId zone = ZoneId.systemDefault();
        return text(at, zone, Instant.now().atZone(zone).getYear() != at.atZone(zone).getYear());
    }

    /**
     * The time as a heading on a printed page says it.
     *
     * @param at when the turn was said
     * @return the date with its year, and the time
     */
    static String onPage(Instant at) {
        return text(at, ZoneId.systemDefault(), true);
    }

    static String text(Instant at, ZoneId zone, boolean withYear) {
        ZonedDateTime local = at.atZone(zone);
        int day = local.getDayOfMonth();
        return MONTH.format(local) + " " + day + DateTimeUtil.getDayOfMonthSuffix(day)
                + (withYear ? ", " + local.getYear() : "")
                + ", " + TIME.format(local).toLowerCase(Locale.ENGLISH);
    }
}
