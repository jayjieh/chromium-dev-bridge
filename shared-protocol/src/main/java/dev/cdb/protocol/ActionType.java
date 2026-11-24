package dev.cdb.protocol;

public enum ActionType {
    CLICK, DOUBLE_CLICK, HOVER, SCROLL, FILL, CLEAR, TYPE, PRESS, SELECT, CHECK, UNCHECK, DRAG_AND_DROP,
    FOCUS, GET_TEXT, GET_ATTRIBUTE, IS_VISIBLE, CLICK_AT;

    /** Actions that only read state or move focus/pointer. */
    public boolean isReadOnly() {
        return this == GET_TEXT || this == GET_ATTRIBUTE || this == IS_VISIBLE || this == HOVER || this == FOCUS;
    }

    public boolean needsLocator() {
        return this != SCROLL && this != PRESS && this != CLICK_AT;
    }
}
