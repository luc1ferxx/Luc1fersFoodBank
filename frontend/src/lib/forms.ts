/** The text value of a form field (FormData entries can also be files), or "". */
export const formText = (form: FormData, name: string): string => {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
};

/**
 * An amount typed into a money field: digits with at most two decimals ("4", "4.5",
 * "4.50"), as a number; null for anything else.
 */
export const parseAmountInput = (value: string): number | null => {
  const trimmed = value.trim();
  return /^\d+(\.\d{1,2})?$/.test(trimmed) ? Number(trimmed) : null;
};

/**
 * Whether `amount` is more than zero and less than `total`, compared in integer
 * hundredths: the rule of a counter-offer and of a partial arbitration.
 */
export const isPartialAmount = (amount: number, total: number): boolean => {
  const cents = Math.round(amount * 100);
  return cents > 0 && cents < Math.round(total * 100);
};
