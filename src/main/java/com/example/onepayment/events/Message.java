package com.example.onepayment.events;

import java.util.Map;

/** A broker record: headers travel next to the payload, like Kafka record headers. */
public record Message<T>(String topic, Map<String, String> headers, T payload) {
}
