package com.distelli.europa.models;

import java.util.Arrays;
import java.util.Objects;

public enum NotificationType
{
    WEBHOOK,
    EMAIL,
    SLACK,
    HIPCHAT;

    private static final NotificationType[] values = values();

    public static NotificationType valueOf(int ordinal) {
        if ( ordinal < 0 || ordinal >= values.length ) return null;
        return values[ordinal];
    }

}