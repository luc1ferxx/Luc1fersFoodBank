package com.laioffer.onlineorder.push;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


/**
 * The payload each instance of the redis fan-out publishes to itself on the ":probe" sibling of the push channel:
 * {@code {"probe":"<instance id>","seq":7}}. The probe id is the sending instance's own random id, so every other
 * instance ignores the message; the sequence tells a late answer from the one the sender waits for. Boxed components
 * and ignored unknown properties, like {@link PushMessage}, so a later release's probe does not fail this one's reader.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PushProbe(String probe, Long seq) {
}
