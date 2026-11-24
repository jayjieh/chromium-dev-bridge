package dev.cdb.protocol;

public record FormFieldInfo(String type, String name, String label, boolean required, String placeholder, String value,
                            boolean sensitive, boolean valid, String validationMessage) {
}
