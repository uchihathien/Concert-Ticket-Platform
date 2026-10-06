// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.id;

import java.util.Objects;
import java.util.UUID;

public record UserId(UUID value) {
    public UserId {
        Objects.requireNonNull(value, "UserId không được null");
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }

    public static UserId parse(String value) {
        return new UserId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
