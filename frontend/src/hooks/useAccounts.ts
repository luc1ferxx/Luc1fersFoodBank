import { useCallback, useState } from "react";
import { hasErrorCode } from "../api/client";
import {
  type CreateMerchantRequest,
  type CreateRiderRequest,
  ERROR_CODES,
  type MerchantAccount,
  type RiderAccount,
} from "../api/dto";
import {
  createMerchantAccount,
  createRiderAccount,
  getMerchantAccounts,
  getRiderAccounts,
  setMerchantAccountActive,
  setRiderAccountActive,
} from "../api/endpoints";
import { errorCopy } from "../lib/format";
import { withEntry, withoutEntry } from "../lib/maps";
import { useLiveResource } from "./useLiveResource";

/** One provisioning API (merchants or riders); declared once at module level. */
interface AccountsApi<TAccount, TCreate extends { email: string }> {
  noun: string;
  list: (key: "all", signal: AbortSignal) => Promise<TAccount[]>;
  create: (body: TCreate) => Promise<TAccount>;
  setActive: (id: number, active: boolean) => Promise<TAccount>;
  idOf: (account: TAccount) => number;
  describe: (account: TAccount) => string;
}

export const merchantAccountsApi: AccountsApi<MerchantAccount, CreateMerchantRequest> = {
  noun: "merchant",
  list: (_key, signal) => getMerchantAccounts({ signal }),
  create: createMerchantAccount,
  setActive: setMerchantAccountActive,
  idOf: (account) => account.merchant_id,
  describe: (account) => account.email,
};

export const riderAccountsApi: AccountsApi<RiderAccount, CreateRiderRequest> = {
  noun: "rider",
  list: (_key, signal) => getRiderAccounts({ signal }),
  create: createRiderAccount,
  setActive: setRiderAccountActive,
  idOf: (account) => account.rider_id,
  describe: (account) => account.email ?? account.name,
};

/**
 * The admin console's list of merchant or rider accounts with create and
 * activate/deactivate. A 409 EMAIL_ALREADY_REGISTERED on create (the email has a
 * login, perhaps from a create whose response was lost) re-reads the list so the
 * admin can see it.
 */
export function useAccounts<TAccount, TCreate extends { email: string }>(
  api: AccountsApi<TAccount, TCreate>,
) {
  const accounts = useLiveResource("all", api.list, `Unable to load the ${api.noun}s.`);
  const { reload, applyAndReload } = accounts;
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState<ReadonlyMap<number, boolean>>(new Map());
  const [rowErrors, setRowErrors] = useState<ReadonlyMap<number, string>>(new Map());

  const create = useCallback(
    async (body: TCreate): Promise<boolean> => {
      setCreating(true);
      setCreateError("");
      setNotice("");
      try {
        const account = await api.create(body);
        const id = api.idOf(account);
        applyAndReload((list) => [account, ...list.filter((item) => api.idOf(item) !== id)]);
        setNotice(`Created ${api.noun} ${api.describe(account)}.`);
        return true;
      } catch (requestError) {
        if (hasErrorCode(requestError, ERROR_CODES.emailAlreadyRegistered)) {
          setCreateError(`${body.email} already has an account. Use another email.`);
          reload();
        } else {
          setCreateError(errorCopy(requestError, `Unable to create the ${api.noun}.`));
        }
        return false;
      } finally {
        setCreating(false);
      }
    },
    [api, applyAndReload, reload],
  );

  const setActive = useCallback(
    async (account: TAccount, active: boolean) => {
      const id = api.idOf(account);
      setPending((current) => withEntry(current, id, active));
      setRowErrors((current) => withoutEntry(current, id));
      setNotice("");
      try {
        const updated = await api.setActive(id, active);
        applyAndReload((list) => list.map((item) => (api.idOf(item) === id ? updated : item)));
        setNotice(`${active ? "Activated" : "Deactivated"} ${api.noun} ${api.describe(updated)}.`);
      } catch (requestError) {
        setRowErrors((current) =>
          withEntry(
            current,
            id,
            errorCopy(requestError, `Unable to update ${api.noun} ${api.describe(account)}.`),
          ),
        );
      } finally {
        setPending((current) => withoutEntry(current, id));
      }
    },
    [api, applyAndReload],
  );

  return {
    accounts: accounts.data ?? [],
    loaded: accounts.data !== null,
    loading: accounts.loading,
    error: accounts.error,
    reload,
    creating,
    createError,
    notice,
    create,
    pending,
    rowErrors,
    setActive,
  };
}

export type AccountsState<TAccount, TCreate extends { email: string }> = ReturnType<
  typeof useAccounts<TAccount, TCreate>
>;
