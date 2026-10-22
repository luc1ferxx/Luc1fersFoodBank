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
import { orderLabel } from "../lib/labels";
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

const FORBIDDEN = 403;

const isForbidden = (error: unknown) => error instanceof ApiError && error.status === FORBIDDEN;

/**
 * The rider console: the rider's profile with the on-duty switch, and their own
 * deliveries with picked-up / delivered. Assignments arrive asynchronously from the
 * dispatch module, so both are read again on a `rider.deliveries` signal while the
 * user's stream is open, at most once a second (and every 60 seconds then), else polled
 * every 15 seconds, only while the tab is visible. The delivery commands are idempotent
 * on the server; a 404
 * (not this rider's delivery) or 409 (wrong state) re-reads the list.
 *
 * The backend re-checks the rider's active flag on every /rider/** request and
 * answers 403 for a deactivated rider (or a login bound to none). A 403 from either
 * read sets `forbidden`: polling stops, the raw 403 gives way to the console's
 * notice, and Refresh checks again, since an admin may reactivate the rider. A
 * command or availability change that comes back 403 re-reads both to find out.
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
  const forbidden = profile.errorStatus === FORBIDDEN || deliveries.errorStatus === FORBIDDEN;

  const reloadAll = useCallback(() => {
    reloadProfile();
    reloadDeliveries();
  }, [reloadProfile, reloadDeliveries]);

  // Polling a 403 would only repeat it; Refresh checks again.
  usePolling(reloadAll, {
    enabled: polling && !forbidden,
    intervalMs: RIDER_POLL_MS,
    signalTopic: "rider.deliveries",
  });

  const setOnDuty = useCallback(
    (onDuty: boolean) => {
      setAvailabilityPending(true);
      setAvailabilityError("");
      return updateRiderAvailability(onDuty)
        .then((updated) => applyProfile(() => updated))
        .catch((requestError: unknown) => {
          setAvailabilityError(errorCopy(requestError, "Unable to change your availability."));
          if (isForbidden(requestError)) {
            // Deactivated meanwhile? The profile read turns that into the console's notice.
            reloadProfile();
          }
        })
        .finally(() => setAvailabilityPending(false));
    },
    [applyProfile, reloadProfile],
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
          const label = orderLabel({ id: delivery.order_id, order_no: delivery.order_no });
          setNotice(command === "picked-up" ? `${label} picked up.` : `${label} delivered.`);
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
          if (isForbidden(requestError)) {
            reloadAll();
          } else if (
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
    [applyDeliveries, reloadAll, reloadDeliveries, reloadProfile],
  );

  const markPickedUp = useCallback((delivery: RiderDelivery) => run(delivery, "picked-up"), [run]);
  const markDelivered = useCallback((delivery: RiderDelivery) => run(delivery, "delivered"), [run]);

  return {
    profile: profile.data,
    /** A read came back 403: the rider is deactivated (or the login is not a rider). */
    forbidden,
    profileError: profile.errorStatus === FORBIDDEN ? "" : profile.error,
    availabilityPending,
    availabilityError,
    setOnDuty,
    filter,
    setFilter,
    deliveries: deliveries.data ?? [],
    deliveriesLoaded: deliveries.data !== null,
    deliveriesLoading: deliveries.loading,
    deliveriesError: deliveries.errorStatus === FORBIDDEN ? "" : deliveries.error,
    reload: reloadAll,
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
