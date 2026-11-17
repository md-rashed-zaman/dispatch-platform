package dev.rashed.dispatch.outbox;

/** Kafka topics Dispatch publishes to. ".v1": a breaking change to an event gets a new topic. */
public final class Topics {

    /** Lifecycle events of work orders, keyed by work order id. */
    public static final String WORK_ORDER_EVENTS = "dispatch.workorder.events.v1";

    /** Technician profile changes, keyed by technician id. */
    public static final String TECHNICIAN_EVENTS = "dispatch.technician.events.v1";

    private Topics() {}
}
