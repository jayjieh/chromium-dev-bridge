package dev.cdb.protocol;

public record StackFrame(String function, SourceLocation location, SourceLocation original) {
    public StackFrame withOriginal(SourceLocation o) {
        return new StackFrame(function, location, o);
    }
}
