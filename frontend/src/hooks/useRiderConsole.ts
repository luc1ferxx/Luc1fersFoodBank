import { useCallback, useState } from "react";
import { ApiError } from "../api/client";
import type { DeliveryStatus, RiderDelivery } from "../api/dto";
import {
  getRiderDeliveries,
  getRiderProfile,
  markDeliveryDelivered,
  markDeliveryPickedUp,
  updateRiderAvailability,
} from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { withEntry, withoutEntry } from "../lib/maps";
import { useLiveResource } from "./useLiveResource";
import { usePolling } from "./usePolling";

export const RIDER_POLL_MS = 15000;

/** "ACTIVE" is the backend default: ASSIGNED and PICKED_UP. */
export type RiderDeliveryFilter = "ACTIVE" | Exclude<DeliveryStatus, "CANCELLED">;
export type RiderCommand = "picked-up" | "delivered";

const fetchProfile = (_key: "me", signal: AbortSignal) => getRiderProfile({ signal });

const fetchDeliveries = (filter: RiderDeliveryFilter, signal: AbortSignal) =>
  getRiderDeliveries({ status: filter === "ACTIVE" ? undefined : filter, signal });

/**
 * The rider console: the rider's profile with the on-duty switch, and their own
 * deliveries with picked-up / delivered. Both are polled every 15 seconds while the
 * tab is visible, since assignments arrive asynchronously from the dispatch module.
 * The delivery commands are idempotent on the server; a 404 (not this rider's
 * delivery) or 409 (wrong state) re-reads the list.
 */
export function useRiderConsole() {
  const [filter, setFilter] = useState<RiderDeliveryFilter>("ACTIVE");
  const [polling, setPolling] = useState(true);
  const [availabilityPending, setAvailabilityPending] = useState(false);
  const [availabilityError, setAvailabilityError] = useState("");
  const [pending, setPending] = useState<ReadonlyMap<number, RiderCommand>>(new Map());
  const [commandErrors, setCommandErrors] = useState<ReadonlyMap<number, string>>(new Map());
  const [notice, setNotice] = useState("");
  const profile = useLiveResource("me", fetchProfile, "Unable to load your rider profile.");
  const deliveries = useLiveResource(filter, fetchDeliveries, "Unable to load your deliveries.");
  const { reload: reloadProfile, applyAndReload: applyProfile } = profile;
  const { reload: reloadDeliveries, applyAndReload: applyDeliveries } = deliveries;

  usePolling(
    () => {
      reloadProfile();
      reloadDeliveries();
    },
    { enabled: polling, intervalMs: RIDER_POLL_MS },
  );

  const setOnDuty = useCallback(
    (onDuty: boolean) => {
      setAvailabilityPending(true);
      setAvailabilityError("");
      return updateRiderAvailability(onDuty)
        .then((updated) => applyProfile(() => updated))
        .catch((requestError: unknown) =>
          setAvailabilityError(errorCopy(requestError, "Unable to change your availability.")),
        )
        .finally(() => setAvailabilityPending(false));
    },
    [applyProfile],
  );

  const run = useCallback(
    (delivery: RiderDelivery, command: RiderCommand): Promise<boolean> => {
      const deliveryId = delivery.delivery_id;
      setPending((current) => withEntry(current, deliveryId, command));
      setCommandErrors((current) => withoutEntry(current, deliveryId));
      setNotice("");

      const call =
        command === "picked-up"
          ? markDeliveryPickedUp(deliveryId)
          : markDeliveryDelivered(deliveryId);

      return call
        .then((updated) => {
          applyDeliveries((list) =>
            list.map((item) => (item.delivery_id === updated.delivery_id ? updated : item)),
          );
          // Finishing a delivery frees the rider: refresh the active-delivery count.
          reloadProfile();
          setNotice(
            command === "picked-up"
              ? `Order #${delivery.order_id} picked up.`
              : `Order #${delivery.order_id} delivered.`,
          );
          return true;
        })
        .catch((requestError: unknown) => {
          setCommandErrors((current) =>
            withEntry(
              current,
              deliveryId,
              errorCopy(requestError, `Unable to update delivery #${deliveryId}.`),
            ),
          );
          if (
            requestError instanceof ApiError &&
            (requestError.status === 404 || requestError.status === 409)
          ) {
            reloadDeliveries();
          }
          return false;
        })
        .finally(() => {
          setPending((current) => withoutEntry(current, deliveryId));
        });
    },
    [applyDeliveries, reloadDeliveries, reloadProfile],
  );

  const markPickedUp = useCallback((delivery: RiderDelivery) => run(delivery, "picked-up"), [run]);
  const markDelivered = useCallback((delivery: RiderDelivery) => run(delivery, "delivered"), [run]);

  return {
    profile: profile.data,
    profileError: profile.error,
    availabilityPending,
    availabilityError,
    setOnDuty,
    filter,
    setFilter,
    deliveries: deliveries.data ?? [],
    deliveriesLoaded: deliveries.data !== null,
    deliveriesLoading: deliveries.loading,
    deliveriesError: deliveries.error,
    reload: () => {
      reloadProfile();
      reloadDeliveries();
    },
    polling,
    setPolling,
    pending,
    commandErrors,
    notice,
    markPickedUp,
    markDelivered,
  };
}

export type RiderConsoleState = ReturnType<typeof useRiderConsole>;
