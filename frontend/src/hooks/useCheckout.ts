import { useCallback, useRef, useState } from "react";
import { createIdempotencyKey } from "../api/client";
import type { Cart, CurrentUser, Order, PaymentDetails } from "../api/dto";
import { checkoutWithPayment } from "../api/endpoints";
import {
  errorCopy,
  formatCardNumberInput,
  formatExpiryInput,
  formatSecurityCode,
} from "../lib/format";

export type PaymentForm = PaymentDetails;
export type PaymentField = keyof PaymentForm;

export const INITIAL_PAYMENT_FORM: Readonly<PaymentForm> = Object.freeze({
  cardholder_name: "",
  card_number: "",
  expiry: "",
  cvv: "",
});

const fieldFormatters: Partial<Record<PaymentField, (value: string) => string>> = {
  card_number: formatCardNumberInput,
  expiry: formatExpiryInput,
  cvv: formatSecurityCode,
};

export const validatePaymentForm = (form: PaymentForm): string => {
  if (!form.cardholder_name.trim()) {
    return "Cardholder name is required.";
  }

  if (!/^\d{16}$/.test(form.card_number.replace(/\s+/g, ""))) {
    return "Card number must contain 16 digits.";
  }

  if (!/^(0[1-9]|1[0-2])\/\d{2}$/.test(form.expiry)) {
    return "Expiry must use MM/YY.";
  }

  if (!/^\d{3,4}$/.test(form.cvv)) {
    return "CVV must contain 3 or 4 digits.";
  }

  return "";
};

interface CheckoutOptions {
  user: CurrentUser | null;
  cart: Cart;
  clearCart: () => void;
  onCheckedOut: () => void;
  requireSession: (message: string) => void;
}

/**
 * Demo payment checkout. A retry of the same payload reuses the Idempotency-Key of
 * the failed attempt, so a request that reached the server but lost its response
 * cannot charge twice; editing the form starts a new attempt with a new key.
 */
export function useCheckout({
  user,
  cart,
  clearCart,
  onCheckedOut,
  requireSession,
}: CheckoutOptions) {
  const [form, setForm] = useState<PaymentForm>(INITIAL_PAYMENT_FORM);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [confirmation, setConfirmation] = useState<Order | null>(null);
  const attemptKeyRef = useRef<string | null>(null);
  const attemptPayloadRef = useRef("");

  const resetAttempt = () => {
    attemptKeyRef.current = null;
    attemptPayloadRef.current = "";
  };

  const updateField = useCallback((field: PaymentField, value: string) => {
    const format = fieldFormatters[field];
    attemptKeyRef.current = null;
    attemptPayloadRef.current = "";
    setForm((current) => ({ ...current, [field]: format ? format(value) : value }));
  }, []);

  const submit = (): Promise<void> => {
    if (!user) {
      requireSession("Sign in before checkout.");
      return Promise.resolve();
    }

    if (!cart.order_items.length) {
      setError("Your cart is empty.");
      return Promise.resolve();
    }

    const validationError = validatePaymentForm(form);

    if (validationError) {
      setError(validationError);
      return Promise.resolve();
    }

    const payload: PaymentDetails = {
      cardholder_name: form.cardholder_name.trim(),
      card_number: form.card_number.replace(/\s+/g, ""),
      expiry: form.expiry,
      cvv: form.cvv,
    };
    const serializedPayload = JSON.stringify(payload);

    if (attemptPayloadRef.current && attemptPayloadRef.current !== serializedPayload) {
      attemptKeyRef.current = null;
    }

    attemptPayloadRef.current = serializedPayload;
    const attemptKey = attemptKeyRef.current ?? createIdempotencyKey();
    attemptKeyRef.current = attemptKey;
    setPending(true);
    setError("");

    return checkoutWithPayment(payload, attemptKey)
      .then((order) => {
        setConfirmation(order);
        setForm(INITIAL_PAYMENT_FORM);
        resetAttempt();
        clearCart();
        onCheckedOut();
      })
      .catch((requestError: unknown) => {
        setError(errorCopy(requestError, "Payment checkout failed."));
      })
      .finally(() => {
        setPending(false);
      });
  };

  return { form, pending, error, confirmation, updateField, submit };
}

export type CheckoutState = ReturnType<typeof useCheckout>;
