package dev.rashed.dispatch.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorTest {

    @Test
    void roundTripsThroughAnOpaqueUrlSafeToken() {
        var cursor = new Cursor("2026-11-14T04:00:00.123456Z", UUID.randomUUID());

        var token = cursor.encode();

        assertThat(token).matches("[A-Za-z0-9_-]+");
        assertThat(Cursor.decode(token)).isEqualTo(cursor);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"not base64!", "bm8tc2VwYXJhdG9y" /* "no-separator" */, "eHxub3QtYS11dWlk" /* "x|not-a-uuid" */})
    void rejectsTokensItDidNotMake(String token) {
        assertThatThrownBy(() -> Cursor.decode(token)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void sortValuesMayContainTheSeparator() {
        var id = UUID.randomUUID();
        var token = Base64.getUrlEncoder().encodeToString(("a|b|" + id).getBytes());

        assertThat(Cursor.decode(token)).isEqualTo(new Cursor("a|b", id));
    }

    @Test
    void pageHandsOutACursorOnlyWhenAnotherPageExists() {
        var lastPage = CursorPage.of(List.of("a", "b"), 2, s -> new Cursor(s, UUID.randomUUID()));
        var fullPage = CursorPage.of(List.of("a", "b", "c"), 2, s -> new Cursor(s, UUID.randomUUID()));

        assertThat(lastPage.items()).containsExactly("a", "b");
        assertThat(lastPage.next()).isNull();
        assertThat(fullPage.items()).containsExactly("a", "b");
        assertThat(Cursor.decode(fullPage.next()).sortValue()).isEqualTo("b");
    }
}
