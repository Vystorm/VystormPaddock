package at.esoren.vystormpaddock;

import java.util.List;
import java.util.Locale;
import java.util.Comparator;

/** A filtered snapshot; menu slots refer to IDs, never to a later list's positions. */
record StablePage(List<Stable.Stored> animals, String search, int number, int pages, int matches, Sort sort) {
    static final int SIZE = 45;

    enum Sort {
        OLDEST, NEWEST, NAME, TYPE;

        Sort next() { return values()[(ordinal() + 1) % values().length]; }
        String key() { return "menu-sort-" + name().toLowerCase(Locale.ROOT); }
        Comparator<Stable.Stored> comparator() {
            Comparator<Stable.Stored> byName = Comparator.comparing(Stable.Stored::name, String.CASE_INSENSITIVE_ORDER);
            return switch (this) {
                case OLDEST -> Comparator.comparingLong(Stable.Stored::storedAt).thenComparing(Stable.Stored::id);
                case NEWEST -> Comparator.comparingLong(Stable.Stored::storedAt).reversed().thenComparing(Stable.Stored::id);
                case NAME -> byName.thenComparingLong(Stable.Stored::storedAt).thenComparing(Stable.Stored::id);
                case TYPE -> Comparator.comparing(Stable.Stored::type).thenComparing(byName).thenComparing(Stable.Stored::id);
            };
        }
    }

    static StablePage of(List<Stable.Stored> animals, String search, int requested) {
        return of(animals, search, requested, Sort.OLDEST);
    }

    static StablePage of(List<Stable.Stored> animals, String search, int requested, Sort sort) {
        return of(animals, search, requested, sort, SIZE);
    }

    static StablePage of(List<Stable.Stored> animals, String search, int requested, Sort sort, int size) {
        String query = search == null ? "" : search.strip();
        String wanted = query.toLowerCase(Locale.ROOT).replace('_', ' ');
        List<Stable.Stored> found = animals.stream().filter(a ->
                a.name().toLowerCase(Locale.ROOT).contains(wanted)
                        || a.type().toLowerCase(Locale.ROOT).replace('_', ' ').contains(wanted))
                .sorted(sort.comparator()).toList();
        int pages = Math.max(1, (found.size() + size - 1) / size);
        int page = Math.clamp(requested, 0, pages - 1);
        return new StablePage(List.copyOf(found.subList(page * size, Math.min(found.size(), (page + 1) * size))),
                query, page, pages, found.size(), sort);
    }

    String animalId(int slot) { return slot >= 0 && slot < animals.size() ? animals.get(slot).id() : null; }
}
