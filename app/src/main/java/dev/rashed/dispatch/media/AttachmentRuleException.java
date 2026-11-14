package dev.rashed.dispatch.media;

import dev.rashed.dispatch.shared.BusinessRuleException;

/** 409 for an upload the current state doesn't allow, with a type clients can switch on. */
class AttachmentRuleException extends BusinessRuleException {

    private final String type;

    AttachmentRuleException(String type, String message) {
        super(message);
        this.type = type;
    }

    @Override
    public String type() {
        return type;
    }
}
