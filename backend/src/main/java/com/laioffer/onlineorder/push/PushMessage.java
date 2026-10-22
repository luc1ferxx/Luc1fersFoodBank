package com.laioffer.onlineorder.push;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


/**
 * The Redis pub/sub payload of a signal: {@code {"recipient":"restaurant:5","topic":"merchant.orders"}}. Unknown
 * properties are ignored, so a later release may add some without the instances of this one dropping its messages.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PushMessage(String recipient, String topic) {
}
