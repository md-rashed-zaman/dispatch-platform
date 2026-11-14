package dev.rashed.dispatch.shared;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list. {@code next} is the cursor for the following page, or null on the last page.
 *
 * @param <T> the item type
 */
public record CursorPage<T>(List<T> items, @Nullable String next) {

    /**
     * Builds a page from up to {@code limit + 1} rows: the extra row only shows that another page exists, so
     * the last page never hands out a cursor to an empty page.
     */
    public static <T> CursorPage<T> of(List<T> rows, int limit, Function<T, Cursor> cursorOf) {
        if (rows.size() <= limit) {
            return new CursorPage<>(rows, null);
        }
        var items = rows.subList(0, limit);
        return new CursorPage<>(
                List.copyOf(items), cursorOf.apply(items.getLast()).encode());
    }

    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).toList(), next);
    }
}
