/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches.compat;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * List methods added in Android 15 (API 35, SequencedCollection). X calls them directly, which throws
 * NoSuchMethodError on Android 14 and older (for example while recording an X Chat voice note).
 */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
public final class SequencedListCompat {
    private SequencedListCompat() {
    }

    public static Object removeFirst(List list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.remove(0);
    }

    public static Object removeLast(List list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.remove(list.size() - 1);
    }

    public static Object getFirst(List list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.get(0);
    }

    public static Object getLast(List list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.get(list.size() - 1);
    }

    public static void addFirst(List list, Object element) {
        list.add(0, element);
    }

    public static void addLast(List list, Object element) {
        list.add(element);
    }
}
