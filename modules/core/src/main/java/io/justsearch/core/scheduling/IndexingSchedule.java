/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import java.util.List;
import java.util.HashSet;

/** One retained local-wall-clock window; overnight hours belong to their starting day. */
public record IndexingSchedule(boolean enabled, List<Integer> days, int startMinute, int endMinute) {
  public IndexingSchedule {
    if (days == null || days.stream().anyMatch(day -> day == null || day < 1 || day > 7)
        || new HashSet<>(days).size() != days.size() || (enabled && days.isEmpty())) {
      throw new IllegalArgumentException("schedule.days must be unique ISO weekdays 1..7, nonempty when enabled");
    }
    if (startMinute < 0 || startMinute > 1439 || endMinute < 0 || endMinute > 1439
        || startMinute == endMinute) {
      throw new IllegalArgumentException("schedule minutes must be 0..1439 with different endpoints");
    }
    days = List.copyOf(days);
  }

  /** Start inclusive, end exclusive, with Sunday wrapping to Monday. No zone conversion. */
  public boolean allows(int weekday, int minute) {
    if (weekday < 1 || weekday > 7 || minute < 0 || minute > 1439) {
      throw new IllegalArgumentException("Invalid local weekday or minute");
    }
    if (!enabled) return true;
    if (startMinute < endMinute) {
      return days.contains(weekday) && minute >= startMinute && minute < endMinute;
    }
    int yesterday = weekday == 1 ? 7 : weekday - 1;
    return days.contains(weekday) && minute >= startMinute
        || days.contains(yesterday) && minute < endMinute;
  }
}
