/**
 * Runtime decoding of backend JSON. `request()` hands every response body over as
 * `unknown`; the endpoint functions run it through a decoder from dto.ts, so a field
 * the backend renamed, dropped, or retyped fails at the API boundary with a clear
 * message instead of rendering `undefined` deep inside a component. This is the only
 * place where untyped data turns into typed DTOs, and it needs no casts to do so.
 *
 * The backend serializes with `default-property-inclusion: non_null`, so a null field
 * is usually missing altogether: the `optional*` readers accept both and return null.
 * Fields a DTO does not declare are ignored.
 */

export type JsonObject = Readonly<Record<string, unknown>>;

export type Decoder<T> = (value: unknown, path: string) => T;

const describe = (value: unknown): string => {
  if (value === null) {
    return "null";
  }
  if (Array.isArray(value)) {
    return "an array";
  }
  return typeof value === "string" ? `the string ${JSON.stringify(value)}` : typeof value;
};

export class DecodeError extends Error {
  readonly path: string;

  constructor(path: string, expected: string, actual: unknown) {
    super(
      `Unexpected response from the server: ${path} should be ${expected}, got ${describe(actual)}.`,
    );
    this.name = "DecodeError";
    this.path = path;
  }
}

export const isJsonObject = (value: unknown): value is JsonObject =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const isString = (value: unknown): value is string => typeof value === "string";
const isNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);
const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";

const isMissing = (value: unknown): value is null | undefined =>
  value === null || value === undefined;

export const decodeArray =
  <T>(item: Decoder<T>): Decoder<T[]> =>
  (value, path) => {
    if (!Array.isArray(value)) {
      throw new DecodeError(path, "an array", value);
    }
    const entries: readonly unknown[] = value;
    return entries.map((entry, index) => item(entry, `${path}[${index}]`));
  };

/**
 * Typed readers for the fields of one JSON object. Every reader names the field path
 * in its error, e.g. `orders[2].items[0].price should be number`.
 */
export function fieldsOf(value: unknown, path: string) {
  if (!isJsonObject(value)) {
    throw new DecodeError(path, "an object", value);
  }
  const object = value;
  const at = (key: string) => `${path}.${key}`;

  const required = <T>(key: string, guard: (field: unknown) => field is T, expected: string) => {
    const field = object[key];
    if (!guard(field)) {
      throw new DecodeError(at(key), expected, field);
    }
    return field;
  };

  const optional = <T>(
    key: string,
    guard: (field: unknown) => field is T,
    expected: string,
  ): T | null => {
    const field = object[key];
    if (isMissing(field)) {
      return null;
    }
    if (!guard(field)) {
      throw new DecodeError(at(key), expected, field);
    }
    return field;
  };

  const isOneOf =
    <T extends string>(values: readonly T[]) =>
    (field: unknown): field is T =>
      values.some((candidate) => candidate === field);

  return {
    string: (key: string) => required(key, isString, "a string"),
    optionalString: (key: string) => optional(key, isString, "a string"),
    number: (key: string) => required(key, isNumber, "a number"),
    optionalNumber: (key: string) => optional(key, isNumber, "a number"),
    boolean: (key: string) => required(key, isBoolean, "a boolean"),
    oneOf: <T extends string>(key: string, values: readonly T[]) =>
      required(key, isOneOf(values), `one of ${values.join(", ")}`),
    optionalOneOf: <T extends string>(key: string, values: readonly T[]) =>
      optional(key, isOneOf(values), `one of ${values.join(", ")}`),
    array: <T>(key: string, item: Decoder<T>) => decodeArray(item)(object[key], at(key)),
    /** A list the backend omits when it is empty or not supported yet. */
    arrayOrEmpty: <T>(key: string, item: Decoder<T>) =>
      isMissing(object[key]) ? [] : decodeArray(item)(object[key], at(key)),
    optionalObject: <T>(key: string, decoder: Decoder<T>) => {
      const field = object[key];
      return isMissing(field) ? null : decoder(field, at(key));
    },
  };
}
